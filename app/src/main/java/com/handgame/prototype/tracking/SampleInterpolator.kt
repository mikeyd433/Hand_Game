package com.handgame.prototype.tracking

/**
 * Evens out hand-tracking timing. Tracking results reach the game unevenly (17, 33 or 50 ms
 * apart even though the camera captures every ~33 ms), so using each result when it arrives makes
 * the hand freeze and then jump. Instead, every result keeps the time its camera frame was
 * *captured*, and the game shows the hand a fixed [delayNs] behind real time, blending between
 * the two results either side of that moment. Motion is then evenly paced at the display rate.
 *
 * If a result is late, the hand is extrapolated briefly (at most [maxExtrapolateNs]) from the last
 * two results, then held.
 */
class SampleInterpolator(
    val delayNs: Long = 55_000_000L,
    private val maxExtrapolateNs: Long = 40_000_000L,
    private val capacity: Int = 8,
) {
    private val buffer = ArrayDeque<TrackingSample>()

    fun reset() = buffer.clear()

    /** Adds a new tracking result. Results without a hand are kept so gaps are respected. */
    fun push(sample: TrackingSample) {
        if (buffer.isNotEmpty() && sample.timestampNs <= buffer.last().timestampNs) return
        buffer.addLast(sample)
        while (buffer.size > capacity) buffer.removeFirst()
    }

    val latest: TrackingSample? get() = buffer.lastOrNull()

    /**
     * The hand as it was at capture time [displayNs] (normally now − [delayNs]), or null if no
     * result with a hand is near that time.
     */
    fun handAt(displayNs: Long): RawHand? {
        val withHand = buffer.filter { it.hand != null }
        if (withHand.isEmpty()) return null
        val first = withHand.first()
        if (displayNs <= first.timestampNs) return first.hand

        for (i in 0 until withHand.size - 1) {
            val a = withHand[i]; val b = withHand[i + 1]
            if (displayNs in a.timestampNs..b.timestampNs) {
                // Don't blend across a real gap in tracking (hand lost in between).
                if (b.timestampNs - a.timestampNs > MAX_BLEND_GAP_NS) return if (displayNs - a.timestampNs < b.timestampNs - displayNs) a.hand else b.hand
                val f = (displayNs - a.timestampNs).toFloat() / (b.timestampNs - a.timestampNs)
                return lerp(a.hand!!, b.hand!!, f)
            }
        }
        // Past the newest result: extrapolate a little from the last two, then hold.
        val last = withHand.last()
        if (withHand.size < 2) return last.hand
        val prev = withHand[withHand.size - 2]
        val span = last.timestampNs - prev.timestampNs
        if (span <= 0 || span > MAX_BLEND_GAP_NS) return last.hand
        val ahead = (displayNs - last.timestampNs).coerceAtMost(maxExtrapolateNs)
        return lerp(prev.hand!!, last.hand!!, 1f + ahead.toFloat() / span)
    }

    private fun lerp(a: RawHand, b: RawHand, f: Float): RawHand {
        val img = FloatArray(a.image.size) { a.image[it] + (b.image[it] - a.image[it]) * f }
        val world = FloatArray(a.world.size) { a.world[it] + (b.world[it] - a.world[it]) * f }
        return RawHand(img, world, if (f < 0.5f) a.mpLabel else b.mpLabel, a.confidence + (b.confidence - a.confidence) * f.coerceIn(0f, 1f))
    }

    private companion object {
        const val MAX_BLEND_GAP_NS = 150_000_000L
    }
}
