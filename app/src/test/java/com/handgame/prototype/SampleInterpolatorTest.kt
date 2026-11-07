package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.tracking.RawHand
import com.handgame.prototype.tracking.SampleInterpolator
import com.handgame.prototype.tracking.TrackingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SampleInterpolatorTest {
    private fun sample(tMs: Long, u: Float?) = TrackingSample(
        tMs * 1_000_000, u?.let { SyntheticHand.hand(Gesture.OPEN, u = it) }, 480, 640, true, 10f,
    )

    private fun palmU(h: RawHand) = h.image[0]

    @Test fun blendsBetweenCaptureTimes() {
        val it = SampleInterpolator()
        it.push(sample(0, 0.4f)); it.push(sample(100, 0.6f))
        val h = it.handAt(50_000_000)!!
        assertEquals((palmU(SyntheticHand.hand(Gesture.OPEN, u = 0.4f)) + palmU(SyntheticHand.hand(Gesture.OPEN, u = 0.6f))) / 2, palmU(h), 1e-4f)
    }

    @Test fun extrapolatesBrieflyThenHolds() {
        val it = SampleInterpolator(maxExtrapolateNs = 20_000_000)
        it.push(sample(0, 0.4f)); it.push(sample(33, 0.5f))
        val far = it.handAt(500_000_000)!!
        val step = palmU(SyntheticHand.hand(Gesture.OPEN, u = 0.5f)) - palmU(SyntheticHand.hand(Gesture.OPEN, u = 0.4f))
        assertEquals(palmU(SyntheticHand.hand(Gesture.OPEN, u = 0.5f)) + step * 20f / 33f, palmU(far), 1e-3f)
    }

    @Test fun noHandMeansNull() {
        val it = SampleInterpolator()
        it.push(sample(0, null))
        assertNull(it.handAt(0))
    }

    /** Unevenly arriving results (17/33/50 ms apart) still give evenly paced motion. */
    @Test fun gameMotionIsEvenWithJitteryArrival() {
        val game = GameController(GameSettings(), null)
        val rnd = Random(2)
        // Steady hand sweep captured every 33 ms; each result arrives 20–55 ms later.
        val captures = (0 until 400).map { i -> i * 33_333_333L }
        val arrivals = captures.map { it + 20_000_000L + rnd.nextLong(35_000_000L) }
        fun uAt(ns: Long) = 0.5f + 0.15f * kotlin.math.sin(ns / 1e9f * 2f)
        var latest: TrackingSample? = null
        var next = 0
        val xs = ArrayList<Float>()
        var now = 0L
        while (now < captures.last()) {
            while (next < captures.size && arrivals[next] <= now) {
                latest = TrackingSample(captures[next], SyntheticHand.hand(Gesture.OPEN, u = uAt(captures[next])), 480, 640, true, 10f)
                next++
            }
            game.update(1f / 60f, latest, now)
            if (now > 4_000_000_000L) xs += game.rig.pose.palmCenter.x
            now += 16_666_667L
        }
        val v = xs.zipWithNext { a, b -> b - a }
        val stutter = v.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()
        val motion = v.map { kotlin.math.abs(it) }.average()
        assertTrue("stutter ${"%.5f".format(stutter)} should be small vs motion ${"%.5f".format(motion)}", stutter < motion * 0.15)
    }
}
