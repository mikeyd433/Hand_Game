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

class PlayBoxTest {
    /** Regression: a box that exactly fills the safe region used to throw from coerceIn. */
    @Test fun neverThrowsAndStaysInsideTheFrame() {
        val rig = HandRig()
        val rnd = kotlin.random.Random(5)
        repeat(20000) {
            val cal = GameController.DEFAULT_CALIBRATION.copy(
                baseSize = 0.1f + rnd.nextFloat() * 0.35f,
                centerU = rnd.nextFloat(), centerV = rnd.nextFloat(),
                rangeU = rnd.nextFloat() * 0.5f, rangeV = rnd.nextFloat() * 0.5f,
                extents = if (rnd.nextBoolean()) null else List(4) { rnd.nextFloat() * 0.5f },
            )
            val b = rig.playBox(cal, 480, 640, 0.3f + rnd.nextFloat() * 1.7f)
            assertTrue(b.all { it.isFinite() } && b[1] > 0f && b[3] > 0f)
        }
        // The real case from the recording.
        val real = GameController.DEFAULT_CALIBRATION.copy(baseSize = 0.3548f, centerU = 0.5255f, centerV = 0.5408f,
            rangeU = 0.122f, rangeV = 0.146f, worldPalmLength = 0.0998f, extents = listOf(0.4472f, 0.1382f, 0.3545f, 0.2054f))
        rig.playBox(real, 480, 640, 0.5f)
    }
}
