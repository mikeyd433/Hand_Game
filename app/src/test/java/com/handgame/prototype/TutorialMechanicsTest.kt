package com.handgame.prototype

import com.handgame.prototype.game.Critter
import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HandState
import com.handgame.prototype.game.PushButton
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.input.GestureDetector
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.HandMeasure
import com.handgame.prototype.tracking.Lm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialMechanicsTest {
    private fun shape(g: Gesture, pointing: Boolean = false) = Array(Lm.COUNT) { Vec3.ZERO }.also {
        HandMeasure.gameShape(SyntheticHand.hand(g, pointing = pointing), true, it)
    }

    @Test fun pointingIsDetectedWithoutBeingAGrab() {
        val d = GestureDetector()
        repeat(4) { d.update(shape(Gesture.OPEN, pointing = true)) }
        assertTrue(d.pointing)
        assertEquals("pointing isn't a grab", Gesture.OPEN, d.state)
        repeat(4) { d.update(shape(Gesture.OPEN)) }
        assertFalse(d.pointing)
        repeat(4) { d.update(shape(Gesture.FIST)) }
        assertFalse(d.pointing)
    }

    @Test fun buttonPressesOnceUntilReleased() {
        val b = PushButton(Vec3(0f, 0f, 0f), color = floatArrayOf(1f, 0f, 0f))
        var presses = 0
        b.onPressed = { presses++ }
        val down = listOf(Vec3(0f, b.top, 0f) to 0.02f)
        repeat(30) { b.update(1 / 60f, down) }
        assertTrue(b.pressed)
        repeat(30) { b.update(1 / 60f, down) }
        assertEquals(1, presses)
        repeat(60) { b.update(1 / 60f, emptyList()) }
        assertFalse(b.pressed)
        repeat(30) { b.update(1 / 60f, down) }
        assertEquals(2, presses)
        // A fingertip beside the button does nothing.
        val c = PushButton(Vec3(0f, 0f, 0f), color = floatArrayOf(1f, 0f, 0f))
        repeat(30) { c.update(1 / 60f, listOf(Vec3(0.2f, 0f, 0f) to 0.02f)) }
        assertFalse(c.pressed)
    }

    @Test fun critterWakesSquishesAndEnjoysPetting() {
        val c = Critter(Vec3(0f, 0f, 0f))
        val idle = HandState(true, Vec3(0.3f, 0.3f, 0.3f), Vec3.ZERO, true, emptyList())
        repeat(60) { c.update(1 / 60f, idle) }
        assertFalse(c.awake)
        assertTrue("asleep = eyes closed", c.eyesClosed > 0.9f)
        // Poke wakes it and squishes it.
        c.update(1 / 60f, idle.copy(tips = listOf(Vec3(0f, Critter.HEIGHT * 0.6f, 0f) to 0.02f)))
        var minSquash = 0f
        repeat(20) { c.update(1 / 60f, idle); minSquash = minOf(minSquash, c.squash) }
        assertTrue(c.awake)
        assertTrue("squished (${minSquash})", minSquash < -0.05f)
        // Petting: open hand moving just over its head.
        val before = c.petSeconds
        var x = -0.08f
        repeat(120) {
            x += 0.002f
            c.update(1 / 60f, idle.copy(palm = Vec3(x, c.top + 0.05f, 0f), palmVelocity = Vec3(0.12f, 0f, 0f)))
        }
        assertTrue("petted ${c.petSeconds - before}s", c.petSeconds - before > 1.5f)
        assertTrue("hearts appear", c.hearts.isNotEmpty())
        // A still hand isn't petting.
        val s = c.petSeconds
        repeat(60) { c.update(1 / 60f, idle.copy(palm = Vec3(0f, c.top + 0.05f, 0f))) }
        assertEquals(s, c.petSeconds, 1e-4f)
    }

    @Test fun fastFingertipFlicksAToy() {
        val game = GameController(GameSettings(), null)
        val p = SimPlayer(game)
        p.calibrate()
        game.setToyActive(game.mainToy, false)
        val toy = game.addToy(Vec3(0.035f, 0.035f, 0.035f), floatArrayOf(1f, 1f, 1f), Vec3(0f, 0.035f, 0.1f), "t")
        p.pointing = true
        // Park the fingertip just to the left of the toy, then swipe right quickly.
        repeat(90) { p.aim(-0.12f, 0.1f, SimPlayer.Point.INDEX_TIP, 1 / 60f); p.tick() }
        p.scale = 3.2f // dip so the fingertip is at toy height
        repeat(40) { p.aim(-0.12f, 0.1f, SimPlayer.Point.INDEX_TIP, 1 / 60f); p.tick() }
        p.maxSpeed = 3f
        var flicked = false
        repeat(40) {
            p.aim(0.25f, 0.1f, SimPlayer.Point.INDEX_TIP, 1 / 60f); p.tick()
            if (toy.body.linearVelocity.x > 0.8f) flicked = true
        }
        assertTrue("the toy flew off to the right (pos ${toy.body.position})", flicked || toy.body.position.x > 0.15f)
    }
}
