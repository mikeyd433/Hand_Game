package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.GripSolver
import com.handgame.prototype.game.HandLook
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.physics.RigidBox
import com.handgame.prototype.tracking.Lm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GripSolverTest {
    private fun cube() = RigidBox(Vec3(0.07f, 0.07f, 0.07f), 0.4f).apply {
        position = Vec3(0f, 0.07f, 0f)
        orientation = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0.4f)
    }

    @Test fun pushOutMovesInsidePointsToTheSurface() {
        val c = cube()
        val p = GripSolver.pushOut(c.position + Vec3(0.01f, 0.05f, 0f), c, 0.02f, null)
        assertEquals(0.02f, c.distanceTo(p), 1e-4f) // exits through the nearest (top) face
        assertTrue(p.y > 0.14f)
    }

    @Test fun pushOutPrefersTheRequestedFace() {
        val c = cube()
        val fwd = c.orientation.rotate(Vec3(0f, 0f, -1f))
        val p = GripSolver.pushOut(c.position + Vec3(0f, 0.05f, 0f), c, 0.02f, fwd)
        val local = c.orientation.conjugate().rotate(p - c.position)
        assertEquals(-0.09f, local.z, 1e-4f)
    }

    @Test fun pinchPutsThumbAndIndexOnOppositeFaces() {
        val c = cube()
        val joints = Array(Lm.COUNT) { c.position } // worst case: everything inside the cube
        val out = Array(Lm.COUNT) { Vec3.ZERO }
        val axis = Vec3(1f, 0f, 0f)
        GripSolver.solve(joints, out, c, Gesture.PINCH, axis, 1f, Vec3(0f, 0f, -1f), Vec3(1f, 0f, 0f))
        val g = c.orientation.rotate(axis)
        assertTrue((out[Lm.THUMB_TIP] - c.position) dot g > 0.07f)
        assertTrue((out[Lm.INDEX_TIP] - c.position) dot g < -0.07f)
        for (i in 0 until Lm.COUNT) {
            assertTrue("joint $i inside cube", c.distanceTo(out[i]) >= HandLook.jointRadius(i) * 0.85f)
        }
    }

    /** Full game: after grabbing, no drawn joint penetrates the cube. */
    @Test fun heldCubeIsNotClippedByTheDrawnHand() {
        for (g in listOf(Gesture.PINCH, Gesture.FIST)) {
            val game = GameController(GameSettings(), null)
            fun play(sec: Float, hand: () -> com.handgame.prototype.tracking.RawHand?) {
                var s = SyntheticHand.sample(hand())
                for (f in 0 until (sec * 60).toInt()) {
                    if (f % 2 == 0) s = SyntheticHand.sample(hand())
                    game.update(1f / 60f, s)
                }
            }
            play(2f) { SyntheticHand.hand(Gesture.OPEN) }
            game.skipRangeCalibration()
            play(1.5f) { SyntheticHand.hand(Gesture.OPEN) }
            play(0.6f) { SyntheticHand.hand(Gesture.OPEN, u = 0.46f) }
            play(1.0f) { SyntheticHand.hand(g, u = 0.46f, scale = 1.8f) } // grab and lift
            assertTrue("$g should hold", game.holding)
            assertTrue(game.gripWeight > 0.95f)
            val j = game.rig.pose.renderJoints
            for (i in 0 until Lm.COUNT) {
                val d = game.cube.distanceTo(j[i])
                assertTrue("$g: joint $i is ${"%.3f".format(d)} from the cube", d >= HandLook.jointRadius(i) * 0.8f)
            }
            if (g == Gesture.PINCH) {
                // Fingertips actually touch the cube rather than hovering away from it.
                assertTrue(game.cube.distanceTo(j[Lm.THUMB_TIP]) < 0.035f)
                assertTrue(game.cube.distanceTo(j[Lm.INDEX_TIP]) < 0.035f)
            }
        }
    }
}
