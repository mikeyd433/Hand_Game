package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.Tutorial
import com.handgame.prototype.input.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A simulated player goes through every tutorial lesson. */
class TutorialTest {
    @Test fun simulatedPlayerCompletesTheTutorial() {
        val game = GameController(GameSettings(), null)
        var finishedCalled = false
        game.onTutorialFinished = { finishedCalled = true }
        val p = SimPlayer(game)
        p.calibrate()
        game.startTutorial()
        val t = game.tutorial!!
        assertTrue("critter is there, asleep", game.critter != null && !game.critter!!.awake)
        assertTrue("main cube is put away", !game.mainToy.active)

        val dt = 1 / 60f
        val log = ArrayList<String>()
        var lesson = t.lesson
        var phase = 0; var phaseT = 0f; var lessonT = 0f
        var frames = 0
        var swingRelease = 0.2f
        while (!t.finished && frames < 60 * 240) {
            if (t.lesson != lesson) { log += "${lesson.name} done in %.1fs".format(lessonT); lesson = t.lesson; phase = 0; phaseT = 0f; lessonT = 0f }
            phaseT += dt; lessonT += dt
            fun next() { phase++; phaseT = 0f }
            val prop = t.prop?.body
            val critter = game.critter!!
            val ring = game.target
            p.pointing = false; p.maxSpeed = 0.6f
            if (t.celebrating > 0f) { p.gesture = Gesture.OPEN; p.scale = 2.5f; p.tick(); frames++; continue }
            when (t.lesson) {
                Tutorial.Lesson.PINCH -> when (phase) {
                    0 -> { p.gesture = Gesture.OPEN; if (prop != null && p.aim(prop.position.x, prop.position.z, SimPlayer.Point.PINCH, dt) && phaseT > 0.4f) next() }
                    else -> { p.gesture = Gesture.PINCH; if (phaseT > 0.3f) p.scale = 1.6f }
                }
                Tutorial.Lesson.FIST -> when (phase) {
                    0 -> { p.gesture = Gesture.OPEN; p.scale = 2.5f; if (prop != null && p.aim(prop.position.x, prop.position.z, SimPlayer.Point.PALM, dt) && phaseT > 0.4f) next() }
                    1 -> { p.gesture = Gesture.FIST; if (phaseT > 0.4f) next() }
                    2 -> { p.gesture = Gesture.FIST; p.scale = 2.0f; if (ring != null && p.aim(ring.center.x, ring.center.z + 0.04f, SimPlayer.Point.PALM, dt) && phaseT > 0.4f) next() }
                    else -> { p.scale = 2.5f; p.gesture = if (phaseT > 0.6f) Gesture.OPEN else Gesture.FIST }
                }
                Tutorial.Lesson.POKE -> {
                    val b = game.buttons.firstOrNull()
                    p.gesture = Gesture.OPEN; p.pointing = true
                    if (b != null) {
                        val over = p.aim(b.center.x, b.center.z, SimPlayer.Point.INDEX_TIP, dt)
                        if (over && phaseT > 0.4f) phase = 1
                        p.scale = if (phase == 1) 3.4f else 2.5f // push toward the screen to press
                    }
                }
                Tutorial.Lesson.PET -> {
                    p.gesture = Gesture.OPEN; p.scale = 2.2f
                    val sweep = 0.07f * kotlin.math.sin(lessonT * 4f)
                    p.maxSpeed = 1.2f
                    p.aim(critter.position.x + sweep, critter.position.z, SimPlayer.Point.PALM, dt)
                }
                Tutorial.Lesson.FLICK -> if (prop != null) {
                    // Line up behind the block (opposite the critter), then swipe through toward it.
                    val dx = critter.position.x - prop.position.x; val dz = critter.position.z - prop.position.z
                    val len = kotlin.math.hypot(dx, dz); val ux = dx / len; val uz = dz / len
                    p.gesture = Gesture.OPEN; p.pointing = true
                    when (phase) {
                        0 -> { p.scale = 2.5f; if (p.aim(prop.position.x - ux * 0.12f, prop.position.z - uz * 0.12f, SimPlayer.Point.INDEX_TIP, dt) && phaseT > 0.5f) next() }
                        1 -> { p.scale = 3.2f; p.aim(prop.position.x - ux * 0.12f, prop.position.z - uz * 0.12f, SimPlayer.Point.INDEX_TIP, dt); if (phaseT > 0.6f) next() }
                        2 -> { p.scale = 3.2f; p.maxSpeed = 2.6f; p.aim(prop.position.x + ux * 0.3f, prop.position.z + uz * 0.3f, SimPlayer.Point.INDEX_TIP, dt); if (phaseT > 0.5f) next() }
                        else -> { p.scale = 2.5f; if (phaseT > 2.5f) phase = 0 } // wait, then retry if needed
                    }
                }
                Tutorial.Lesson.THROW -> if (prop != null && ring != null) when (phase) {
                    0 -> { p.gesture = Gesture.OPEN; p.scale = 2.5f; if (p.aim(prop.position.x, prop.position.z, SimPlayer.Point.PINCH, dt) && phaseT > 0.4f) next() }
                    1 -> { p.gesture = Gesture.PINCH; if (game.held != null && phaseT > 0.3f) next() }
                    2 -> { p.gesture = Gesture.PINCH; p.scale = 2.2f; if (p.aim(prop.position.x, 0.35f, SimPlayer.Point.PINCH, dt) && phaseT > 0.3f) next() }
                    // Swing toward the ring and let go once the block passes the middle of the table.
                    3 -> { p.gesture = Gesture.PINCH; p.maxSpeed = 0.3f; p.aim(ring.center.x, ring.center.z, SimPlayer.Point.PINCH, dt); if (prop.position.z < swingRelease) next() }
                    4 -> { p.gesture = Gesture.OPEN; if (phaseT > 0.15f) next() }
                    else -> {
                        // Pull the hand back (lifted) so it doesn't touch the landed block, then retry,
                        // adjusting the release point like a player would.
                        p.gesture = Gesture.OPEN; p.scale = 1.8f
                        p.aim(-0.15f, 0.35f, SimPlayer.Point.PALM, dt)
                        if (phaseT > 3.45f && phaseT - dt <= 3.45f) {
                            // Long (or off the table and respawned at home): let go earlier. Short: later.
                            val respawned = kotlin.math.hypot(prop.position.x - (-0.15f), prop.position.z - 0.35f) < 0.03f
                            swingRelease += if (respawned || prop.position.z < ring.center.z) 0.05f else -0.05f
                        }
                        if (phaseT > 3.5f) { phase = 0; phaseT = 0f }
                    }
                }
                Tutorial.Lesson.DONE -> Unit
            }
            p.tick(dt); frames++
        }
        println("TUT " + log.joinToString(" | "))
        assertTrue("tutorial finished (stuck on ${t.lesson}, ${log})", t.finished)
        assertTrue(finishedCalled)
        assertTrue("friend is awake", game.critter!!.awake)
        game.exitTutorial()
        assertTrue("main cube is back", game.mainToy.active)
        assertEquals(null, game.tutorial)
    }
}
