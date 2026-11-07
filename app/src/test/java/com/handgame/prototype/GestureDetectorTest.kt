package com.handgame.prototype

import com.handgame.prototype.input.Gesture
import com.handgame.prototype.input.GestureDetector
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.HandMeasure
import com.handgame.prototype.tracking.Lm
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureDetectorTest {
    private fun joints(g: Gesture): Array<Vec3> {
        val shape = Array(Lm.COUNT) { Vec3.ZERO }
        HandMeasure.gameShape(SyntheticHand.hand(g), frontCamera = true, out = shape)
        return shape
    }

    private fun settle(d: GestureDetector, g: Gesture): Gesture {
        var s = d.state
        repeat(4) { s = d.update(joints(g)) }
        return s
    }

    @Test fun recognizesEachGesture() {
        val d = GestureDetector()
        assertEquals(Gesture.OPEN, settle(d, Gesture.OPEN))
        assertEquals(Gesture.PINCH, settle(d, Gesture.PINCH))
        assertEquals(Gesture.OPEN, settle(d, Gesture.OPEN))
        assertEquals(Gesture.FIST, settle(d, Gesture.FIST))
        assertEquals(Gesture.OPEN, settle(d, Gesture.OPEN))
    }

    @Test fun singleNoisyFrameDoesNotTrigger() {
        val d = GestureDetector()
        settle(d, Gesture.OPEN)
        d.update(joints(Gesture.FIST)) // one bad frame
        assertEquals(Gesture.OPEN, d.update(joints(Gesture.OPEN)))
    }

    @Test fun hysteresisHoldsPinchInsideTheBand() {
        val d = GestureDetector()
        settle(d, Gesture.PINCH)
        // Open the pinch to a distance between enter (0.32) and exit (0.50): stays pinched.
        val j = joints(Gesture.PINCH)
        val palm = j[Lm.WRIST].distance(j[Lm.MIDDLE_MCP])
        val dir = (j[Lm.INDEX_TIP] - j[Lm.THUMB_TIP]).normalized()
        j[Lm.INDEX_TIP] = j[Lm.THUMB_TIP] + dir * (0.42f * palm)
        repeat(5) { assertEquals(Gesture.PINCH, d.update(j)) }
    }
}

/** Pinches MediaPipe gets wrong in depth (seen in a real recording) are caught via the image. */
class ImagePinchTest {
    @Test fun imageOverlapRescuesADepthConfusedPinch() {
        val d = GestureDetector()
        val j = Array(Lm.COUNT) { Vec3.ZERO }
        HandMeasure.gameShape(SyntheticHand.hand(Gesture.PINCH), frontCamera = true, out = j)
        // Push the index tip away in depth only: 3D says ~0.45 apart, the image says touching.
        val palm = j[Lm.WRIST].distance(j[Lm.MIDDLE_MCP])
        j[Lm.INDEX_TIP] = j[Lm.THUMB_TIP] + Vec3(0f, 0.45f * palm, 0f)
        repeat(3) { d.update(j) }
        assertEquals("3D alone misses it", Gesture.OPEN, d.state)
        repeat(3) { d.update(j, pinch2d = 0.1f) }
        assertEquals(Gesture.PINCH, d.state)
    }

    @Test fun imageOverlapAloneDoesNotTriggerWhenFingersAreFarApart() {
        val d = GestureDetector()
        val j = Array(Lm.COUNT) { Vec3.ZERO }
        HandMeasure.gameShape(SyntheticHand.hand(Gesture.OPEN), frontCamera = true, out = j)
        repeat(5) { d.update(j, pinch2d = 0.05f) } // thumb crossing in front of the index in view
        assertEquals(Gesture.OPEN, d.state)
    }
}
