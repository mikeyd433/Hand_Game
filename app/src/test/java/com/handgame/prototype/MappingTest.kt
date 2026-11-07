package com.handgame.prototype

import com.handgame.prototype.input.Calibration
import com.handgame.prototype.input.CalibrationSession
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.render.MeshFactory
import com.handgame.prototype.tracking.HandMeasure
import com.handgame.prototype.tracking.Handedness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingTest {
    private fun det(front: Boolean): Float {
        val x = HandMeasure.toGameAxes(1f, 0f, 0f, front)
        val y = HandMeasure.toGameAxes(0f, 1f, 0f, front)
        val z = HandMeasure.toGameAxes(0f, 0f, 1f, front)
        return x dot (y cross z)
    }

    @Test fun cameraMappingsAreProperRotations() {
        // det +1: the 3D hand is never mirrored, so a left hand stays a left hand.
        assertEquals(1f, det(true), 1e-6f)
        assertEquals(1f, det(false), 1e-6f)
    }

    @Test fun naturalPosesBecomePalmDownReachingForward() {
        // Fingers up in the image (−y) should point into the scene (−z) for both cameras.
        assertVec(Vec3(0f, 0f, -1f), HandMeasure.toGameAxes(0f, -1f, 0f, true))
        assertVec(Vec3(0f, 0f, -1f), HandMeasure.toGameAxes(0f, -1f, 0f, false))
        // Front: palm faces the camera (palm normal −z) → palm points down.
        assertVec(Vec3(0f, -1f, 0f), HandMeasure.toGameAxes(0f, 0f, -1f, true))
        // Back: palm faces away from the player, i.e. away from the camera (+z) → down.
        assertVec(Vec3(0f, -1f, 0f), HandMeasure.toGameAxes(0f, 0f, 1f, false))
    }

    @Test fun frontCameraIsMirroredOnScreen() {
        val h = SyntheticHand.hand(Gesture.OPEN, u = 0.3f)
        assertEquals(0.7f, HandMeasure.palmCenterUv(h, frontCamera = true).first, 1e-4f)
        assertEquals(0.3f, HandMeasure.palmCenterUv(h, frontCamera = false).first, 1e-4f)
    }

    @Test fun mediapipeHandednessIsInvertedForUnmirroredImages() {
        assertEquals(Handedness.RIGHT, HandMeasure.physicalHandedness("Left"))
        assertEquals(Handedness.LEFT, HandMeasure.physicalHandedness("Right"))
    }

    @Test fun apparentSizeGrowsWhenCloser() {
        val far = HandMeasure.apparentSize(SyntheticHand.hand(Gesture.OPEN, scale = 2f), 480, 640)
        val near = HandMeasure.apparentSize(SyntheticHand.hand(Gesture.OPEN, scale = 3f), 480, 640)
        assertEquals(1.5f, near / far, 1e-3f)
    }

    /** Plays the target step: moves the pinch point onto each target and pinches. */
    private fun pinchAllTargets(s: CalibrationSession, offU: Float = 0.06f, offV: Float = -0.12f, reach: (Pair<Float, Float>) -> Pair<Float, Float> = { it }) {
        var guard = 0
        while (s.step == CalibrationSession.Step.TARGETS && guard++ < 20) {
            val t = reach(s.target!!)
            val pu = t.first; val pv = t.second
            // Palm sits at a fixed offset from the pinch point.
            s.update(1 / 30f, true, Gesture.OPEN, pu - offU, pv - offV, 0.2f, 0.09f, pinchU = pu, pinchV = pv)
            s.update(1 / 30f, true, Gesture.PINCH, pu - offU, pv - offV, 0.2f, 0.09f, pinchU = pu, pinchV = pv)
        }
    }

    private fun held(): CalibrationSession {
        val s = CalibrationSession()
        var t = 0
        while (s.step == CalibrationSession.Step.HOLD && t < 200) {
            s.update(1 / 30f, true, Gesture.OPEN, 0.5f, 0.55f, 0.2f, 0.09f, extents = floatArrayOf(0.2f, 0.12f, 0.2f, 0.15f)); t++
        }
        return s
    }

    @Test fun calibrationTargetsSetThePlayArea() {
        val s = held()
        assertEquals(CalibrationSession.Step.TARGETS, s.step)
        assertEquals(CalibrationSession.CENTER_TARGET, s.target)
        pinchAllTargets(s)
        val c = assertNotNullAndGet(s.result)
        assertEquals(0.2f, c.baseSize, 1e-4f)
        // Corner targets sit at the safe-area corners (palm space), so the recorded range spans
        // most of the room the hand has: u in [0.16, 0.904], v in [0.16, 0.88] before the inset.
        assertTrue("range u ${c.rangeU}", c.rangeU in 0.28f..0.36f)
        assertTrue("range v ${c.rangeV}", c.rangeV in 0.26f..0.34f)
        assertNotNullAndGet(c.extents)
    }

    @Test fun pinchingAwayFromTheTargetDoesNothing() {
        val s = held()
        s.update(1 / 30f, true, Gesture.PINCH, 0.2f, 0.2f, 0.2f, 0.09f, pinchU = 0.2f, pinchV = 0.2f)
        assertEquals(0, s.targetIndex)
    }

    @Test fun cantReachPullsTheTargetIn() {
        val s = held()
        pinchAllTargets(s, reach = { it }) .also { } // complete normally for comparison
        val full = assertNotNullAndGet(s.result)
        val s2 = held()
        // First (center) target normally, then pull every corner in once before pinching it.
        var guard = 0
        while (s2.step == CalibrationSession.Step.TARGETS && guard++ < 20) {
            if (s2.targetIndex > 0) s2.pullTargetIn()
            val t = s2.target!!
            s2.update(1 / 30f, true, Gesture.OPEN, t.first - 0.06f, t.second + 0.12f, 0.2f, 0.09f, pinchU = t.first, pinchV = t.second)
            s2.update(1 / 30f, true, Gesture.PINCH, t.first - 0.06f, t.second + 0.12f, 0.2f, 0.09f, pinchU = t.first, pinchV = t.second)
        }
        val pulled = assertNotNullAndGet(s2.result)
        assertTrue(pulled.rangeU < full.rangeU && pulled.rangeV < full.rangeV)
    }

    @Test fun skippingTargetsKeepsDefaults() {
        val s = held()
        assertTrue(s.skipMove())
        assertEquals(Calibration.DEFAULT_RANGE_U, s.result!!.rangeU, 1e-6f)
    }

    @Test fun calibrationHoldRestartsWhenHandMoves() {
        val s = CalibrationSession()
        repeat(30) { s.update(1 / 30f, true, Gesture.OPEN, 0.5f, 0.5f, 0.2f, 0.09f) }
        s.update(1 / 30f, true, Gesture.OPEN, 0.7f, 0.5f, 0.2f, 0.09f) // big jump
        assertEquals(0f, s.progress, 0f)
    }

    @Test fun roundedBoxStaysInsideItsExtents() {
        val m = MeshFactory.roundedBox(0.6f, 0.04f, 0.4f, 0.03f)
        for (i in 0 until m.vertexCount) {
            assertTrue(kotlin.math.abs(m.positions[i * 3]) <= 0.6f + 1e-4f)
            assertTrue(kotlin.math.abs(m.positions[i * 3 + 1]) <= 0.04f + 1e-4f)
            val n = Vec3(m.normals[i * 3], m.normals[i * 3 + 1], m.normals[i * 3 + 2])
            assertEquals(1f, n.length(), 1e-3f)
        }
        assertTrue(m.indices.all { it.toInt() and 0xFFFF < m.vertexCount })
    }

    private fun assertVec(expected: Vec3, actual: Vec3) =
        assertEquals("$expected vs $actual", 0f, expected.distance(actual), 1e-6f)

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
