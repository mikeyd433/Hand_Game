package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
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

    /** Plays a full guided test with a synthetic hand that follows the prompts. */
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

        rec.start(guided = true, info = mapOf("device" to "test"), frontCamera = true)
        var frame = 0
        while (!rec.finished && frame < 60 * 120) {
            val step = rec.currentStep?.id ?: "ready"
            val t = rec.time.toFloat()
            val cue = rec.currentCue
            val hand = when (step) {
                "pinch_reps", "fist_reps" -> SyntheticHand.hand(if (cue.isEmpty()) Gesture.OPEN else Gesture.valueOf(cue))
                "sweep_slow" -> SyntheticHand.hand(Gesture.OPEN, u = 0.5f + 0.15f * sin(t * 2f))
                "sweep_fast" -> SyntheticHand.hand(Gesture.OPEN, u = 0.5f + 0.15f * sin(t * 6f))
                "depth" -> SyntheticHand.hand(Gesture.OPEN, scale = if (cue == "LIFT") 1.7f else 2.5f)
                "edges" -> SyntheticHand.hand(Gesture.OPEN, u = 0.5f + 0.15f * cos(t * 1.2f), v = 0.5f + 0.12f * sin(t * 1.2f))
                else -> SyntheticHand.hand(Gesture.OPEN)
            }
            if (frame % 2 == 0) sample = SyntheticHand.sample(noisy(hand))
            game.update(1 / 60f, sample)
            rec.onFrame(1 / 60f, sample, game)
            if (frame == 300) rec.mark("jitter")
            frame++
        }
        assertTrue("guided test should finish", rec.finished)
        return rec.stop()
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
        assertTrue("about 80 s", rec.duration in 75.0..90.0)

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
        // File size stays reasonable for upload (uncompressed; gzip shrinks ~4x).
        assertTrue("size ${text.length}", text.length < 3_000_000)
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
