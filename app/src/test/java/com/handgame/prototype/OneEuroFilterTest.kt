package com.handgame.prototype

import com.handgame.prototype.tracking.OneEuroFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OneEuroFilterTest {
    @Test fun reducesJitterAtRest() {
        val f = OneEuroFilter(minCutoff = 1f, beta = 1f)
        val rnd = Random(1)
        var t = 0.0
        val out = ArrayList<Float>()
        repeat(300) {
            t += 1 / 30.0
            out += f.filter(0.5f + (rnd.nextFloat() - 0.5f) * 0.01f, t)
        }
        val tail = out.takeLast(100)
        val spread = tail.max() - tail.min()
        assertTrue("filtered spread $spread should be well under raw 0.01", spread < 0.004f)
    }

    @Test fun followsFastMovesQuickly() {
        val f = OneEuroFilter(minCutoff = 1f, beta = 5f)
        var t = 0.0
        f.filter(0f, t)
        var y = 0f
        repeat(6) { t += 1 / 30.0; y = f.filter(1f, t) } // 200 ms after a big jump
        assertTrue("should be most of the way there, was $y", y > 0.8f)
    }

    @Test fun firstSamplePassesThrough() {
        assertEquals(3f, OneEuroFilter().filter(3f, 0.0), 0f)
    }
}
