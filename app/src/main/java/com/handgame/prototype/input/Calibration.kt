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
) {
    companion object {
        const val DEFAULT_RANGE_U = 0.24f
        const val DEFAULT_RANGE_V = 0.20f
        const val MIN_RANGE = 0.10f
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

    var step = Step.HOLD
        private set
    /** 0..1 progress of the current step. */
    var progress = 0f
        private set
    var result: Calibration? = null
        private set

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
    ): Boolean {
        when (step) {
            Step.HOLD -> {
                val still = !lastU.isNaN() && abs(u - lastU) + abs(v - lastV) < 0.02f
                lastU = u; lastV = v
                if (!handPresent || gesture != Gesture.OPEN || !still) {
                    resetHold()
                    if (handPresent && gesture == Gesture.OPEN) { lastU = u; lastV = v }
                    return false
                }
                holdTime += dt
                sumSize += size; sumU += u; sumV += v; sumPalm += worldPalm; samples++
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
