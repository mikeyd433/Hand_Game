package com.handgame.prototype.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Player-specific baseline captured during calibration.
 *
 * @param baseSize apparent hand size (fraction of the image's short side) at a comfortable distance.
 * @param centerU,centerV mirrored palm-center position that maps to the middle of the table.
 * @param rangeU,rangeV half-extents of the comfortable movement range, in normalized image units.
 * @param worldPalmLength metric palm length from MediaPipe, used to scale the on-screen hand.
 */
data class Calibration(
    val baseSize: Float,
    val centerU: Float,
    val centerV: Float,
    val rangeU: Float,
    val rangeV: Float,
    val worldPalmLength: Float,
    /**
     * Measured reach of the open hand from its palm center [left, right, up, down] (normalized
     * image units, player's view), or null for calibrations made before this was recorded.
     */
    val extents: List<Float>? = null,
) {
    companion object {
        const val DEFAULT_RANGE_U = 0.24f
        const val DEFAULT_RANGE_V = 0.20f
        const val MIN_RANGE = 0.10f
        /** Hold only counts when every landmark is at least this far from the frame edge. */
        const val MIN_EDGE_MARGIN = 0.06f
        /** Apparent size above which we suggest holding the hand a bit farther away. */
        const val TOO_CLOSE_SIZE = 0.30f
        /** Apparent size below which tracking gets less precise; suggest coming a bit closer. */
        const val TOO_FAR_SIZE = 0.12f
        /**
         * Share of the hand's measured reach that must stay inside the frame (MediaPipe keeps
         * tracking a hand with fingertips slightly out of view). Shared with the play-area mapping.
         */
        const val EXTENT_SLACK = 0.8f
        const val EDGE_MARGIN = 0f
    }
}

/**
 * Two-step calibration, shown over the live camera view:
 *  1. HOLD: the player holds an open hand still, inside the camera's view, for [holdSeconds].
 *     Records the hand's size, position and reach.
 *  2. TARGETS: targets pop up one at a time and the player pinches each one with their pinch point
 *     (between thumb and index tip). The first is in the middle; the other four sit at the corners
 *     of the largest play area that keeps the whole hand in view. The palm's position at each
 *     corner pinch becomes the edge of the play area. A target that's uncomfortable to reach can be
 *     pulled inward with [pullTargetIn]. The step can be skipped, keeping a default range.
 */
class CalibrationSession(
    private val holdSeconds: Float = 1.5f,
) {
    enum class Step { HOLD, TARGETS, DONE }

    /** Why the hold isn't progressing, for on-screen hints. */
    enum class Hint { NONE, NEAR_EDGE, TOO_CLOSE, TOO_FAR }

    var step = Step.HOLD
        private set
    /** 0..1 progress of the current step. */
    var progress = 0f
        private set
    var result: Calibration? = null
        private set
    var hint = Hint.NONE
        private set

    /** Current target for the pinch point, in the player's (mirrored) image coordinates. */
    var target: Pair<Float, Float>? = null
        private set
    var targetIndex = 0
        private set
    val targetCount get() = TARGET_COUNT

    private val sumExt = FloatArray(4)
    private var holdTime = 0f
    private var sumSize = 0f; private var sumU = 0f; private var sumV = 0f; private var sumPalm = 0f
    private var samples = 0
    private var lastU = Float.NaN; private var lastV = Float.NaN

    private val targets = ArrayList<Pair<Float, Float>>()
    private val pinchedPalms = ArrayList<Pair<Float, Float>>()
    private var lastGesture = Gesture.OPEN
    private var armed = true

    /**
     * Feed one frame. [u], [v] are the palm center and [pinchU], [pinchV] the pinch point, both
     * in the player's (mirrored) image coordinates.
     * @return true when calibration has just finished.
     */
    fun update(
        dt: Float,
        handPresent: Boolean,
        gesture: Gesture,
        u: Float,
        v: Float,
        size: Float,
        worldPalm: Float,
        extents: FloatArray? = null,
        edgeMargin: Float = 1f,
        pinchU: Float = u,
        pinchV: Float = v,
    ): Boolean {
        when (step) {
            Step.HOLD -> {
                val still = !lastU.isNaN() && abs(u - lastU) + abs(v - lastV) < 0.02f
                lastU = u; lastV = v
                // The whole hand must sit comfortably inside the camera's view, or the play range
                // would end up cramped on one side.
                val roomy = edgeMargin >= Calibration.MIN_EDGE_MARGIN
                hint = when {
                    !handPresent -> Hint.NONE
                    !roomy -> Hint.NEAR_EDGE
                    size > Calibration.TOO_CLOSE_SIZE -> Hint.TOO_CLOSE
                    size < Calibration.TOO_FAR_SIZE -> Hint.TOO_FAR
                    else -> Hint.NONE
                }
                if (!handPresent || gesture != Gesture.OPEN || !still || !roomy) {
                    resetHold()
                    if (handPresent && gesture == Gesture.OPEN) { lastU = u; lastV = v }
                    return false
                }
                holdTime += dt
                sumSize += size; sumU += u; sumV += v; sumPalm += worldPalm; samples++
                if (extents != null) for (k in 0 until 4) sumExt[k] += extents[k]
                progress = (holdTime / holdSeconds).coerceIn(0f, 1f)
                if (holdTime >= holdSeconds && samples > 0) startTargets()
            }
            Step.TARGETS -> {
                hint = Hint.NONE
                val pinchedNow = gesture == Gesture.PINCH && lastGesture != Gesture.PINCH
                if (gesture == Gesture.OPEN) armed = true
                lastGesture = gesture
                val t = target
                if (handPresent && t != null && pinchedNow && armed &&
                    hypot(pinchU - t.first, pinchV - t.second) < TARGET_RADIUS
                ) {
                    armed = false
                    pinchedPalms += u to v
                    if (targetIndex == 0) placeCornerTargets(pinchU - u, pinchV - v)
                    targetIndex++
                    progress = targetIndex.toFloat() / TARGET_COUNT
                    if (targetIndex >= TARGET_COUNT) {
                        finish(usePinches = true)
                        return true
                    }
                    target = targets[targetIndex]
                }
            }
            Step.DONE -> Unit
        }
        return false
    }

    private fun startTargets() {
        step = Step.TARGETS
        progress = 0f
        targets.clear(); pinchedPalms.clear()
        targetIndex = 0
        targets += CENTER_TARGET
        target = CENTER_TARGET
        armed = true
    }

    /**
     * Corners of the largest play area that keeps the whole hand in view (palm positions), shifted
     * by where the player's pinch point sits relative to their palm, then pulled in a little.
     */
    private fun placeCornerTargets(offU: Float, offV: Float) {
        val ext = avgExtents() ?: listOf(0.2f, 0.2f, 0.2f, 0.15f)
        val k = Calibration.EXTENT_SLACK
        val uLo = ext[0] * k + Calibration.EDGE_MARGIN; val uHi = 1f - ext[1] * k - Calibration.EDGE_MARGIN
        val vLo = ext[2] * k + Calibration.EDGE_MARGIN; val vHi = 1f - ext[3] * k - Calibration.EDGE_MARGIN
        val cu = (uLo + uHi) / 2; val cv = (vLo + vHi) / 2
        fun inset(lo: Float, hi: Float, c: Float, side: Int) = c + ((if (side < 0) lo else hi) - c) * CORNER_INSET
        for ((su, sv) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
            val pu = if (uHi > uLo) inset(uLo, uHi, cu, su) else cu
            val pv = if (vHi > vLo) inset(vLo, vHi, cv, sv) else cv
            targets += (pu + offU).coerceIn(0.05f, 0.95f) to (pv + offV).coerceIn(0.05f, 0.95f)
        }
    }

    /** Moves the current target a third of the way toward the middle (if it's hard to reach). */
    fun pullTargetIn() {
        val t = target ?: return
        if (step != Step.TARGETS || targetIndex == 0) return
        val c = targets[0]
        val moved = (t.first + (c.first - t.first) * 0.33f) to (t.second + (c.second - t.second) * 0.33f)
        targets[targetIndex] = moved
        target = moved
    }

    /** Skips the target step and keeps the default range. */
    fun skipMove(): Boolean {
        if (step != Step.TARGETS) return false
        finish(usePinches = false)
        return true
    }

    private fun avgExtents() = if (samples > 0 && sumExt.all { it > 0f }) sumExt.map { it / samples } else null

    private fun resetHold() {
        holdTime = 0f; progress = 0f
        sumSize = 0f; sumU = 0f; sumV = 0f; sumPalm = 0f; samples = 0
        sumExt.fill(0f)
        lastU = Float.NaN; lastV = Float.NaN
    }

    private fun finish(usePinches: Boolean) {
        var cu = sumU / samples
        var cv = sumV / samples
        var ru = Calibration.DEFAULT_RANGE_U
        var rv = Calibration.DEFAULT_RANGE_V
        val corners = pinchedPalms.drop(1)
        if (usePinches && corners.size == 4) {
            val minU = corners.minOf { it.first }; val maxU = corners.maxOf { it.first }
            val minV = corners.minOf { it.second }; val maxV = corners.maxOf { it.second }
            cu = (minU + maxU) / 2; cv = (minV + maxV) / 2
            ru = ((maxU - minU) / 2).coerceAtLeast(Calibration.MIN_RANGE)
            rv = ((maxV - minV) / 2).coerceAtLeast(Calibration.MIN_RANGE)
        }
        result = Calibration(
            baseSize = sumSize / samples,
            centerU = cu,
            centerV = cv,
            rangeU = ru,
            rangeV = rv,
            worldPalmLength = sumPalm / samples,
            extents = avgExtents(),
        )
        step = Step.DONE
        target = null
        progress = 1f
    }

    companion object {
        const val TARGET_COUNT = 5
        /** How close (normalized image units) the pinch point must be to a target. */
        const val TARGET_RADIUS = 0.075f
        val CENTER_TARGET = 0.5f to 0.45f
        /** Corner targets sit this far out toward the safe-area corners (1 = right at the limit). */
        const val CORNER_INSET = 0.9f
    }
}
