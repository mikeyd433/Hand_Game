package com.handgame.prototype.game

import com.handgame.prototype.input.Calibration
import com.handgame.prototype.input.CalibrationSession
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.input.GestureDetector
import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.physics.PhysicsWorld
import com.handgame.prototype.physics.RigidBox
import com.handgame.prototype.tracking.HandMeasure
import com.handgame.prototype.tracking.Handedness
import com.handgame.prototype.tracking.HandednessVoter
import com.handgame.prototype.tracking.Lm
import com.handgame.prototype.tracking.TrackingSample
import kotlin.math.abs

enum class Phase { CALIBRATING, PLAYING, PAUSED, RESUMING }

enum class HapticEvent { GRAB, RELEASE }

/** Everything the debug overlay shows. */
data class DebugInfo(
    val gesture: Gesture,
    val pinchDistance: Float,
    val maxCurl: Float,
    val lift: Float,
    val sizeRatio: Float,
    val renderFps: Float,
    val trackingFps: Float,
    val inferenceMs: Float,
    val confidence: Float,
    val handedness: Handedness,
    val handPresent: Boolean,
    val holding: Boolean,
    val cubeSleeping: Boolean,
    /** Raw (un-mirrored) normalized landmarks for drawing over the camera frame. */
    val landmarks: FloatArray?,
)

/** Snapshot of game state for the Compose UI. */
data class GameUi(
    val phase: Phase = Phase.CALIBRATING,
    val calibrationStep: CalibrationSession.Step = CalibrationSession.Step.HOLD,
    val calibrationProgress: Float = 0f,
    val calibrationHint: CalibrationSession.Hint = CalibrationSession.Hint.NONE,
    val countdown: Int = 0,
    val handFoundFlash: Boolean = false,
    /** 0..1 strength of the "hand is near the edge of the camera's view" glow, and its edge. */
    val edgeWarning: Float = 0f,
    val edgeX: Int = 0,
    val edgeY: Int = 0,
    val debug: DebugInfo? = null,
)

/**
 * The prototype's game logic. Runs once per rendered frame on the main thread:
 * tracking → smoothing → gestures → grab/throw → physics, plus calibration and auto-pause.
 */
class GameController(
    initialSettings: GameSettings,
    initialCalibration: Calibration?,
    private val onHaptic: (HapticEvent) -> Unit = {},
    private val onCalibrated: (Calibration) -> Unit = {},
) {
    var settings: GameSettings = initialSettings
        private set

    var calibration: Calibration = initialCalibration ?: DEFAULT_CALIBRATION
        private set

    /** Calibrations from older builds lack the measured hand reach, so ask for a fresh one. */
    private val needsCalibration = initialCalibration?.extents == null

    var phase: Phase = if (needsCalibration) Phase.CALIBRATING else Phase.PAUSED
        private set

    private var calibrationSession: CalibrationSession? =
        if (needsCalibration) CalibrationSession() else null

    val rig = HandRig().also {
        it.applySettings(initialSettings)
        HandLook.scale = initialSettings.handScale
    }
    /** Scene camera, shared with the renderer (which refits it when the screen size changes). */
    val view = ViewRig()
    val gestures = GestureDetector()
    private val handednessVoter = HandednessVoter()

    val world = PhysicsWorld(PlaySpace.TABLE_HALF_X, PlaySpace.TABLE_HALF_Z)
    val cube = RigidBox(Vec3(PlaySpace.CUBE_HALF, PlaySpace.CUBE_HALF, PlaySpace.CUBE_HALF), mass = 0.4f)

    /** True while the hand should be drawn. */
    var handVisible = false
        private set
    /** 0..1 grab-range glow on the cube. */
    var highlight = 0f
        private set
    var holding = false
        private set

    var handedness = Handedness.UNKNOWN
        private set

    // Hand presence.
    private var timeSec = 0.0
    private var lastHandSeen = -10.0
    private var lastSampleNs = 0L
    private var lastSample: TrackingSample? = null

    // Grab state.
    private var grabGesture = Gesture.OPEN
    private var lastNearTime = -10.0
    private var heldLocalOffset = Vec3.ZERO
    private var heldTargetOffset = Vec3.ZERO
    private var heldRelRotation = Quat.IDENTITY
    private var heldRelTarget = Quat.IDENTITY
    /** Cube-local axis facing the thumb (pinch) or the palm (fist). */
    private var gripAxis = Vec3.UP
    /** 0..1 blend of the drawn grip pose. Fades out after release. */
    var gripWeight = 0f
        private set
    private val anchorHistory = ArrayDeque<Pair<Double, Vec3>>()

    // Pause / resume.
    private var resumeTimer = 0f
    private var handFoundFlashTimer = 0f

    // Cube respawn.
    private var outOfPlayTimer = 0f

    // Render-rate measurement.
    private var fpsFrames = 0
    private var fpsTime = 0f
    private var renderFps = 0f

    var trackingFpsProvider: () -> Float = { 0f }

    init {
        world.bodies.add(cube)
        respawnCube()
    }

    // ------------------------------------------------------------------------------------------
    // Public controls
    // ------------------------------------------------------------------------------------------

    fun updateSettings(s: GameSettings) {
        val cameraChanged = s.frontCamera != settings.frontCamera
        settings = s
        rig.applySettings(s)
        HandLook.scale = s.handScale
        if (cameraChanged) {
            // A different camera means a different hand pose and distance: recalibrate.
            handednessVoter.reset()
            recalibrate()
        }
    }

    fun recalibrate() {
        releaseHeld(throwIt = false)
        calibrationSession = CalibrationSession()
        phase = Phase.CALIBRATING
        rig.reset()
        gestures.reset()
    }

    fun skipRangeCalibration() {
        val session = calibrationSession ?: return
        if (session.skipMove()) finishCalibration(session)
    }

    // ------------------------------------------------------------------------------------------
    // Frame update
    // ------------------------------------------------------------------------------------------

    fun update(dtRaw: Float, sample: TrackingSample?) {
        val dt = dtRaw.coerceIn(0f, 0.1f)
        timeSec += dt
        measureFps(dt)

        val fresh = sample != null && sample.timestampNs != lastSampleNs
        if (fresh) {
            lastSampleNs = sample!!.timestampNs
            lastSample = sample
            processSample(sample, dt)
        } else if (timeSec - lastHandSeen < HAND_LOSS_GRACE) {
            // Tracking runs at ~30 fps and rendering at 60: keep the smoothing running every frame
            // on the latest sample so the hand glides between tracking updates instead of stepping.
            lastSample?.let { if (it.hand != null && it.frontCamera == settings.frontCamera) updateRig(it) }
        }

        val handPresent = timeSec - lastHandSeen < HAND_LOSS_GRACE
        handVisible = handPresent && phase != Phase.PAUSED

        updatePhase(dt, handPresent)
        if (phase == Phase.PLAYING) updateGrab(fresh) else releaseHeld(throwIt = false)
        updateHighlight(dt)

        world.step(dt) { step ->
            if (holding) {
                val target = heldTarget()
                world.drive(cube, target.first, target.second, step)
            }
        }
        checkCubeBounds(dt)
        updateGripPose(dt)
        if (handFoundFlashTimer > 0f) handFoundFlashTimer -= dt
    }

    private fun processSample(sample: TrackingSample, dt: Float) {
        val hand = sample.hand ?: return
        if (sample.frontCamera != settings.frontCamera) return // stale frame from the old camera
        lastHandSeen = timeSec

        val voted = handednessVoter.add(HandMeasure.physicalHandedness(hand.mpLabel))
        handedness = when (settings.handedness) {
            HandednessSetting.LEFT -> Handedness.LEFT
            HandednessSetting.RIGHT -> Handedness.RIGHT
            HandednessSetting.AUTO -> voted
        }

        val t = timeSec
        updateRig(sample)
        gestures.update(rig.gestureShape, rig.pinch2d)

        anchorHistory.addLast(t to currentAnchor())
        while (anchorHistory.size > 12) anchorHistory.removeFirst()

        calibrationSession?.let { session ->
            if (phase != Phase.CALIBRATING) return@let
            val (u, v) = HandMeasure.palmCenterUv(hand, sample.frontCamera)
            val size = HandMeasure.apparentSize(hand, sample.imageWidth, sample.imageHeight)
            val done = session.update(
                dt = sampleDt(),
                handPresent = true,
                gesture = gestures.state,
                u = u, v = v, size = size,
                worldPalm = HandMeasure.worldPalmLength(hand),
                extents = HandMeasure.extents(hand, sample.frontCamera),
                edgeMargin = HandMeasure.edgeMargin(hand),
            )
            if (session.step == CalibrationSession.Step.MOVE && previousCalStep == CalibrationSession.Step.HOLD) {
                // Use the fresh baseline right away so the hand moves correctly during the sweep.
                provisionalFromHold(u, v, size, HandMeasure.worldPalmLength(hand))
            }
            previousCalStep = session.step
            if (done) finishCalibration(session)
        }
    }

    private fun updateRig(sample: TrackingSample) {
        rig.update(
            sample.hand!!, sample.frontCamera, sample.imageWidth, sample.imageHeight,
            calibration, settings, handedness != Handedness.LEFT, view, timeSec,
        )
    }

    private var previousCalStep = CalibrationSession.Step.HOLD
    private var lastCalSampleTime = -1.0

    /** Time between tracking samples, for calibration timing. */
    private fun sampleDt(): Float {
        val dt = if (lastCalSampleTime < 0) 0f else (timeSec - lastCalSampleTime).toFloat()
        lastCalSampleTime = timeSec
        return dt.coerceIn(0f, 0.2f)
    }

    private fun provisionalFromHold(u: Float, v: Float, size: Float, palm: Float) {
        calibration = calibration.copy(centerU = u, centerV = v, baseSize = size, worldPalmLength = palm)
        rig.reset()
    }

    private fun finishCalibration(session: CalibrationSession) {
        val result = session.result ?: return
        calibration = result
        calibrationSession = null
        previousCalStep = CalibrationSession.Step.HOLD
        lastCalSampleTime = -1.0
        rig.reset()
        onCalibrated(result)
        phase = Phase.RESUMING
        resumeTimer = RESUME_SECONDS
        handFoundFlashTimer = 0f
    }

    private fun updatePhase(dt: Float, handPresent: Boolean) {
        when (phase) {
            Phase.CALIBRATING -> {
                if (!handPresent) calibrationSession?.let {
                    if (it.step == CalibrationSession.Step.HOLD) it.update(dt, false, Gesture.OPEN, 0f, 0f, 0f, 0f)
                }
            }
            Phase.PLAYING -> if (!handPresent) {
                // Auto-pause; any held object is set down gently.
                releaseHeld(throwIt = false)
                phase = Phase.PAUSED
            }
            Phase.PAUSED -> if (handPresent) {
                phase = Phase.RESUMING
                resumeTimer = RESUME_SECONDS
                handFoundFlashTimer = 0.8f
            }
            Phase.RESUMING -> {
                if (!handPresent) {
                    phase = Phase.PAUSED
                } else {
                    resumeTimer -= dt
                    if (resumeTimer <= 0f) {
                        phase = Phase.PLAYING
                        // Don't let a fist held during the countdown count as a fresh grab.
                        grabGesture = gestures.state
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Grabbing and throwing
    // ------------------------------------------------------------------------------------------

    private fun currentAnchor(): Vec3 =
        if (holding && grabGesture == Gesture.FIST) rig.pose.palmCenter else rig.pose.pinchPoint

    private fun reachDistance(): Float {
        val p = rig.pose
        val contact = minOf(cube.distanceTo(p.pinchPoint), cube.distanceTo(p.fistPoint), cube.distanceTo(p.palmCenter) - 0.02f * HandLook.scale)
        // The view is mostly top-down, where height is hard to judge. Count the hand as in reach
        // when it's over the cube on screen and not too far above it.
        return minOf(contact, overheadDistance(p.pinchPoint), overheadDistance(p.fistPoint))
    }

    /** Horizontal distance from [point] to the cube's footprint, if [point] is within reach above it. */
    private fun overheadDistance(point: Vec3): Float {
        val top = cube.position.y + PlaySpace.CUBE_HALF
        val above = point.y - top
        if (above < -PlaySpace.CUBE_HALF || above > OVERHEAD_REACH) return Float.MAX_VALUE
        val flat = Vec3(point.x, cube.position.y, point.z)
        return cube.distanceTo(flat)
    }

    private fun updateGrab(fresh: Boolean) {
        if (!handVisible) return
        val g = gestures.state
        val near = reachDistance() < GRAB_RANGE
        if (near && g == Gesture.OPEN) lastNearTime = timeSec

        if (!holding) {
            val closedNow = g != Gesture.OPEN && grabGesture == Gesture.OPEN
            // Closing the hand moves the fingertips, so accept a grab if the open hand was in range
            // a moment ago, not just this exact frame.
            if (closedNow && (near || timeSec - lastNearTime < 0.3)) startGrab(g)
            grabGesture = g
        } else {
            if (g == Gesture.OPEN) {
                releaseHeld(throwIt = true)
                grabGesture = Gesture.OPEN
            } else {
                // A loosening fist can read as a pinch; keep holding either way.
                // Soft snap: ease the held offset toward its settled position.
                heldLocalOffset = Vec3.lerp(heldLocalOffset, heldTargetOffset, 0.18f)
                heldRelRotation = Quat.slerp(heldRelRotation, heldRelTarget, 0.18f)
            }
        }
    }

    private fun startGrab(g: Gesture) {
        holding = true
        grabGesture = g
        cube.held = true
        cube.wake()
        val p = rig.pose
        val anchor = grabAnchor(g)
        val inv = p.rotation.conjugate()
        heldLocalOffset = inv.rotate(cube.position - anchor)

        // Which way the grip squeezes: thumb → index for a pinch, palm-down for a fist.
        val palmUp = p.rotation.rotate(Vec3.UP)
        val squeeze = if (g == Gesture.PINCH) {
            val d = p.joints[Lm.THUMB_TIP] - p.joints[Lm.INDEX_TIP]
            if (d.length() > 1e-3f) d.normalized() else p.rotation.rotate(Vec3(1f, 0f, 0f))
        } else palmUp
        // Turn the cube (softly) so a face lines up with the squeeze, letting fingers sit flat on it.
        gripAxis = GripSolver.nearestAxis(cube, squeeze)
        val aligned = (Quat.fromTo(cube.orientation.rotate(gripAxis), squeeze) * cube.orientation).normalized()
        heldRelRotation = (inv * cube.orientation).normalized()
        heldRelTarget = (inv * aligned).normalized()

        // Settle position: centered between thumb and index, nudged away from the palm (pinch), or
        // resting flat under the palm and slightly toward the fingers (fist).
        heldTargetOffset = if (g == Gesture.FIST) {
            Vec3(0f, -(HandLook.PALM_HALF_THICKNESS + PlaySpace.CUBE_HALF + 0.004f), -0.03f * HandLook.scale)
        } else {
            val out = (p.pinchPoint - p.palmCenter).normalized()
            inv.rotate(out * 0.015f)
        }
        onHaptic(HapticEvent.GRAB)
    }

    private fun heldTarget(): Pair<Vec3, Quat> {
        val p = rig.pose
        var pos = grabAnchor(grabGesture) + p.rotation.rotate(heldLocalOffset)
        if (pos.y < PlaySpace.CUBE_HALF * 0.9f) pos = Vec3(pos.x, PlaySpace.CUBE_HALF * 0.9f, pos.z)
        return pos to (p.rotation * heldRelRotation).normalized()
    }

    private fun grabAnchor(g: Gesture): Vec3 =
        if (g == Gesture.FIST) rig.pose.palmCenter else rig.pose.pinchPoint

    /** Computes the drawn hand: blends the grip pose in/out and keeps fingers out of the cube. */
    private fun updateGripPose(dt: Float) {
        val target = if (holding) 1f else 0f
        gripWeight += (target - gripWeight) * (12f * dt).coerceAtMost(1f)
        if (!holding && gripWeight < 0.01f) gripWeight = 0f
        val p = rig.pose
        if (holding) {
            val forward = p.rotation.rotate(Vec3(0f, 0f, -1f))
            val right = p.rotation.rotate(Vec3(1f, 0f, 0f))
            val thumbSide = if ((p.joints[Lm.THUMB_MCP] - p.palmCenter) dot right >= 0f) right else -right
            GripSolver.solve(p.joints, p.renderJoints, cube, grabGesture, gripAxis, gripWeight, forward, thumbSide)
            for (i in gripOffsets.indices) gripOffsets[i] = p.renderJoints[i] - p.joints[i]
        } else {
            // After release, let the fingers relax back to the tracked pose instead of snapping
            // (or chasing a thrown cube).
            val decay = kotlin.math.exp(-14f * dt)
            for (i in gripOffsets.indices) {
                gripOffsets[i] = gripOffsets[i] * decay
                relaxed[i] = p.joints[i] + gripOffsets[i]
            }
            GripSolver.solve(relaxed, p.renderJoints, cube, Gesture.OPEN, gripAxis, 0f, Vec3.ZERO, Vec3.ZERO)
        }
    }

    private val gripOffsets = Array(Lm.COUNT) { Vec3.ZERO }
    private val relaxed = Array(Lm.COUNT) { Vec3.ZERO }

    private fun releaseHeld(throwIt: Boolean) {
        if (!holding) return
        holding = false
        cube.held = false
        cube.wake()
        if (throwIt) {
            val v = recentHandVelocity()
            cube.linearVelocity = if (v.length() > THROW_MIN_SPEED) (v * THROW_GAIN).clampLength(THROW_MAX_SPEED)
            else cube.linearVelocity * 0.3f
            cube.angularVelocity = cube.angularVelocity * 0.5f
        } else {
            cube.linearVelocity = Vec3.ZERO
            cube.angularVelocity = Vec3.ZERO
        }
        onHaptic(HapticEvent.RELEASE)
    }

    /** Average hand velocity over roughly the last 100 ms of tracking samples. */
    private fun recentHandVelocity(): Vec3 {
        if (anchorHistory.size < 2) return Vec3.ZERO
        val (tEnd, pEnd) = anchorHistory.last()
        var start = anchorHistory.first()
        for (i in anchorHistory.indices.reversed()) {
            if (tEnd - anchorHistory[i].first >= 0.1) { start = anchorHistory[i]; break }
        }
        val dt = (tEnd - start.first).toFloat()
        if (dt < 1e-3f) return Vec3.ZERO
        return (pEnd - start.second) / dt
    }

    private fun updateHighlight(dt: Float) {
        val target = when {
            holding -> 0.6f
            phase == Phase.PLAYING && handVisible && reachDistance() < GRAB_RANGE -> 1f
            else -> 0f
        }
        val rate = 10f * dt
        highlight += (target - highlight) * rate.coerceAtMost(1f)
    }

    // ------------------------------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------------------------------

    private fun checkCubeBounds(dt: Float) {
        val pos = cube.position
        val offTable = pos.y < -0.2f || abs(pos.x) > 3f || abs(pos.z) > 3f
        outOfPlayTimer = if (offTable && !holding) outOfPlayTimer + dt else 0f
        if (outOfPlayTimer > 1.2f || pos.y < -5f) respawnCube()
    }

    fun respawnCube() {
        releaseHeld(throwIt = false)
        cube.position = Vec3(0f, 0.35f, 0f)
        cube.orientation = Quat.fromAxisAngle(Vec3(0.3f, 1f, 0.2f), 0.5f)
        cube.linearVelocity = Vec3.ZERO
        cube.angularVelocity = Vec3.ZERO
        cube.wake()
        outOfPlayTimer = 0f
    }

    private fun measureFps(dt: Float) {
        fpsFrames++
        fpsTime += dt
        if (fpsTime >= 0.5f) {
            renderFps = fpsFrames / fpsTime
            fpsFrames = 0
            fpsTime = 0f
        }
    }

    fun uiSnapshot(withDebug: Boolean): GameUi {
        val session = calibrationSession
        val debug = if (withDebug) {
            val s = lastSample
            DebugInfo(
                gesture = gestures.state,
                pinchDistance = gestures.metrics.pinchDistance,
                maxCurl = gestures.metrics.maxCurl,
                lift = rig.pose.lift,
                sizeRatio = rig.pose.sizeRatio,
                renderFps = renderFps,
                trackingFps = trackingFpsProvider(),
                inferenceMs = s?.inferenceMs ?: 0f,
                confidence = s?.hand?.confidence ?: 0f,
                handedness = handedness,
                handPresent = handVisible,
                holding = holding,
                cubeSleeping = cube.sleeping,
                landmarks = s?.hand?.image,
            )
        } else null
        return GameUi(
            phase = phase,
            calibrationStep = session?.step ?: CalibrationSession.Step.DONE,
            calibrationProgress = session?.progress ?: 1f,
            calibrationHint = session?.hint ?: CalibrationSession.Hint.NONE,
            countdown = if (phase == Phase.RESUMING) kotlin.math.ceil(resumeTimer / (RESUME_SECONDS / 3f)).toInt().coerceIn(1, 3) else 0,
            handFoundFlash = handFoundFlashTimer > 0f,
            edgeWarning = if (handVisible && phase != Phase.CALIBRATING) rig.pose.edgeWarning else 0f,
            edgeX = rig.pose.edgeX,
            edgeY = rig.pose.edgeY,
            debug = debug,
        )
    }

    companion object {
        /** How long the hand may vanish before the game pauses. */
        const val HAND_LOSS_GRACE = 0.4
        const val RESUME_SECONDS = 1.2f
        const val GRAB_RANGE = 0.06f
        /** How far above the cube a grab still reaches (see [overheadDistance]). */
        const val OVERHEAD_REACH = 0.20f
        const val THROW_GAIN = 1.15f
        const val THROW_MIN_SPEED = 0.35f
        const val THROW_MAX_SPEED = 6f

        val DEFAULT_CALIBRATION = Calibration(
            baseSize = 0.22f,
            centerU = 0.5f,
            centerV = 0.5f,
            rangeU = Calibration.DEFAULT_RANGE_U,
            rangeV = Calibration.DEFAULT_RANGE_V,
            worldPalmLength = 0.085f,
        )
    }
}
