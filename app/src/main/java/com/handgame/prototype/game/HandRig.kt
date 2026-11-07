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

    private val positionFilter = OneEuroBank(3, 1.2f, 6f, 1f)
    private val shapeFilter = OneEuroBank(Lm.COUNT * 3, 2f, 8f, 1f)
    private val depthFilter = OneEuroFilter(0.5f, 1f, 1f)

    private val rawShape = Array(Lm.COUNT) { Vec3.ZERO }
    private val shapeBuf = FloatArray(Lm.COUNT * 3)

    /** The latest unsmoothed shape (scaled to world size), used for gesture detection. */
    val gestureShape = Array(Lm.COUNT) { Vec3.ZERO }

    fun applySettings(s: GameSettings) {
        positionFilter.setParams(s.posMinCutoff, s.posBeta, 1f)
        shapeFilter.setParams(s.shapeMinCutoff, s.shapeBeta, 1f)
        depthFilter.minCutoff = s.depthMinCutoff
        depthFilter.beta = s.depthBeta
    }

    fun reset() {
        positionFilter.reset()
        shapeFilter.reset()
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
        for (i in 0 until Lm.COUNT) {
            val p = rawShape[i] * scale
            gestureShape[i] = p
            shapeBuf[i * 3] = p.x; shapeBuf[i * 3 + 1] = p.y; shapeBuf[i * 3 + 2] = p.z
        }
        shapeFilter.filterInPlace(shapeBuf, t)

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
        val nx = ((u - cal.centerU) / cal.rangeU * sens).coerceIn(-1f, 1f)
        val ny = ((v - cal.centerV) / cal.rangeV * sens).coerceIn(-1f, 1f)
        // The comfortable range covers the whole screen: map it to screen coordinates, then find
        // the matching spot at hover height. Lifting then goes straight up from there.
        val ndcX = nx * SCREEN_REACH_X
        val ndcY = if (ny < 0f) -ny * SCREEN_REACH_TOP else -ny * SCREEN_REACH_BOTTOM
        val ground = view.screenToPlane(ndcX, ndcY, PlaySpace.HOVER_HEIGHT)
            ?: Vec3(nx * PlaySpace.TABLE_HALF_X, PlaySpace.HOVER_HEIGHT, ny * PlaySpace.TABLE_HALF_Z)
        val target = floatArrayOf(
            ground.x,
            PlaySpace.HOVER_HEIGHT + lift * PlaySpace.MAX_LIFT,
            ground.z,
        )
        positionFilter.filterInPlace(target, t)
        var center = Vec3(target[0], target[1], target[2])

        // --- Assemble joints ---------------------------------------------------------------
        val joints = pose.joints
        var minY = Float.MAX_VALUE
        for (i in 0 until Lm.COUNT) {
            val local = Vec3(shapeBuf[i * 3], shapeBuf[i * 3 + 1], shapeBuf[i * 3 + 2])
            joints[i] = center + local
            if (joints[i].y < minY) minY = joints[i].y
        }
        // Keep the hand from sinking into the table top.
        val overTable = kotlin.math.abs(center.x) < PlaySpace.TABLE_HALF_X + 0.05f &&
            kotlin.math.abs(center.z) < PlaySpace.TABLE_HALF_Z + 0.05f
        val floorY = PlaySpace.FINGER_RADIUS * HandLook.scale * 0.8f
        if (overTable && minY < floorY) {
            val push = Vec3(0f, floorY - minY, 0f)
            center += push
            for (i in 0 until Lm.COUNT) joints[i] = joints[i] + push
        }

        pose.palmCenter = center
        pose.lift = lift
        pose.sizeRatio = ratio
        pose.rotation = palmRotation(joints, rightHand)
        pose.pinchPoint = (joints[Lm.THUMB_TIP] + joints[Lm.INDEX_TIP]) * 0.5f
        val palmDown = pose.rotation.rotate(Vec3(0f, -1f, 0f))
        val toFingers = (joints[Lm.MIDDLE_MCP] - center) * 0.5f
        pose.fistPoint = center + toFingers + palmDown * (0.05f * HandLook.scale)
    }

    private companion object {
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
