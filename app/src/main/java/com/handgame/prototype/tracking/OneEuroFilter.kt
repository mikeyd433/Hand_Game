package com.handgame.prototype.tracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al. 2012): an adaptive low-pass filter that smooths heavily when the
 * signal is slow (kills jitter at rest) and lightly when it is fast (keeps lag low).
 *
 * @param minCutoff Hz. Lower = smoother at rest, more lag on slow motion.
 * @param beta speed coefficient. Higher = less lag during fast motion.
 * @param dCutoff Hz cutoff for the derivative estimate.
 */
class OneEuroFilter(
    var minCutoff: Float = 1.0f,
    var beta: Float = 0.02f,
    var dCutoff: Float = 1.0f,
) {
    private var hasPrev = false
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = 0.0

    fun reset() {
        hasPrev = false
    }

    /** @param t timestamp in seconds. */
    fun filter(x: Float, t: Double): Float {
        if (!hasPrev) {
            hasPrev = true
            xPrev = x
            dxPrev = 0f
            tPrev = t
            return x
        }
        val dt = (t - tPrev).toFloat()
        if (dt <= 1e-6f) return xPrev
        tPrev = t

        val dx = (x - xPrev) / dt
        val dxHat = lerp(dxPrev, dx, alpha(dCutoff, dt))
        dxPrev = dxHat

        val cutoff = minCutoff + beta * abs(dxHat)
        val xHat = lerp(xPrev, x, alpha(cutoff, dt))
        xPrev = xHat
        return xHat
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** A bank of One Euro filters sharing parameters, for filtering vectors of [size] floats. */
class OneEuroBank(size: Int, minCutoff: Float, beta: Float, dCutoff: Float) {
    private val filters = Array(size) { OneEuroFilter(minCutoff, beta, dCutoff) }

    fun setParams(minCutoff: Float, beta: Float, dCutoff: Float) {
        for (f in filters) {
            f.minCutoff = minCutoff; f.beta = beta; f.dCutoff = dCutoff
        }
    }

    fun reset() = filters.forEach { it.reset() }

    fun filterInPlace(values: FloatArray, t: Double) {
        for (i in values.indices) values[i] = filters[i].filter(values[i], t)
    }
}
