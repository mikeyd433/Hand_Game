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
    const val TABLE_HALF_X = 0.60f
    const val TABLE_HALF_Z = 0.40f
    const val TABLE_THICKNESS = 0.08f
    const val CUBE_HALF = 0.07f

    /** Palm height above the table when the hand is at its calibrated distance. */
    const val HOVER_HEIGHT = 0.10f
    /** Extra height at full lift. */
    const val MAX_LIFT = 0.38f
    /** Rendered palm length (wrist → middle knuckle). Chunky cartoon proportions. */
    const val HAND_PALM_LENGTH = 0.15f
    /** Joint radius of the rendered fingers, used to keep fingers above the table. */
    const val FINGER_RADIUS = 0.022f
}

/** The hand's pose in world space for one frame. */
class HandPose {
    val joints = Array(Lm.COUNT) { Vec3.ZERO }
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
 *  - X/Z position: mirrored palm-center image position mapped through the calibrated range
 *    onto the table.
 *  - Height (light depth): apparent hand size vs. calibrated size, clamped and heavily smoothed.
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
        t: Double,
    ) {
        // --- Shape -------------------------------------------------------------------------
        HandMeasure.gameShape(hand, frontCamera, rawShape)
        val scale = PlaySpace.HAND_PALM_LENGTH / cal.worldPalmLength.coerceIn(0.04f, 0.2f)
        for (i in 0 until Lm.COUNT) {
            val p = rawShape[i] * scale
            gestureShape[i] = p
            shapeBuf[i * 3] = p.x; shapeBuf[i * 3 + 1] = p.y; shapeBuf[i * 3 + 2] = p.z
        }
        shapeFilter.filterInPlace(shapeBuf, t)

        // --- Depth → lift ------------------------------------------------------------------
        val size = HandMeasure.apparentSize(hand, imageWidth, imageHeight)
        val ratio = size / cal.baseSize.coerceAtLeast(1e-3f)
        // Closer to the camera (bigger) lifts. A small dead zone keeps the cube on the table at rest.
        val liftRange = 0.45f / settings.depthSensitivity.coerceAtLeast(0.1f)
        val rawLift = ((ratio - 1.05f) / liftRange).coerceIn(0f, 1f)
        val lift = depthFilter.filter(rawLift, t).coerceIn(0f, 1f)

        // --- Position ----------------------------------------------------------------------
        val (u, v) = HandMeasure.palmCenterUv(hand, frontCamera)
        val sens = settings.sensitivity.coerceAtLeast(0.1f)
        val nx = ((u - cal.centerU) / cal.rangeU * sens).coerceIn(-1.2f, 1.2f)
        val nz = ((v - cal.centerV) / cal.rangeV * sens).coerceIn(-1.2f, 1.2f)
        val target = floatArrayOf(
            nx * PlaySpace.TABLE_HALF_X,
            PlaySpace.HOVER_HEIGHT + lift * PlaySpace.MAX_LIFT,
            nz * PlaySpace.TABLE_HALF_Z,
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
        val floorY = PlaySpace.FINGER_RADIUS * 0.8f
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
        pose.fistPoint = center + toFingers + palmDown * 0.05f
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
