package com.handgame.prototype

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

    @Test fun calibrationRecordsBaselineAndRange() {
        val s = CalibrationSession()
        var t = 0
        while (s.step == CalibrationSession.Step.HOLD && t < 200) {
            s.update(1 / 30f, true, Gesture.OPEN, 0.5f, 0.55f, 0.2f, 0.09f); t++
        }
        assertEquals(CalibrationSession.Step.MOVE, s.step)
        var done = false
        var i = 0
        while (!done && i < 400) {
            val a = i / 20f
            done = s.update(1 / 30f, true, Gesture.OPEN, 0.5f + 0.2f * kotlin.math.sin(a), 0.55f + 0.15f * kotlin.math.cos(a), 0.2f, 0.09f)
            i++
        }
        val c = assertNotNullAndGet(s.result)
        assertEquals(0.2f, c.baseSize, 1e-4f)
        assertEquals(0.5f, c.centerU, 1e-4f)
        assertTrue("range u ${c.rangeU}", c.rangeU in 0.17f..0.21f)
        assertTrue("range v ${c.rangeV}", c.rangeV in 0.12f..0.16f)
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
