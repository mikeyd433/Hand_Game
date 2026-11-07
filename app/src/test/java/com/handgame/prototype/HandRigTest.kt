package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HandRig
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.Lm
import com.handgame.prototype.tracking.RawHand
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HandRigTest {
    /** Adds MediaPipe-like noise: ~±2 px landmark jitter and ±3 mm depth wobble. */
    private fun noisy(h: RawHand, rnd: Random): RawHand {
        val img = FloatArray(h.image.size) { h.image[it] + (rnd.nextFloat() - 0.5f) * 0.008f }
        val world = FloatArray(h.world.size) { h.world[it] + (rnd.nextFloat() - 0.5f) * 0.006f }
        return RawHand(img, world, h.mpLabel, h.confidence)
    }

    @Test fun handIsStableAtRest() {
        val rig = HandRig()
        val settings = GameSettings()
        rig.applySettings(settings)
        val rnd = Random(7)
        val base = SyntheticHand.hand(Gesture.OPEN)
        val palms = ArrayList<Vec3>(); val tips = ArrayList<Vec3>(); val lifts = ArrayList<Float>()
        var t = 0.0
        repeat(300) {
            t += 1 / 30.0
            rig.update(noisy(base, rnd), true, 480, 640, GameController.DEFAULT_CALIBRATION, settings, true, t)
            palms += rig.pose.palmCenter; tips += rig.pose.joints[Lm.INDEX_TIP]; lifts += rig.pose.lift
        }
        fun spread(v: List<Vec3>) = v.takeLast(150).let { l ->
            val c = l.reduce { a, b -> a + b } / l.size.toFloat()
            l.maxOf { it.distance(c) }
        }
        val palm = spread(palms); val tip = spread(tips)
        val lift = lifts.takeLast(150).let { it.max() - it.min() }
        println("rest jitter: palm=%.4f tip=%.4f lift=%.4f".format(palm, tip, lift))
        // Cube is 0.14 wide; a few millimetres of wobble at this scale is invisible on a phone.
        assertTrue("palm jitter $palm", palm < 0.006f)
        assertTrue("fingertip jitter $tip", tip < 0.008f)
        assertTrue("lift jitter $lift", lift < 0.02f)
    }
}
