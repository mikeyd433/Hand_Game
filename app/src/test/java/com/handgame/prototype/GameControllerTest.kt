package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HapticEvent
import com.handgame.prototype.game.Phase
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.tracking.RawHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end: calibration → grab → throw → auto-pause, driven by synthetic tracking. */
class GameControllerTest {
    private val haptics = ArrayList<HapticEvent>()
    private val game = GameController(GameSettings(), null, onHaptic = { haptics += it })

    /** Runs the game at 60 fps with tracking at 30 fps. */
    private fun play(seconds: Float, hand: () -> RawHand?) {
        val frames = (seconds * 60).toInt()
        var sample = SyntheticHand.sample(hand())
        for (f in 0 until frames) {
            if (f % 2 == 0) sample = SyntheticHand.sample(hand())
            game.update(1f / 60f, sample)
        }
    }

    private fun calibrate() {
        assertEquals(Phase.CALIBRATING, game.phase)
        play(2.0f) { SyntheticHand.hand(Gesture.OPEN) }
        game.skipRangeCalibration()
        assertEquals(Phase.RESUMING, game.phase)
        play(1.5f) { SyntheticHand.hand(Gesture.OPEN) }
        assertEquals(Phase.PLAYING, game.phase)
    }

    @Test fun calibrateGrabLiftAndThrow() {
        calibrate()
        assertTrue("cube settled", game.cube.sleeping)

        // Front camera is mirrored: the hand's pinch point sits to the left of the palm, so shift
        // the palm right a little to bring the pinch over the cube.
        val u = 0.5f - 0.04f
        play(0.6f) { SyntheticHand.hand(Gesture.OPEN, u = u) }
        assertTrue("cube should glow when in reach (highlight ${game.highlight})", game.highlight > 0.5f)

        play(0.3f) { SyntheticHand.hand(Gesture.PINCH, u = u) }
        assertTrue("should be holding", game.holding)
        assertEquals(HapticEvent.GRAB, haptics.last())

        // Front camera: pull the hand back from the screen (it looks smaller) → lift.
        play(1.0f) { SyntheticHand.hand(Gesture.PINCH, u = u, scale = 1.6f) }
        assertTrue("cube lifted (y=${game.cube.position.y})", game.cube.position.y > 0.2f)

        // Swing quickly to the side and let go → throw.
        var uu = u
        play(0.25f) { uu += 0.02f; SyntheticHand.hand(Gesture.PINCH, u = uu, scale = 1.6f) }
        play(0.1f) { uu += 0.02f; SyntheticHand.hand(Gesture.OPEN, u = uu, scale = 1.6f) }
        assertFalse(game.holding)
        val vx = game.cube.linearVelocity.x
        // u increasing in the un-mirrored image = moving left on screen for the front camera.
        assertTrue("thrown to the left, vx=$vx", vx < -0.8f)
    }

    @Test fun fistGrabWorks() {
        calibrate()
        play(0.6f) { SyntheticHand.hand(Gesture.OPEN, u = 0.46f) }
        play(0.3f) { SyntheticHand.hand(Gesture.FIST, u = 0.46f) }
        assertTrue(game.holding)
    }

    @Test fun losingTheHandPausesAndReleases() {
        calibrate()
        play(0.6f) { SyntheticHand.hand(Gesture.OPEN, u = 0.46f) }
        play(0.3f) { SyntheticHand.hand(Gesture.PINCH, u = 0.46f) }
        assertTrue(game.holding)

        play(0.2f) { null } // brief dropout: within the grace period
        assertEquals(Phase.PLAYING, game.phase)
        assertTrue(game.holding)

        play(0.5f) { null }
        assertEquals(Phase.PAUSED, game.phase)
        assertFalse(game.holding)
        assertFalse(game.handVisible)

        play(0.1f) { SyntheticHand.hand(Gesture.OPEN) }
        assertEquals(Phase.RESUMING, game.phase)
        play(1.5f) { SyntheticHand.hand(Gesture.OPEN) }
        assertEquals(Phase.PLAYING, game.phase)
    }

    @Test fun closedHandOutOfReachDoesNotGrab() {
        calibrate()
        play(0.6f) { SyntheticHand.hand(Gesture.OPEN, u = 0.2f, v = 0.3f) }
        play(0.3f) { SyntheticHand.hand(Gesture.FIST, u = 0.2f, v = 0.3f) }
        assertFalse(game.holding)
    }

    @Test fun calibrationByPinchingTargets() {
        assertEquals(Phase.CALIBRATING, game.phase)
        play(2.0f) { SyntheticHand.hand(Gesture.OPEN) }
        val ui0 = game.uiSnapshot(false)
        assertEquals(com.handgame.prototype.input.CalibrationSession.Step.TARGETS, ui0.calibrationStep)
        assertTrue("camera-view cursor is reported", ui0.calibrationCursor != null && ui0.calibrationLandmarks != null)
        // Where the pinch point sits relative to the palm, in the player's mirrored view.
        val probe = SyntheticHand.hand(Gesture.PINCH)
        val palm = com.handgame.prototype.tracking.HandMeasure.palmCenterUv(probe, true)
        val pinch = Pair(1f - (probe.image[4 * 2] + probe.image[8 * 2]) / 2, (probe.image[4 * 2 + 1] + probe.image[8 * 2 + 1]) / 2)
        var guard = 0
        while (game.phase == Phase.CALIBRATING && guard++ < 10) {
            val t = game.uiSnapshot(false).calibrationTarget ?: break
            // Raw (un-mirrored) palm u that puts the pinch point on the target.
            val u = 1f - (t.first - (pinch.first - palm.first))
            val v = t.second - (pinch.second - palm.second)
            play(0.3f) { SyntheticHand.hand(Gesture.OPEN, u = u, v = v) }
            play(0.3f) { SyntheticHand.hand(Gesture.PINCH, u = u, v = v) }
        }
        assertEquals("all five targets pinched", Phase.RESUMING, game.phase)
        val c = game.calibration
        assertTrue("play area spans a good part of the view: ${c.rangeU} × ${c.rangeV}", c.rangeU > 0.12f && c.rangeV > 0.1f)
        assertTrue(c.extents != null)
    }

    @Test fun switchingCameraForcesRecalibration() {
        calibrate()
        game.updateSettings(game.settings.copy(frontCamera = false))
        assertEquals(Phase.CALIBRATING, game.phase)
    }
}
