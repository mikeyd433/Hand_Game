package com.handgame.prototype.game

import com.handgame.prototype.input.Calibration
import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.HandMeasure
import com.handgame.prototype.tracking.Lm
import com.handgame.prototype.tracking.OneEuroBank
import com.handgame.prototype.tracking.OneEuroFilter
import com.handgame.prototype.tracking.RawHand

/** Dimensions of the play space in world units (≈ meters). The table top is at y = 0. */
object PlaySpace {
    // Portrait-shaped table so it fills a phone screen held upright.
    const val TABLE_HALF_X = 0.50f
    const val TABLE_HALF_Z = 0.90f
    const val TABLE_THICKNESS = 0.08f
    const val CUBE_HALF = 0.07f

    /** Palm height above the table when the hand is at its calibrated distance. */
    const val HOVER_HEIGHT = 0.10f
    /** Extra height at full lift. */
    const val MAX_LIFT = 0.38f
    /** Rendered palm length (wrist → middle knuckle) at hand scale 1. Scaled by [HandLook.scale]. */
    const val HAND_PALM_LENGTH = 0.15f
    /** Joint radius of the rendered fingers, used to keep fingers above the table. */
    const val FINGER_RADIUS = 0.022f
}

/** The hand's pose in world space for one frame. */
class HandPose {
    val joints = Array(Lm.COUNT) { Vec3.ZERO }
    /** Joints as drawn: [joints] adjusted by [GripSolver] so the hand holds things convincingly. */
    val renderJoints = Array(Lm.COUNT) { Vec3.ZERO }
    var palmCenter = Vec3.ZERO
    var rotation = Quat.IDENTITY
    /** Midpoint of thumb tip and index tip: where a pinch holds things. */
    var pinchPoint = Vec3.ZERO
    /** Under the palm, between the fingers: where a fist holds things. */
    var fistPoint = Vec3.ZERO
    /** 0..1 lift amount from depth. */
    var lift = 0f
    /** Smoothed apparent-size ratio vs. calibration (1 = calibrated distance). */
    var sizeRatio = 1f
    /** 0..1: how close the real hand is to leaving the camera's view. */
    var edgeWarning = 0f
    /** Unsmoothed target position (before the position filter), for latency analysis. */
    var rawTarget = Vec3.ZERO
    /** Which screen edge the hand is nearing: x −1 left / +1 right, y −1 top / +1 bottom. */
    var edgeX = 0
    var edgeY = 0
}

/**
 * Turns raw MediaPipe hands into a smoothed [HandPose] in world space:
 *  - X/Z position: mirrored palm-center image position, mapped through the calibrated range onto
 *    the whole screen, then projected onto the play space with the scene camera ([ViewRig]).
 *  - Height (light depth): apparent hand size vs. calibrated size, clamped and heavily smoothed.
 *    Pushing the hand "into" the screen lowers it toward the table; pulling back lifts.
 *  - Shape: MediaPipe's metric 3D landmarks rotated into game axes and scaled to cartoon size.
 * All three are smoothed with One Euro filters whose parameters come from [GameSettings].
 */
class HandRig {
    val pose = HandPose()

    private val positionFilter = OneEuroBank(3, 0.8f, 2f, 1f)
    private val angleFilter = OneEuroBank(HandSkeleton.SIZE, 1.5f, 0.6f, 1f)
    private val palmFilter = OneEuroBank(6, 1.2f, 0.5f, 1f)
    private val depthFilter = OneEuroFilter(0.5f, 1f, 1f)

    private val rawShape = Array(Lm.COUNT) { Vec3.ZERO }
    private val angles = FloatArray(HandSkeleton.SIZE)
    private val palmBuf = FloatArray(6)
    private val skeleton = Array(Lm.COUNT) { Vec3.ZERO }
    private var lastRightHand: Boolean? = null

    /** The latest unsmoothed shape (scaled to world size), used for gesture detection. */
    val gestureShape = Array(Lm.COUNT) { Vec3.ZERO }

    fun applySettings(s: GameSettings) {
        positionFilter.setParams(s.posMinCutoff, s.posBeta, 1f)
        angleFilter.setParams(s.shapeMinCutoff, s.shapeBeta, 1f)
        palmFilter.setParams(s.shapeMinCutoff * 0.5f, s.shapeBeta, 1f)
        depthFilter.minCutoff = s.depthMinCutoff
        depthFilter.beta = s.depthBeta
    }

    fun reset() {
        positionFilter.reset()
        angleFilter.reset()
        palmFilter.reset()
        depthFilter.reset()
    }

    fun update(
        hand: RawHand,
        frontCamera: Boolean,
        imageWidth: Int,
        imageHeight: Int,
        cal: Calibration,
        settings: GameSettings,
        rightHand: Boolean,
        view: ViewRig,
        t: Double,
    ) {
        // --- Shape -------------------------------------------------------------------------
        HandMeasure.gameShape(hand, frontCamera, rawShape)
        val scale = HandLook.palmLength / cal.worldPalmLength.coerceIn(0.04f, 0.2f)
        for (i in 0 until Lm.COUNT) gestureShape[i] = rawShape[i] * scale

        // The skeleton's conventions flip with handedness, so start its filters fresh on a flip.
        if (lastRightHand != rightHand) { angleFilter.reset(); palmFilter.reset(); lastRightHand = rightHand }

        // Palm orientation: smooth the forward and up directions, then rebuild a clean frame.
        val trackedRot = palmRotation(gestureShape, rightHand)
        val fwdRaw = trackedRot.rotate(Vec3(0f, 0f, -1f)); val upRaw = trackedRot.rotate(Vec3.UP)
        palmBuf[0] = fwdRaw.x; palmBuf[1] = fwdRaw.y; palmBuf[2] = fwdRaw.z
        palmBuf[3] = upRaw.x; palmBuf[4] = upRaw.y; palmBuf[5] = upRaw.z
        palmFilter.filterInPlace(palmBuf, t)
        val fwd = Vec3(palmBuf[0], palmBuf[1], palmBuf[2]).normalized()
        var up = Vec3(palmBuf[3], palmBuf[4], palmBuf[5])
        up = (up - fwd * (up dot fwd)).normalized()
        val palmRot = if (fwd.lengthSq() < 0.5f || up.lengthSq() < 0.5f) trackedRot
        else Quat.fromBasis((fwd cross up).normalized(), up, -fwd)

        // Finger pose: read joint angles (clamped to real hand limits), smooth them, and pose a
        // rigid skeleton with fixed bone lengths, so the hand never stretches or folds through itself.
        HandSkeleton.extract(gestureShape, trackedRot, rightHand, angles)
        angleFilter.filterInPlace(angles, t)
        HandSkeleton.pose(angles, rightHand, HandLook.palmLength, skeleton)
        assistPinch(skeleton)

        // --- Depth → lift ------------------------------------------------------------------
        val size = HandMeasure.apparentSize(hand, imageWidth, imageHeight)
        val ratio = size / cal.baseSize.coerceAtLeast(1e-3f)
        // The screen is a window looking down at the table, so moving the hand deeper into the
        // scene goes down:
        //  - Front camera: the hand faces the screen. Moving toward the screen (hand looks bigger)
        //    goes down, pulling back toward yourself (smaller) lifts.
        //  - Back camera: reaching farther behind the phone (smaller) goes down, bringing the
        //    hand back toward the phone (bigger) lifts.
        // Both use distance ratios (size or 1/size), with a small dead zone so the cube rests at
        // the calibrated distance.
        val liftRange = 0.45f / settings.depthSensitivity.coerceAtLeast(0.1f)
        val closerLifts = !frontCamera xor settings.invertLift
        val distanceRatio = if (closerLifts) ratio else 1f / ratio.coerceAtLeast(1e-3f)
        val rawLift = ((distanceRatio - 1.05f) / liftRange).coerceIn(0f, 1f)
        val lift = depthFilter.filter(rawLift, t).coerceIn(0f, 1f)

        // --- Position ----------------------------------------------------------------------
        val (u, v) = HandMeasure.palmCenterUv(hand, frontCamera)
        val sens = settings.sensitivity.coerceAtLeast(0.1f)
        val range = safeRange(cal, imageWidth, imageHeight)
        val du = u - cal.centerU; val dv = v - cal.centerV
        val nx = (du / (if (du < 0f) range[0] else range[1]) * sens).coerceIn(-1f, 1f)
        val ny = (dv / (if (dv < 0f) range[2] else range[3]) * sens).coerceIn(-1f, 1f)
        updateEdgeWarning(hand, frontCamera)
        // The comfortable range covers the whole screen: map it to screen coordinates, then find
        // the matching spot at hover height. Lifting then goes straight up from there.
        val ndcX = nx * SCREEN_REACH_X
        val ndcY = if (ny < 0f) -ny * SCREEN_REACH_TOP else -ny * SCREEN_REACH_BOTTOM
        val ground = view.screenToPlane(ndcX, ndcY, PlaySpace.HOVER_HEIGHT)
            ?: Vec3(nx * PlaySpace.TABLE_HALF_X, PlaySpace.HOVER_HEIGHT, ny * PlaySpace.TABLE_HALF_Z)
        // Keep the hand from sinking into the table: raise the palm enough for its lowest joint.
        // Done before smoothing so finger motion doesn't make the whole hand bob.
        var lowest = 0f
        for (i in 0 until Lm.COUNT) lowest = minOf(lowest, palmRot.rotate(skeleton[i]).y)
        val overTable = kotlin.math.abs(ground.x) < PlaySpace.TABLE_HALF_X + 0.05f &&
            kotlin.math.abs(ground.z) < PlaySpace.TABLE_HALF_Z + 0.05f
        val floorY = PlaySpace.FINGER_RADIUS * HandLook.scale * 0.8f
        var height = PlaySpace.HOVER_HEIGHT + lift * PlaySpace.MAX_LIFT
        if (overTable) height = maxOf(height, floorY - lowest)
        val target = floatArrayOf(ground.x, height, ground.z)
        pose.rawTarget = Vec3(target[0], target[1], target[2])
        positionFilter.filterInPlace(target, t)
        val center = Vec3(target[0], target[1], target[2])

        // --- Assemble joints ---------------------------------------------------------------
        val joints = pose.joints
        for (i in 0 until Lm.COUNT) joints[i] = center + palmRot.rotate(skeleton[i])

        pose.palmCenter = center
        pose.lift = lift
        pose.sizeRatio = ratio
        pose.rotation = palmRot
        pose.pinchPoint = (joints[Lm.THUMB_TIP] + joints[Lm.INDEX_TIP]) * 0.5f
        val palmDown = pose.rotation.rotate(Vec3(0f, -1f, 0f))
        val toFingers = (joints[Lm.MIDDLE_MCP] - center) * 0.5f
        pose.fistPoint = center + toFingers + palmDown * (0.05f * HandLook.scale)
    }

    /**
     * Movement half-ranges [left, right, up, down] in normalized image units. Starts from the
     * calibrated range (shrunk a little so the screen edges need less reach), then is capped so
     * that reaching any screen edge never needs the hand to leave the camera's view. The hand's
     * extent comes from the calibrated size, so the cap is stable frame to frame.
     */
    private fun safeRange(cal: Calibration, w: Int, h: Int): FloatArray {
        val minSide = minOf(w, h).toFloat()
        val su = minSide / w; val sv = minSide / h
        val extX = cal.baseSize * 1.25f * su
        val extUp = cal.baseSize * 1.6f * sv     // fingers reach above the palm
        val extDown = cal.baseSize * 0.9f * sv   // wrist below
        val m = EDGE_MARGIN
        fun cap(r: Float, room: Float) = minOf(r * RANGE_SCALE, room).coerceAtLeast(MIN_RANGE)
        return floatArrayOf(
            cap(cal.rangeU, cal.centerU - extX - m),
            cap(cal.rangeU, 1f - cal.centerU - extX - m),
            cap(cal.rangeV, cal.centerV - extUp - m),
            cap(cal.rangeV, 1f - cal.centerV - extDown - m),
        )
    }

    /** Flags when any landmark gets close to the camera frame edge (tracking is lost past it). */
    private fun updateEdgeWarning(hand: RawHand, frontCamera: Boolean) {
        var minLeft = 1f; var minRight = 1f; var minTop = 1f; var minBottom = 1f
        for (i in 0 until Lm.COUNT) {
            val u = hand.image[i * 2]; val v = hand.image[i * 2 + 1]
            minLeft = minOf(minLeft, u); minRight = minOf(minRight, 1f - u)
            minTop = minOf(minTop, v); minBottom = minOf(minBottom, 1f - v)
        }
        // Screen left/right: the front camera image is mirrored on screen.
        val screenLeft = if (frontCamera) minRight else minLeft
        val screenRight = if (frontCamera) minLeft else minRight
        val margins = floatArrayOf(screenLeft, screenRight, minTop, minBottom)
        val worst = margins.indices.minByOrNull { margins[it] }!!
        pose.edgeWarning = ((EDGE_WARN - margins[worst]) / EDGE_WARN).coerceIn(0f, 1f)
        pose.edgeX = when (worst) { 0 -> -1; 1 -> 1; else -> 0 }
        pose.edgeY = when (worst) { 2 -> -1; 3 -> 1; else -> 0 }
    }

    /**
     * The skeleton has cartoon proportions, so a real pinch doesn't always bring its thumb and
     * index tips together. As the real fingers close, draw the tips together so pinches read.
     */
    private fun assistPinch(local: Array<Vec3>) {
        val palm = gestureShape[Lm.WRIST].distance(gestureShape[Lm.MIDDLE_MCP]).coerceAtLeast(1e-4f)
        val d = gestureShape[Lm.THUMB_TIP].distance(gestureShape[Lm.INDEX_TIP]) / palm
        val w = ((0.55f - d) / 0.3f).coerceIn(0f, 1f)
        if (w <= 0f) return
        val thumb = local[Lm.THUMB_TIP]; val index = local[Lm.INDEX_TIP]
        val mid = (thumb + index) * 0.5f
        val dir = (thumb - index).normalized().let { if (it.lengthSq() < 0.5f) Vec3(1f, 0f, 0f) else it }
        val r = (HandLook.jointRadius(Lm.THUMB_TIP) + HandLook.jointRadius(Lm.INDEX_TIP)) * 0.5f
        GripSolver.bendChain(local, GripSolver.THUMB_CHAIN, mid + dir * r, w)
        GripSolver.bendChain(local, GripSolver.INDEX_CHAIN, mid - dir * r, w)
    }

    private companion object {
        const val RANGE_SCALE = 1.0f
        const val MIN_RANGE = 0.07f
        const val EDGE_MARGIN = 0.03f
        /** Start warning when a landmark is this close (normalized) to the frame edge. */
        const val EDGE_WARN = 0.07f

        // How far the palm center reaches toward each screen edge (NDC). Slightly inside the edge
        // so the whole hand stays visible; the top leaves room for the fingers.
        const val SCREEN_REACH_X = 0.92f
        const val SCREEN_REACH_TOP = 0.78f
        const val SCREEN_REACH_BOTTOM = 0.92f
    }

    /**
     * Orientation of the palm: forward = wrist → middle knuckle, up = back of the hand. The
     * knuckle line points to the hand's right only once we know which hand it is (index is on
     * the right for a palm-down left hand, pinky for a right hand), so handedness is needed to
     * tell the back of the hand from the palm. This is the frame a held object follows.
     */
    private fun palmRotation(j: Array<Vec3>, rightHand: Boolean): Quat {
        val forward = (j[Lm.MIDDLE_MCP] - j[Lm.WRIST]).normalized()
        val knuckles = j[Lm.INDEX_MCP] - j[Lm.PINKY_MCP]
        val side = (if (rightHand) -knuckles else knuckles).normalized()
        val up = (side cross forward).normalized()
        if (up.lengthSq() < 0.5f) return Quat.IDENTITY
        val right = (forward cross up).normalized()
        // Columns: right (x), up (y), back (z) = -forward.
        return Quat.fromBasis(right, up, -forward)
    }
}
