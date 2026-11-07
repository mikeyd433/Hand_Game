package com.handgame.prototype.input

import kotlin.math.abs
import kotlin.math.max

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
    }
}

/**
 * Two-step calibration:
 *  1. HOLD: the player holds an open hand still for [holdSeconds]. Records size and center.
 *  2. MOVE: the player sweeps their hand around the area they want to use for [moveSeconds].
 *     Records the comfortable range. Can be skipped, which keeps a sensible default range.
 */
class CalibrationSession(
    private val holdSeconds: Float = 1.5f,
    private val moveSeconds: Float = 4.0f,
) {
    enum class Step { HOLD, MOVE, DONE }

    /** Why the hold isn't progressing, for on-screen hints. */
    enum class Hint { NONE, NEAR_EDGE, TOO_CLOSE }

    var step = Step.HOLD
        private set
    /** 0..1 progress of the current step. */
    var progress = 0f
        private set
    var result: Calibration? = null
        private set
    var hint = Hint.NONE
        private set
    private val sumExt = FloatArray(4)

    private var holdTime = 0f
    private var sumSize = 0f; private var sumU = 0f; private var sumV = 0f; private var sumPalm = 0f
    private var samples = 0
    private var lastU = Float.NaN; private var lastV = Float.NaN

    private var moveTime = 0f
    private val us = ArrayList<Float>()
    private val vs = ArrayList<Float>()

    /**
     * Feed one frame. [handPresent] false (or a non-open gesture during HOLD) restarts the hold.
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
                if (holdTime >= holdSeconds && samples > 0) {
                    step = Step.MOVE
                    progress = 0f
                }
            }
            Step.MOVE -> {
                if (handPresent) {
                    moveTime += dt
                    us.add(u); vs.add(v)
                }
                progress = (moveTime / moveSeconds).coerceIn(0f, 1f)
                if (moveTime >= moveSeconds) {
                    finish(useRecordedRange = true)
                    return true
                }
            }
            Step.DONE -> Unit
        }
        return false
    }

    /** Skips the movement-range step and keeps the default range. */
    fun skipMove(): Boolean {
        if (step != Step.MOVE) return false
        finish(useRecordedRange = false)
        return true
    }

    private fun resetHold() {
        holdTime = 0f; progress = 0f
        sumSize = 0f; sumU = 0f; sumV = 0f; sumPalm = 0f; samples = 0
        sumExt.fill(0f)
        lastU = Float.NaN; lastV = Float.NaN
    }

    private fun finish(useRecordedRange: Boolean) {
        val cu = sumU / samples
        val cv = sumV / samples
        var ru = Calibration.DEFAULT_RANGE_U
        var rv = Calibration.DEFAULT_RANGE_V
        if (useRecordedRange && us.size >= 10) {
            // Use robust percentiles of the sweep so a single wild frame doesn't stretch the range.
            ru = max(abs(percentile(us, 0.05f) - cu), abs(percentile(us, 0.95f) - cu))
            rv = max(abs(percentile(vs, 0.05f) - cv), abs(percentile(vs, 0.95f) - cv))
            ru = ru.coerceAtLeast(Calibration.MIN_RANGE)
            rv = rv.coerceAtLeast(Calibration.MIN_RANGE)
        }
        result = Calibration(
            baseSize = sumSize / samples,
            centerU = cu,
            centerV = cv,
            rangeU = ru,
            rangeV = rv,
            worldPalmLength = sumPalm / samples,
            extents = if (sumExt.all { it > 0f }) sumExt.map { it / samples } else null,
        )
        step = Step.DONE
        progress = 1f
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
