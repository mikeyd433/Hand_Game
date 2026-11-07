package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HandRig
import com.handgame.prototype.game.PlaySpace
import com.handgame.prototype.game.ViewRig
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewAndDepthTest {
    private fun liftAfter(front: Boolean, scale: Float, invert: Boolean = false): Float {
        val rig = HandRig()
        val s = GameSettings(frontCamera = front, invertLift = invert)
        rig.applySettings(s)
        var t = 0.0
        repeat(90) {
            t += 1 / 30.0
            // Default calibration: base size matches scale ≈ 2.5 for the synthetic hand.
            val cal = GameController.DEFAULT_CALIBRATION.copy(
                baseSize = com.handgame.prototype.tracking.HandMeasure.apparentSize(SyntheticHand.hand(Gesture.OPEN), 480, 640),
            )
            rig.update(SyntheticHand.hand(Gesture.OPEN, scale = scale), front, 480, 640, cal, s, true, ViewRig(), t)
        }
        return rig.pose.lift
    }

    @Test fun frontCameraLiftsWhenPullingBackFromTheScreen() {
        assertTrue(liftAfter(front = true, scale = 1.55f) > 0.8f)  // farther → up
        assertEquals(0f, liftAfter(front = true, scale = 3.8f), 1e-3f) // closer → stays down
        assertEquals("small drift doesn't lift", 0f, liftAfter(front = true, scale = 2.25f), 1e-3f)
    }

    @Test fun backCameraLiftsWhenBringingTheHandBackToThePhone() {
        assertTrue(liftAfter(front = false, scale = 3.8f) > 0.8f)
        assertEquals(0f, liftAfter(front = false, scale = 1.7f), 1e-3f)
    }

    @Test fun invertSettingFlipsLift() {
        assertTrue(liftAfter(front = true, scale = 3.8f, invert = true) > 0.8f)
    }

    @Test fun tableFitsInsideTheScreenForCommonAspects() {
        for (aspect in listOf(9f / 16f, 9f / 19.5f, 9f / 21f, 3f / 4f)) {
            val v = ViewRig().apply { fit(aspect) }
            for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) {
                val (x, y) = v.project(Vec3(sx * PlaySpace.TABLE_HALF_X, 0f, sz * PlaySpace.TABLE_HALF_Z))
                assertTrue("aspect $aspect corner x=$x", x in -1f..1f)
                assertTrue("aspect $aspect corner y=$y", y in -1f..1f)
            }
        }
    }

    @Test fun screenEdgesMapBackToTheSameScreenPoints() {
        val v = ViewRig().apply { fit(9f / 19.5f) }
        for ((nx, ny) in listOf(-0.9f to 0.8f, 0.9f to -0.9f, 0f to 0f)) {
            val p = v.screenToPlane(nx, ny, PlaySpace.HOVER_HEIGHT)!!
            assertEquals(PlaySpace.HOVER_HEIGHT, p.y, 1e-4f)
            val (x, y) = v.project(p)
            assertEquals(nx, x, 1e-3f); assertEquals(ny, y, 1e-3f)
        }
    }

    @Test fun handReachesAcrossTheWholeScreen() {
        val rig = HandRig()
        val s = GameSettings()
        val view = ViewRig().apply { fit(9f / 19.5f) }
        rig.applySettings(s)
        fun ndcAt(u: Float, v: Float): Pair<Float, Float> {
            rig.reset()
            rig.update(SyntheticHand.hand(Gesture.OPEN, u = u, v = v), true, 480, 640,
                GameController.DEFAULT_CALIBRATION, s, true, view, 0.0)
            val c = rig.pose.palmCenter
            return view.project(Vec3(c.x, PlaySpace.HOVER_HEIGHT, c.z))
        }
        // Mirrored front camera: small u = right side of the screen.
        assertTrue(ndcAt(0.1f, 0.5f).first > 0.85f)
        assertTrue(ndcAt(0.9f, 0.5f).first < -0.85f)
        assertTrue(ndcAt(0.5f, 0.1f).second > 0.7f)
        assertTrue(ndcAt(0.5f, 0.9f).second < -0.85f)
    }
}
