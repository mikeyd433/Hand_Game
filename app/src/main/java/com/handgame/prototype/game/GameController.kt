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
import com.handgame.prototype.tracking.SampleInterpolator
import com.handgame.prototype.tracking.TrackingSample
import kotlin.math.abs

enum class Phase { CALIBRATING, PLAYING, PAUSED, RESUMING }

enum class HapticEvent { GRAB, RELEASE }

/** A glowing ring on the table that guided tasks ask the player to put the cube in. */
data class Target(val center: Vec3, val radius: Float)

/** What the hand is doing, as seen by critters and props. */
data class HandState(
    val visible: Boolean,
    val palm: Vec3,
    val palmVelocity: Vec3,
    val open: Boolean,
    val tips: List<Pair<Vec3, Float>>,
)

/**
 * Something on the table the hand can pick up. The first toy is the main cube; levels and the
 * tutorial add more. Inactive toys are hidden, ignored by physics and can't be grabbed.
 */
class Toy(val body: RigidBox, val color: FloatArray, var home: Vec3, val tag: String) {
    var active = true
    internal var outOfPlayTimer = 0f
}

/** Something the cube did, for tuning recordings. */
data class GameEvent(val kind: String, val gesture: String, val cube: Vec3, val speed: Float)

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

/** What the tutorial overlay shows. */
data class TutorialUi(
    val number: Int, val count: Int, val title: String, val prompt: String,
    val progress: Float, val celebrating: Boolean, val finished: Boolean,
)

/** Snapshot of game state for the Compose UI. */
data class GameUi(
    val phase: Phase = Phase.CALIBRATING,
    val calibrationStep: CalibrationSession.Step = CalibrationSession.Step.HOLD,
    val calibrationProgress: Float = 0f,
    val calibrationHint: CalibrationSession.Hint = CalibrationSession.Hint.NONE,
    /** Current calibration target and the player's pinch point (mirrored image coords), if any. */
    val calibrationTarget: Pair<Float, Float>? = null,
    val calibrationTargetIndex: Int = 0,
    val calibrationTargetCount: Int = 0,
    val calibrationCursor: Pair<Float, Float>? = null,
    /** Raw landmarks (un-mirrored) for drawing the hand over the camera view during calibration. */
    val calibrationLandmarks: FloatArray? = null,
    val frontCamera: Boolean = true,
    /** Tutorial state, when it's running. */
    val tutorial: TutorialUi? = null,
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
    val toys = ArrayList<Toy>()
    /** The main cube (always the first toy). */
    val mainToy = Toy(
        RigidBox(Vec3(PlaySpace.CUBE_HALF, PlaySpace.CUBE_HALF, PlaySpace.CUBE_HALF), mass = 0.4f),
        floatArrayOf(0.12f, 0.76f, 0.62f), Vec3(0f, 0.35f, 0f), "cube",
    )
    val cube: RigidBox get() = mainToy.body

    /** The toy in the hand, if any. */
    var held: Toy? = null
        private set
    /** The toy within grab range of the hand (gets the highlight glow). */
    var focus: Toy? = null
        private set

    /** Adds a toy resting at [home]. */
    fun addToy(half: Vec3, color: FloatArray, home: Vec3, tag: String, mass: Float = 0.3f): Toy {
        val toy = Toy(RigidBox(half, mass), color, home, tag)
        toys += toy
        world.bodies += toy.body
        placeToy(toy, home)
        return toy
    }

    fun removeToy(toy: Toy) {
        if (toy === mainToy) { setToyActive(toy, false); return }
        if (held === toy) releaseHeld(throwIt = false)
        toys.remove(toy)
        world.bodies.remove(toy.body)
    }

    fun setToyActive(toy: Toy, active: Boolean) {
        if (toy.active == active) return
        if (!active && held === toy) releaseHeld(throwIt = false)
        toy.active = active
        if (active) { if (toy.body !in world.bodies) world.bodies += toy.body; placeToy(toy, toy.home) }
        else world.bodies.remove(toy.body)
    }

    /** Puts [toy] at [pos], resting and upright, letting go of it if held. */
    fun placeToy(toy: Toy, pos: Vec3) {
        if (held === toy) releaseHeld(throwIt = false)
        val b = toy.body
        b.position = pos
        b.orientation = Quat.IDENTITY
        b.linearVelocity = Vec3.ZERO
        b.angularVelocity = Vec3.ZERO
        b.wake()
        toy.outOfPlayTimer = 0f
    }

    private val activeToys get() = toys.filter { it.active }

    /** The tutorial room, while it's running. */
    var tutorial: Tutorial? = null
        private set
    var onTutorialFinished: (() -> Unit)? = null

    fun startTutorial() {
        tutorial?.exit()
        tutorial = Tutorial(this).also {
            it.onFinished = { onTutorialFinished?.invoke() }
            it.start()
        }
    }

    /** Leaves the tutorial (finished or not); the critter friend stays on the table. */
    fun exitTutorial() {
        tutorial?.exit()
        tutorial = null
    }

    /** Puts the critter friend on the table (awake), e.g. for free play after the tutorial. */
    fun spawnFriend() {
        if (critter == null) critter = Critter(Tutorial.CRITTER_HOME).also { it.wake() }
    }

    /** Push buttons on the table (the tutorial's "poke" lesson). */
    val buttons = ArrayList<PushButton>()
    /** The critter friend, if one is in the scene. */
    var critter: Critter? = null

    // Fingertip tracking for pokes and flicks.
    private var prevTip: Vec3? = null
    /** Smoothed index-fingertip velocity (m/s). */
    var tipVelocity = Vec3.ZERO
        private set
    private val touching = HashSet<Toy>()

    /** Optional target ring shown on the table (guided tasks). */
    var target: Target? = null

    private val events = ArrayDeque<GameEvent>()

    /** Returns and clears the events since the last call. Only the latest few are kept between calls. */
    fun drainEvents(): List<GameEvent> = ArrayList(events).also { events.clear() }

    /** Events from this frame only (the tutorial watches these). */
    private val frameEvents = ArrayList<GameEvent>()

    private fun emit(e: GameEvent) {
        frameEvents += e
        events.addLast(e)
        while (events.size > MAX_QUEUED_EVENTS) events.removeFirst()
    }

    /** The gesture the cube is currently held with (OPEN when not holding). */
    val heldWith: Gesture get() = if (holding) grabGesture else Gesture.OPEN

    /** Puts the main cube at [pos] (resting, upright), letting go of it if held. */
    fun placeCube(pos: Vec3) = placeToy(mainToy, pos)

    /** True while the hand should be drawn. */
    var handVisible = false
        private set
    /** 0..1 grab-range glow, on [highlightToy]. */
    var highlight = 0f
        private set
    var highlightToy: Toy? = null
        private set
    val holding: Boolean get() = held != null

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


    // Render-rate measurement.
    private var fpsFrames = 0
    private var fpsTime = 0f
    private var renderFps = 0f

    var trackingFpsProvider: () -> Float = { 0f }

    init {
        toys += mainToy
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
            interpolator.reset()
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

    /** Evens out tracking timing when the caller provides a real clock (see [update]). */
    private val interpolator = SampleInterpolator()
    private var interpolating = false

    /**
     * Runs one frame.
     * @param nowNs current time on the same clock as [TrackingSample.timestampNs] (capture time,
     *   System.nanoTime). When given, the hand is drawn from capture-time interpolation, evenly
     *   paced regardless of when results arrive; gestures still use each new result immediately.
     *   Without it (tests), each result drives the hand as it arrives.
     */
    fun update(dtRaw: Float, sample: TrackingSample?, nowNs: Long? = null) {
        interpolating = nowNs != null
        frameEvents.clear()
        val dt = dtRaw.coerceIn(0f, 0.1f)
        timeSec += dt
        measureFps(dt)

        val fresh = sample != null && sample.timestampNs != lastSampleNs
        if (fresh) {
            lastSampleNs = sample!!.timestampNs
            lastSample = sample
            if (interpolating && sample.frontCamera == settings.frontCamera) interpolator.push(sample)
            processSample(sample, dt)
        }
        if (interpolating) {
            if (timeSec - lastHandSeen < HAND_LOSS_GRACE) {
                val display = nowNs!! - interpolator.delayNs
                val s = interpolator.latest
                val hand = interpolator.handAt(display)
                if (hand != null && s != null) {
                    rig.update(
                        hand, s.frontCamera, s.imageWidth, s.imageHeight,
                        calibration, settings, handedness != Handedness.LEFT, view, display / 1e9,
                    )
                }
            }
        } else if (!fresh && timeSec - lastHandSeen < HAND_LOSS_GRACE) {
            // Tracking runs at ~30 fps and rendering at 60: keep the smoothing running every frame
            // on the latest sample so the hand glides between tracking updates instead of stepping.
            lastSample?.let { if (it.hand != null && it.frontCamera == settings.frontCamera) updateRig(it) }
        }

        val handPresent = timeSec - lastHandSeen < HAND_LOSS_GRACE
        handVisible = handPresent && phase != Phase.PAUSED

        updatePhase(dt, handPresent)
        if (phase == Phase.PLAYING) updateGrab(fresh) else releaseHeld(throwIt = false)
        updateHighlight(dt)

        updateTouch(dt)
        world.step(dt) { step ->
            held?.let {
                val target = heldTarget()
                world.drive(it.body, target.first, target.second, step)
            }
        }
        checkToyBounds(dt)
        rig.pose.palmCenter.let { pc ->
            lastPalm?.let { recentPalmVelocity = Vec3.lerp(recentPalmVelocity, (pc - it) / dt.coerceAtLeast(1e-3f), (10f * dt).coerceAtMost(1f)) }
            lastPalm = pc
        }
        updateGripPose(dt)
        if (phase == Phase.PLAYING) tutorial?.update(dt, frameEvents)
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
        if (interpolating) rig.measure(hand, sample.frontCamera, sample.imageWidth, sample.imageHeight, calibration)
        else updateRig(sample)
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
                pinchU = pinchUv(hand, sample.frontCamera).first,
                pinchV = pinchUv(hand, sample.frontCamera).second,
            )
            if (session.step == CalibrationSession.Step.TARGETS && previousCalStep == CalibrationSession.Step.HOLD) {
                // Use the fresh baseline right away.
                provisionalFromHold(u, v, size, HandMeasure.worldPalmLength(hand))
            }
            previousCalStep = session.step
            if (done) finishCalibration(session)
        }
    }

    /** Pinch point (between thumb and index tips) in the player's (mirrored) image coordinates. */
    private fun pinchUv(hand: com.handgame.prototype.tracking.RawHand, front: Boolean): Pair<Float, Float> {
        val u = (hand.image[Lm.THUMB_TIP * 2] + hand.image[Lm.INDEX_TIP * 2]) / 2
        val v = (hand.image[Lm.THUMB_TIP * 2 + 1] + hand.image[Lm.INDEX_TIP * 2 + 1]) / 2
        return Pair(if (front) 1f - u else u, v)
    }

    /** Pulls the current calibration target toward the middle (when it's hard to reach). */
    fun pullCalibrationTargetIn() { calibrationSession?.pullTargetIn() }

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

    private fun reachDistance(toy: Toy): Float {
        val p = rig.pose
        val b = toy.body
        val contact = minOf(b.distanceTo(p.pinchPoint), b.distanceTo(p.fistPoint), b.distanceTo(p.palmCenter) - 0.02f * HandLook.scale)
        // The view is mostly top-down, where height is hard to judge. Count the hand as in reach
        // when it's over the toy on screen and not too far above it.
        return minOf(contact, overheadDistance(b, p.pinchPoint), overheadDistance(b, p.fistPoint))
    }

    /** Horizontal distance from [point] to [b]'s footprint, if [point] is within reach above it. */
    private fun overheadDistance(b: RigidBox, point: Vec3): Float {
        val top = b.position.y + b.halfExtents.y
        val above = point.y - top
        if (above < -b.halfExtents.y || above > OVERHEAD_REACH) return Float.MAX_VALUE
        val flat = Vec3(point.x, b.position.y, point.z)
        return b.distanceTo(flat)
    }

    /** The active toy nearest the hand's grab points, and its reach distance. */
    private fun nearestToy(): Pair<Toy, Float>? =
        activeToys.map { it to reachDistance(it) }.minByOrNull { it.second }

    /** The toy the drawn hand should account for (held, else the one closest to the palm). */
    private fun contactToy(): Toy? = held ?: activeToys.minByOrNull { it.body.distanceTo(rig.pose.palmCenter) }

    private var lastNearToy: Toy? = null

    private fun updateGrab(fresh: Boolean) {
        if (!handVisible) return
        val g = gestures.state
        val nearest = nearestToy()
        val nearToy = nearest?.takeIf { it.second < GRAB_RANGE }?.first
        if (nearToy != null && g == Gesture.OPEN) { lastNearTime = timeSec; lastNearToy = nearToy }

        if (!holding) {
            val closedNow = g != Gesture.OPEN && grabGesture == Gesture.OPEN
            // Closing the hand moves the fingertips, so accept a grab if the open hand was in range
            // a moment ago, not just this exact frame.
            val candidate = nearToy ?: lastNearToy?.takeIf { timeSec - lastNearTime < 0.3 && it.active }
            if (closedNow && candidate != null) startGrab(g, candidate)
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

    private fun startGrab(g: Gesture, toy: Toy) {
        held = toy
        val cube = toy.body
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
        emit(GameEvent("grab", g.name, cube.position, 0f))

        // Settle position: centered between thumb and index, nudged away from the palm (pinch), or
        // resting flat under the palm and slightly toward the fingers (fist).
        heldTargetOffset = if (g == Gesture.FIST) {
            // Under the palm and tucked back toward the wrist, so the fingers curl down in front of
            // it (like gripping a block from above) instead of into it.
            Vec3(0f, -(HandLook.PALM_HALF_THICKNESS + cube.halfExtents.y + 0.004f), 0.035f * HandLook.scale)
        } else {
            // Out beyond the fingertips, so the index finger's bones clear the cube.
            val out = (p.pinchPoint - p.palmCenter).normalized()
            inv.rotate(out * (cube.halfExtents.x * 0.45f))
        }
        onHaptic(HapticEvent.GRAB)
    }

    private fun heldTarget(): Pair<Vec3, Quat> {
        val p = rig.pose
        var pos = grabAnchor(grabGesture) + p.rotation.rotate(heldLocalOffset)
        val floor = (held?.body?.halfExtents?.y ?: PlaySpace.CUBE_HALF) * 0.9f
        if (pos.y < floor) pos = Vec3(pos.x, floor, pos.z)
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
            GripSolver.solve(p.joints, p.renderJoints, held!!.body, grabGesture, gripAxis, gripWeight, forward, thumbSide)
            for (i in gripOffsets.indices) gripOffsets[i] = p.renderJoints[i] - p.joints[i]
        } else {
            // After release, let the fingers relax back to the tracked pose instead of snapping
            // (or chasing a thrown cube).
            val decay = kotlin.math.exp(-14f * dt)
            for (i in gripOffsets.indices) {
                gripOffsets[i] = gripOffsets[i] * decay
                relaxed[i] = p.joints[i] + gripOffsets[i]
            }
            GripSolver.solve(relaxed, p.renderJoints, contactToy()?.body ?: cube, Gesture.OPEN, gripAxis, 0f, Vec3.ZERO, Vec3.ZERO)
        }
        resolveCollisions(dt)
    }

    /** Overlap stats of the drawn hand this frame (before/after resolving), for tuning. */
    val collisionStats = HandCollision.Stats()
    private val collisionScratch = Array(Lm.COUNT) { Vec3.ZERO }
    private val collisionOffsets = Array(Lm.COUNT) { Vec3.ZERO }

    /**
     * Keeps the drawn hand from passing through itself, the table or the cube. Corrections are
     * eased in over a few frames so a finger never visibly pops.
     */
    private fun resolveCollisions(dt: Float) {
        val p = rig.pose
        for (i in 0 until Lm.COUNT) collisionScratch[i] = p.renderJoints[i]
        val overTable = abs(p.palmCenter.x) < PlaySpace.TABLE_HALF_X + 0.05f && abs(p.palmCenter.z) < PlaySpace.TABLE_HALF_Z + 0.05f
        val bumps = critter?.let { listOf(HandCollision.Bump(it.position, Critter.BASE_RADIUS * 1.4f, it.top)) } ?: emptyList()
        HandCollision.solve(collisionScratch, p.rotation, contactToy()?.body, if (overTable) 0f else null, collisionStats, holdingCube = holding, bumps = bumps)
        val blend = (COLLISION_EASE * dt).coerceIn(0f, 1f)
        for (i in 0 until Lm.COUNT) {
            val target = collisionScratch[i] - p.renderJoints[i]
            collisionOffsets[i] = Vec3.lerp(collisionOffsets[i], target, blend)
            p.renderJoints[i] = p.renderJoints[i] + collisionOffsets[i]
        }
    }

    private val gripOffsets = Array(Lm.COUNT) { Vec3.ZERO }
    private val relaxed = Array(Lm.COUNT) { Vec3.ZERO }

    private fun releaseHeld(throwIt: Boolean) {
        val toy = held ?: return
        held = null
        val cube = toy.body
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
        emit(GameEvent(if (throwIt) "release" else "drop_forced", grabGesture.name, cube.position, cube.linearVelocity.length()))
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
        val nearest = if (phase == Phase.PLAYING && handVisible && !holding) nearestToy() else null
        focus = nearest?.takeIf { it.second < GRAB_RANGE }?.first
        val wanted = held ?: focus
        if (wanted != null && wanted !== highlightToy) { highlightToy = wanted; highlight = 0f }
        val target = when {
            holding -> 0.6f
            focus != null -> 1f
            else -> 0f
        }
        val rate = 10f * dt
        highlight += (target - highlight) * rate.coerceAtMost(1f)
    }

    // ------------------------------------------------------------------------------------------
    // Pokes, flicks, buttons, critter
    // ------------------------------------------------------------------------------------------

    /** Fingertips (tracked joints) with their drawn radius. */
    private fun fingertips(): List<Pair<Vec3, Float>> {
        val j = rig.pose.joints
        return listOf(Lm.INDEX_TIP, Lm.MIDDLE_TIP, Lm.THUMB_TIP).map { j[it] to HandLook.jointRadius(it) }
    }

    private fun updateTouch(dt: Float) {
        val live = phase == Phase.PLAYING && handVisible
        val tip = rig.pose.joints[Lm.INDEX_TIP]
        if (live) {
            prevTip?.let { prev ->
                val v = (tip - prev) / dt.coerceAtLeast(1e-3f)
                tipVelocity = Vec3.lerp(tipVelocity, v, (TIP_VEL_SMOOTH * dt).coerceAtMost(1f))
            }
            prevTip = tip
        } else { prevTip = null; tipVelocity = Vec3.ZERO }

        val tips = if (live) fingertips() else emptyList()
        val pointer = if (live && gestures.pointing) tip to rig.pose.dip else null
        for (b in buttons) b.update(dt, tips, pointer)

        // A pointing fingertip pushes toys: a quick flick sends them flying, slow contact nudges
        // them. (Only when pointing, so an open hand reaching to grab doesn't shove things away.)
        val canTouch = live && !holding && gestures.pointing && gestures.state == Gesture.OPEN
        val nowTouching = HashSet<Toy>()
        if (canTouch) for (toy in activeToys) {
            val b = toy.body
            val contact = tips.take(2).any { (t, r) -> b.distanceTo(t) < r }
            if (!contact) continue
            nowTouching += toy
            val flat = Vec3(tipVelocity.x, 0f, tipVelocity.z)
            if (toy !in touching && flat.length() > FLICK_SPEED) {
                b.wake()
                b.linearVelocity = (flat * FLICK_GAIN).clampLength(FLICK_MAX_SPEED) + Vec3(0f, 0.6f, 0f)
                b.angularVelocity = Vec3(flat.z, 0f, -flat.x) * 8f
                emit(GameEvent("flick", "", b.position, b.linearVelocity.length()))
            } else if (flat.length() > 0.05f) {
                b.wake()
                val push = flat * NUDGE_GAIN
                b.linearVelocity = Vec3(push.x, b.linearVelocity.y, push.z)
            }
        }
        touching.clear(); touching += nowTouching

        critter?.update(dt, this)
    }

    /** For the critter: what the hand is doing this frame. */
    internal fun handForCritter(): HandState = HandState(
        visible = phase == Phase.PLAYING && handVisible,
        palm = rig.pose.palmCenter,
        palmVelocity = recentPalmVelocity,
        open = gestures.state == Gesture.OPEN && !gestures.pointing,
        tips = fingertips(),
    )

    private var lastPalm: Vec3? = null
    private var recentPalmVelocity = Vec3.ZERO

    internal fun emitEvent(kind: String, at: Vec3) = emit(GameEvent(kind, "", at, 0f))

    // ------------------------------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------------------------------

    private fun checkToyBounds(dt: Float) {
        for (toy in activeToys) {
            val pos = toy.body.position
            val offTable = pos.y < -0.2f || abs(pos.x) > 3f || abs(pos.z) > 3f
            toy.outOfPlayTimer = if (offTable && held !== toy) toy.outOfPlayTimer + dt else 0f
            if (toy.outOfPlayTimer > 1.2f || pos.y < -5f) respawnToy(toy)
        }
    }

    fun respawnCube() = respawnToy(mainToy)

    /** Drops [toy] back onto the table at its home, with a little tilt so it tumbles in. */
    fun respawnToy(toy: Toy) {
        if (held === toy) releaseHeld(throwIt = false)
        val b = toy.body
        emit(GameEvent("respawn", toy.tag, b.position, 0f))
        b.position = toy.home
        b.orientation = Quat.fromAxisAngle(Vec3(0.3f, 1f, 0.2f), 0.5f)
        b.linearVelocity = Vec3.ZERO
        b.angularVelocity = Vec3.ZERO
        b.wake()
        toy.outOfPlayTimer = 0f
        onToyRespawned?.invoke(toy)
    }

    /** Called when a toy falls off and is put back (the tutorial uses this). */
    var onToyRespawned: ((Toy) -> Unit)? = null

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
            calibrationTarget = session?.target,
            calibrationTargetIndex = session?.targetIndex ?: 0,
            calibrationTargetCount = session?.targetCount ?: 0,
            calibrationCursor = lastSample?.hand?.takeIf { handVisible && phase == Phase.CALIBRATING }?.let { pinchUv(it, lastSample!!.frontCamera) },
            calibrationLandmarks = if (phase == Phase.CALIBRATING && handVisible) lastSample?.hand?.image else null,
            frontCamera = settings.frontCamera,
            tutorial = tutorial?.let { t ->
                TutorialUi(t.lessonNumber, t.lessonCount, t.lesson.title, t.prompt(settings.frontCamera), t.progress, t.celebrating > 0f, t.finished)
            },
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
        /** Fingertip speed (m/s) that turns a touch into a flick. */
        const val FLICK_SPEED = 0.7f
        const val FLICK_GAIN = 1.4f
        const val FLICK_MAX_SPEED = 4.5f
        const val NUDGE_GAIN = 0.8f
        const val TIP_VEL_SMOOTH = 25f
        /** Cube events kept while nobody is recording them. */
        const val MAX_QUEUED_EVENTS = 64
        /** How quickly collision corrections ease in (per second; ~60 ms to settle). */
        const val COLLISION_EASE = 30f
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
