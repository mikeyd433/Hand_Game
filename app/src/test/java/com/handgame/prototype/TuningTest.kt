package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HandRig
import com.handgame.prototype.game.PlaySpace
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.tracking.RawHand
import com.handgame.prototype.tuning.Json
import com.handgame.prototype.tuning.Recording
import com.handgame.prototype.tuning.TuningAnalyzer
import com.handgame.prototype.tuning.TuningRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TuningTest {

    /**
     * A simulated player: steers its hand so a chosen point (pinch point or palm) sits over a
     * world position, the way a person watching the screen would. Inverts the game's
     * screen-wide mapping to find where in the camera image the hand must be.
     */
    private class Player(val game: GameController) {
        var u = 0.5f; var v = 0.5f
        fun aim(worldX: Float, worldZ: Float, usePinchPoint: Boolean, dt: Float) {
            val pose = game.rig.pose
            val offset = (if (usePinchPoint) pose.pinchPoint else pose.palmCenter) - pose.palmCenter
            val target = com.handgame.prototype.math.Vec3(worldX - offset.x, PlaySpace.HOVER_HEIGHT, worldZ - offset.z)
            val (ndcX, ndcY) = game.view.project(target)
            val nx = (ndcX / HandRig.SCREEN_REACH_X).coerceIn(-1f, 1f)
            val ny = (if (ndcY > 0f) -ndcY / HandRig.SCREEN_REACH_TOP else -ndcY / HandRig.SCREEN_REACH_BOTTOM).coerceIn(-1f, 1f)
            val box = game.rig.playBox(game.calibration, 480, 640, game.settings.sensitivity)
            val wantU = 1f - (box[0] + nx * box[1]) // front camera: mirrored
            val wantV = box[2] + ny * box[3]
            val maxStep = 0.6f * dt
            u += (wantU - u).coerceIn(-maxStep, maxStep)
            v += (wantV - v).coerceIn(-maxStep, maxStep)
        }
    }

    /** Plays a full guided test with a synthetic hand that follows the prompts and does the tasks. */
    private fun syntheticGuidedRecording(): Recording {
        val game = GameController(GameSettings(), null)
        val rec = TuningRecorder()
        val rnd = Random(11)
        fun noisy(h: RawHand) = RawHand(
            FloatArray(h.image.size) { h.image[it] + (rnd.nextFloat() - 0.5f) * 0.004f },
            FloatArray(h.world.size) { h.world[it] + (rnd.nextFloat() - 0.5f) * 0.003f },
            h.mpLabel, h.confidence,
        )
        // Calibrate first.
        var sample = SyntheticHand.sample(SyntheticHand.hand(Gesture.OPEN))
        repeat(120) { f -> if (f % 2 == 0) sample = SyntheticHand.sample(SyntheticHand.hand(Gesture.OPEN)); game.update(1 / 60f, sample) }
        game.skipRangeCalibration()
        repeat(90) { f -> if (f % 2 == 0) sample = SyntheticHand.sample(SyntheticHand.hand(Gesture.OPEN)); game.update(1 / 60f, sample) }

        val player = Player(game)
        rec.start(guided = true, info = mapOf("device" to "test"), frontCamera = true)
        var frame = 0
        var stepId = ""; var stepTime = 0f; var phase = 0; var phaseTime = 0f
        val dt = 1 / 60f
        while (!rec.finished && frame < 60 * 240) {
            val step = rec.currentStep?.id ?: "ready"
            if (step != stepId) { stepId = step; stepTime = 0f; phase = 0; phaseTime = 0f }
            stepTime += dt; phaseTime += dt
            val t = rec.time.toFloat()
            val cue = rec.currentCue
            val cube = game.cube.position
            val ring = game.target
            fun over(x: Float, z: Float, pinch: Boolean): Boolean {
                player.aim(x, z, pinch, dt)
                val p = if (pinch) game.rig.pose.pinchPoint else game.rig.pose.palmCenter
                return kotlin.math.hypot(p.x - x, p.z - z) < 0.03f
            }
            fun next() { phase++; phaseTime = 0f }
            var gesture = Gesture.OPEN
            var scale = 2.5f
            when (step) {
                "pinch_reps", "fist_reps" -> gesture = if (cue.isEmpty()) Gesture.OPEN else Gesture.valueOf(cue)
                "sweep_slow" -> player.u = 0.5f + 0.15f * sin(t * 2f)
                "sweep_fast" -> player.u = 0.5f + 0.15f * sin(t * 6f)
                "depth" -> scale = if (cue == "LIFT") 1.7f else 2.5f
                "edges" -> { player.u = 0.5f + 0.15f * cos(t * 1.2f); player.v = 0.5f + 0.12f * sin(t * 1.2f) }
                "grab_pinch" -> { if (over(cube.x, cube.z, true) && phaseTime > 0.5f || phase > 0) { phase = 1; gesture = Gesture.PINCH } }
                "lift_hold" -> { gesture = if (game.holding || phase == 0) Gesture.PINCH else Gesture.OPEN; scale = 1.6f }
                "place_ring" -> {
                    gesture = Gesture.PINCH
                    scale = 1.6f
                    if (ring != null) {
                        val there = over(ring.center.x, ring.center.z, true)
                        if (phase == 0 && there && phaseTime > 0.4f) next()
                        if (phase >= 1) { scale = 2.5f; if (phaseTime > 0.8f) gesture = Gesture.OPEN }
                    }
                }
                "fist_carry" -> {
                    when (phase) {
                        0 -> if (over(cube.x, cube.z, false) && phaseTime > 0.5f) next()
                        1 -> { gesture = Gesture.FIST; over(cube.x, cube.z, false); if (phaseTime > 0.4f) next() }
                        2 -> { gesture = Gesture.FIST; scale = 1.8f; if (ring != null && over(ring.center.x, ring.center.z, false) && phaseTime > 0.5f) next() }
                        else -> { gesture = if (phaseTime > 0.8f) Gesture.OPEN else Gesture.FIST; if (ring != null) over(ring.center.x, ring.center.z, false) }
                    }
                }
                "throw" -> {
                    when (phase) {
                        0 -> if (!game.holding) { if (over(cube.x, cube.z, true) && phaseTime > 0.5f) next() } else next()
                        1 -> { gesture = Gesture.PINCH; if (game.holding && phaseTime > 0.4f) next() }
                        2 -> { gesture = Gesture.PINCH; scale = 1.8f; player.u -= 1.2f * dt; if (phaseTime > 0.25f) next() } // swing right on screen
                        else -> { player.u -= 1.2f * dt; gesture = Gesture.OPEN }
                    }
                }
            }
            val hand = SyntheticHand.hand(gesture, u = player.u, v = player.v, scale = scale)
            if (frame % 2 == 0) sample = SyntheticHand.sample(noisy(hand))
            game.update(dt, sample)
            rec.onFrame(dt, sample, game)
            if (frame == 300) rec.mark("jitter")
            frame++
        }
        assertTrue("guided test should finish", rec.finished)
        return rec.stop(game)
    }

    @Test fun jsonRoundTrip() {
        val v = mapOf("a" to 1.5, "b" to listOf(1.0, -0.25), "s" to "x\"y\n", "n" to null, "t" to true)
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals("0.1235", Json.write(0.123456))
        assertEquals("-2", Json.write(-2.0))
    }

    @Test fun guidedSessionRecordsSavesAndAnalyzes() {
        val original = syntheticGuidedRecording()
        val text = original.toJsonLines()
        val rec = Recording.fromJsonLines(text)
        assertEquals(original.events.size, rec.events.size)
        assertTrue("duration ${rec.duration}", rec.duration in 85.0..160.0)

        val report = TuningAnalyzer().analyze(rec)
        println(report.text)
        val m = report.metrics
        assertTrue(m["track_fps"]!! in 25.0..35.0)
        assertTrue("pinch accuracy ${m["pinch_reps_accuracy_pct"]}", m["pinch_reps_accuracy_pct"]!! > 90)
        assertTrue("fist accuracy ${m["fist_reps_accuracy_pct"]}", m["fist_reps_accuracy_pct"]!! > 90)
        assertEquals(0.0, m["pinch_reps_missed"]!!, 0.0)
        assertTrue(m["pinch_on_p90"]!! < m["pinch_open_p10"]!!)
        assertTrue("lifts when asked", m["depth_lift_on_lift"]!! > m["depth_lift_on_lower"]!! + 0.5)
        assertTrue(m.containsKey("still_open_palm_step_mm"))
        assertTrue(m.containsKey("sweep_fast_lag_ms"))
        assertTrue(report.text.contains("marks (1)"))
        // Every object task was completed by the simulated player.
        val outcomes = rec.events.filterIsInstance<Recording.Outcome>()
        assertEquals(listOf("grab_pinch", "lift_hold", "place_ring", "fist_carry", "throw"), outcomes.map { it.step })
        for (o in outcomes) assertTrue("${o.step} should succeed (${o.detail})", o.success)
        assertTrue(m.containsKey("held_shake_mm"))
        assertTrue(report.text.contains("tasks: 5 of 5 done"))
        // File size stays reasonable for upload (uncompressed; gzip shrinks ~4x).
        assertTrue("size ${text.length}", text.length < 3_000_000)
    }

    @Test fun eventsFromBeforeTheRecordingAreDropped() {
        val game = GameController(GameSettings(), null)
        game.respawnCube(); game.respawnCube() // events while nobody records
        val rec = TuningRecorder()
        rec.start(guided = false, info = emptyMap(), frontCamera = true, game = game)
        rec.onFrame(1 / 60f, null, game)
        assertTrue(rec.stop().events.none { it is Recording.GameEvt })
        repeat(500) { game.respawnCube() }
        assertTrue("queue is bounded", game.drainEvents().size <= GameController.MAX_QUEUED_EVENTS)
    }

    @Test fun replayMatchesTheLiveGame() {
        val rec = syntheticGuidedRecording()
        val frames = TuningAnalyzer().replay(rec)
        val live = rec.events.filterIsInstance<Recording.Sample>().filter { it.image != null }
        val replayed = frames.filter { it.present }
        val agree = live.zip(replayed).count { (l, r) -> l.gesture == r.gesture.name }
        assertTrue("replayed gestures should match live (${agree}/${live.size})", agree >= live.size * 0.97)
    }
}
