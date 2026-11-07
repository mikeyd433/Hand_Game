package com.handgame.prototype.tuning

import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HandRig
import com.handgame.prototype.game.HandednessSetting
import com.handgame.prototype.game.ViewRig
import com.handgame.prototype.input.Calibration
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.input.GestureDetector
import com.handgame.prototype.input.GestureThresholds
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.HandMeasure
import com.handgame.prototype.tracking.Handedness
import com.handgame.prototype.tracking.HandednessVoter
import com.handgame.prototype.tracking.Lm
import com.handgame.prototype.tracking.RawHand
import java.util.Locale
import kotlin.math.abs

/**
 * Replays a [Recording] through the real hand pipeline ([HandRig], [GestureDetector]) and measures
 * how it behaved: jitter, gesture accuracy and delay, the actual pinch/fist measurements per cue,
 * movement lag, depth range and tracking dropouts.
 *
 * The device and the offline tests run this same code, so a pasted summary and an offline replay
 * always agree. Pass [settingsOverride] / [thresholds] to try new tuning against old recordings.
 */
class TuningAnalyzer(
    private val settingsOverride: ((GameSettings) -> GameSettings)? = null,
    private val thresholds: GestureThresholds = GestureThresholds(),
    private val calibrationOverride: ((Calibration) -> Calibration)? = null,
) {
    /** One replayed tracking sample. */
    class Frame(
        val t: Double,
        val present: Boolean,
        val palm: Vec3,
        val tip: Vec3,
        val rawTarget: Vec3,
        val gesture: Gesture,
        val pinch: Float,
        val curl: Float,
        val sizeRatio: Float,
        val lift: Float,
        val edge: Float,
        val confidence: Float,
        val inferenceMs: Float,
    )

    class Interval(val step: String, val expect: String, val t0: Double, val t1: Double)

    class Report(val frames: List<Frame>, val intervals: List<Interval>, val text: String, val metrics: Map<String, Double>)

    fun analyze(rec: Recording): Report {
        val frames = replay(rec)
        val intervals = intervals(rec)
        val m = LinkedHashMap<String, Double>()
        val out = StringBuilder()
        val settings = rec.events.filterIsInstance<Recording.Config>().lastOrNull()?.settings ?: GameSettings()
        val s = settingsOverride?.invoke(settings) ?: settings

        out.line("Hand tuning summary v${Recording.FORMAT_VERSION} — ${rec.header["device"] ?: "device"}, " +
            "${if (s.frontCamera) "front" else "back"} camera, ${rec.header["created"] ?: ""}")
        out.line("mode %s, %.0f s".format(Locale.US, if (rec.guided) "guided" else "free play", rec.duration))
        out.line(
            "settings: pos %.2f/%.2f finger %.2f/%.2f depth %.2f/%.2f sens %.2f lift %.2f%s hand %.2f".format(
                Locale.US, s.posMinCutoff, s.posBeta, s.shapeMinCutoff, s.shapeBeta, s.depthMinCutoff, s.depthBeta,
                s.sensitivity, s.depthSensitivity, if (s.invertLift) " (inverted)" else "", s.handScale,
            ),
        )
        rec.events.filterIsInstance<Recording.Config>().lastOrNull()?.calibration?.let { c ->
            out.line("calibration: size %.3f center %.2f,%.2f range %.2f×%.2f palm %.3f m".format(
                Locale.US, c.baseSize, c.centerU, c.centerV, c.rangeU, c.rangeV, c.worldPalmLength))
        }

        // --- Tracking health -------------------------------------------------------------------
        val present = frames.filter { it.present }
        val dur = rec.duration.coerceAtLeast(1e-3)
        val fps = frames.size / dur
        val gaps = dropouts(frames)
        m["track_fps"] = fps
        m["hand_seen_pct"] = 100.0 * present.size / frames.size.coerceAtLeast(1)
        m["inference_ms"] = present.map { it.inferenceMs.toDouble() }.averageOr0()
        m["dropouts"] = gaps.size.toDouble()
        out.line("tracking: %.0f fps, hand seen %.0f%%, inference %.0f ms, confidence %.2f, dropouts %d (%.1f s)".format(
            Locale.US, fps, m["hand_seen_pct"], m["inference_ms"], present.map { it.confidence.toDouble() }.averageOr0(),
            gaps.size, gaps.sumOf { it.second - it.first }))

        // --- Per guided step -------------------------------------------------------------------
        for (step in listOf("still_open", "still_relaxed")) {
            val fs = inStep(frames, intervals, step, settle = 1.0).filter { it.present }
            if (fs.size < 10) continue
            val palmStep = meanStep(fs.map { it.palm }) * 1000; val tipStep = meanStep(fs.map { it.tip }) * 1000
            val spread = spread(fs.map { it.palm }) * 1000
            m["${step}_palm_step_mm"] = palmStep; m["${step}_tip_step_mm"] = tipStep; m["${step}_palm_spread_mm"] = spread
            out.line("%s: palm %.1f mm/frame (drift %.1f mm), fingertip %.1f mm/frame".format(Locale.US, step, palmStep, spread, tipStep))
        }
        gestureStep(out, m, frames, intervals, "pinch_reps", Gesture.PINCH, "pinch dist") { it.pinch }
        gestureStep(out, m, frames, intervals, "fist_reps", Gesture.FIST, "max curl") { it.curl }
        for (step in listOf("sweep_slow", "sweep_fast")) {
            val fs = inStep(frames, intervals, step, settle = 0.5).filter { it.present }
            if (fs.size < 20) continue
            val lag = lagMs(fs)
            val speed = maxSpeed(fs)
            m["${step}_lag_ms"] = lag; m["${step}_max_speed"] = speed
            out.line("%s: smoothing lag %.0f ms, top hand speed %.1f m/s".format(Locale.US, step, lag, speed))
        }
        run {
            val fs = inStep(frames, intervals, "depth", settle = 0.0).filter { it.present }
            if (fs.size >= 20) {
                val ratios = fs.map { it.sizeRatio.toDouble() }.sorted()
                val up = cueFrames(frames, intervals, "depth", "LIFT", 1.0).map { it.lift.toDouble() }.averageOr0()
                val down = cueFrames(frames, intervals, "depth", "LOWER", 1.0).map { it.lift.toDouble() }.averageOr0()
                m["depth_size_p5"] = pct(ratios, 0.05); m["depth_size_p95"] = pct(ratios, 0.95)
                m["depth_lift_on_lift"] = up; m["depth_lift_on_lower"] = down
                out.line("depth: hand size ×%.2f–×%.2f of calibrated; avg lift %.2f when asked to lift, %.2f when asked to lower".format(
                    Locale.US, m["depth_size_p5"], m["depth_size_p95"], up, down))
            }
        }
        run {
            val iv = intervals.filter { it.step == "edges" }
            if (iv.isNotEmpty()) {
                val fs = frames.filter { f -> iv.any { f.t >= it.t0 && f.t < it.t1 } }
                val warns = episodes(fs) { it.edge > 0.6f }
                val lost = gaps.count { g -> iv.any { g.first >= it.t0 && g.first < it.t1 } }
                m["edges_warnings"] = warns.toDouble(); m["edges_dropouts"] = lost.toDouble()
                out.line("edges: %d edge warnings, %d dropouts while tracing the screen edges".format(Locale.US, warns, lost))
            }
        }

        // --- Free play overview ----------------------------------------------------------------
        if (!rec.guided && present.size > 30) {
            val grabs = frames.zipWithNext().count { (a, b) -> a.gesture == Gesture.OPEN && b.gesture != Gesture.OPEN }
            val stillish = present.zipWithNext().map { (a, b) -> a.palm.distance(b.palm) * 1000 }.sorted()
            out.line("free play: %d grab gestures, palm motion median %.1f mm/frame".format(Locale.US, grabs, pct(stillish.map { it.toDouble() }, 0.5)))
        }

        // --- Marks -----------------------------------------------------------------------------
        val marks = rec.events.filterIsInstance<Recording.Mark>()
        if (marks.isNotEmpty()) {
            out.line("marks (%d):".format(Locale.US, marks.size))
            for (mk in marks) {
                val near = frames.filter { abs(it.t - mk.t) < 1.0 }
                val f = near.minByOrNull { abs(it.t - mk.t) }
                val jitter = meanStep(near.filter { it.present }.map { it.palm }) * 1000
                val gestures = near.map { it.gesture }.distinct().joinToString("/")
                val lostNear = gaps.any { it.first < mk.t + 1 && it.second > mk.t - 1 }
                out.line("  %.1fs %s: gesture %s (pinch %.2f curl %.2f), palm %.1f mm/frame, lift %.2f%s".format(
                    Locale.US, mk.t, mk.kind, gestures, f?.pinch ?: 0f, f?.curl ?: 0f, jitter, f?.lift ?: 0f,
                    if (lostNear) ", tracking dropped" else ""))
            }
        }
        return Report(frames, intervals, out.toString().trimEnd(), m)
    }

    /** Runs every sample through the hand pipeline exactly as the game does. */
    fun replay(rec: Recording): List<Frame> {
        val rig = HandRig()
        val gestures = GestureDetector(thresholds)
        val voter = HandednessVoter()
        val view = ViewRig()
        var settings = GameSettings()
        var calibration: Calibration? = null
        val frames = ArrayList<Frame>()
        for (e in rec.events) {
            when (e) {
                is Recording.Config -> {
                    settings = settingsOverride?.invoke(e.settings) ?: e.settings
                    calibration = calibrationOverride?.invoke(e.calibration) ?: e.calibration
                    rig.applySettings(settings)
                    com.handgame.prototype.game.HandLook.scale = settings.handScale
                }
                is Recording.Sample -> {
                    val cal = calibration ?: continue
                    if (e.image == null || e.world == null) {
                        frames += Frame(e.t, false, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, gestures.state, 0f, 0f, 0f, 0f, 0f, 0f, e.inferenceMs)
                        continue
                    }
                    val hand = RawHand(e.image, e.world, e.label, e.confidence)
                    val voted = voter.add(HandMeasure.physicalHandedness(e.label))
                    val right = when (settings.handedness) {
                        HandednessSetting.LEFT -> false
                        HandednessSetting.RIGHT -> true
                        HandednessSetting.AUTO -> voted != Handedness.LEFT
                    }
                    rig.update(hand, e.frontCamera, e.imageWidth, e.imageHeight, cal, settings, right, view, e.t)
                    val g = gestures.update(rig.gestureShape, rig.pinch2d)
                    val p = rig.pose
                    frames += Frame(
                        e.t, true, p.palmCenter, p.joints[Lm.INDEX_TIP], p.rawTarget, g,
                        gestures.metrics.pinchDistance, gestures.metrics.maxCurl, p.sizeRatio, p.lift, p.edgeWarning,
                        e.confidence, e.inferenceMs,
                    )
                }
                else -> Unit
            }
        }
        return frames
    }

    fun intervals(rec: Recording): List<Interval> {
        val cues = rec.events.filterIsInstance<Recording.Cue>()
        return cues.zipWithNext().map { (a, b) -> Interval(a.step, a.expect, a.t, b.t) }
    }

    // ------------------------------------------------------------------------------------------

    private fun gestureStep(
        out: StringBuilder, m: MutableMap<String, Double>, frames: List<Frame>, intervals: List<Interval>,
        step: String, target: Gesture, metricName: String, metric: (Frame) -> Float,
    ) {
        val ivs = intervals.filter { it.step == step && it.expect.isNotEmpty() }
        if (ivs.isEmpty()) return
        var hits = 0; var total = 0; var falseTriggers = 0; var missed = 0
        val latencies = ArrayList<Double>()
        for (iv in ivs) {
            val expected = Gesture.valueOf(iv.expect)
            val fs = frames.filter { it.t >= iv.t0 && it.t < iv.t1 && it.present }
            val settled = fs.filter { it.t >= iv.t0 + SETTLE }
            hits += settled.count { it.gesture == expected }; total += settled.size
            val first = fs.firstOrNull { it.gesture == expected }
            if (first == null) missed++ else latencies += (first.t - iv.t0) * 1000
            // Entering a gesture other than the cued one after the settle time counts as a false trigger.
            falseTriggers += settled.zipWithNext().count { (a, b) -> a.gesture != b.gesture && b.gesture != expected }
        }
        val acc = 100.0 * hits / total.coerceAtLeast(1)
        val lat = latencies.averageOr0()
        m["${step}_accuracy_pct"] = acc; m["${step}_latency_ms"] = lat
        m["${step}_false"] = falseTriggers.toDouble(); m["${step}_missed"] = missed.toDouble()
        out.line("%s: accuracy %.0f%%, detect delay %.0f ms (from cue, includes reaction), false %d, missed %d of %d".format(
            Locale.US, step, acc, lat, falseTriggers, missed, ivs.count { it.expect == target.name }))

        val onTarget = cueFrames(frames, intervals, step, target.name, SETTLE).map { metric(it).toDouble() }.sorted()
        val onOpen = cueFrames(frames, intervals, step, "OPEN", SETTLE).map { metric(it).toDouble() }.sorted()
        if (onTarget.size > 5 && onOpen.size > 5) {
            val key = step.removeSuffix("_reps")
            m["${key}_on_p10"] = pct(onTarget, 0.1); m["${key}_on_p50"] = pct(onTarget, 0.5); m["${key}_on_p90"] = pct(onTarget, 0.9)
            m["${key}_open_p10"] = pct(onOpen, 0.1); m["${key}_open_p50"] = pct(onOpen, 0.5); m["${key}_open_p90"] = pct(onOpen, 0.9)
            val (enter, exit) = if (target == Gesture.PINCH) thresholds.pinchEnter to thresholds.pinchExit
            else thresholds.fistEnter to thresholds.fistExit
            out.line("  %s when %s p10/50/90 %.2f/%.2f/%.2f, when OPEN %.2f/%.2f/%.2f (thresholds enter %.2f exit %.2f)".format(
                Locale.US, metricName, target.name, m["${key}_on_p10"], m["${key}_on_p50"], m["${key}_on_p90"],
                m["${key}_open_p10"], m["${key}_open_p50"], m["${key}_open_p90"], enter, exit))
        }
    }

    private fun cueFrames(frames: List<Frame>, intervals: List<Interval>, step: String, expect: String, settle: Double) =
        intervals.filter { it.step == step && it.expect == expect }
            .flatMap { iv -> frames.filter { it.present && it.t >= iv.t0 + settle && it.t < iv.t1 } }

    private fun inStep(frames: List<Frame>, intervals: List<Interval>, step: String, settle: Double): List<Frame> {
        val ivs = intervals.filter { it.step == step }
        if (ivs.isEmpty()) return emptyList()
        val t0 = ivs.minOf { it.t0 } + settle; val t1 = ivs.maxOf { it.t1 }
        return frames.filter { it.t >= t0 && it.t < t1 }
    }

    private fun dropouts(frames: List<Frame>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var lastSeen: Double? = null
        for (f in frames) {
            if (f.present) {
                if (lastSeen != null && f.t - lastSeen > 0.4) out += lastSeen to f.t
                lastSeen = f.t
            }
        }
        return out
    }

    private fun episodes(frames: List<Frame>, cond: (Frame) -> Boolean): Int =
        frames.zipWithNext().count { (a, b) -> !cond(a) && cond(b) } + if (frames.firstOrNull()?.let(cond) == true) 1 else 0

    private fun meanStep(v: List<Vec3>) = if (v.size < 2) 0.0 else v.zipWithNext { a, b -> a.distance(b).toDouble() }.average()

    private fun spread(v: List<Vec3>): Double {
        if (v.isEmpty()) return 0.0
        val c = v.reduce { a, b -> a + b } / v.size.toFloat()
        return v.maxOf { it.distance(c).toDouble() }
    }

    /** How far the smoothed position trails the raw target, by best-matching time shift. */
    private fun lagMs(fs: List<Frame>): Double {
        val dt = (fs.last().t - fs.first().t) / (fs.size - 1).coerceAtLeast(1)
        var best = 0; var bestErr = Double.MAX_VALUE
        for (k in 0..12) {
            var err = 0.0; var n = 0
            for (i in k until fs.size) { err += fs[i].palm.distance(fs[i - k].rawTarget); n++ }
            if (n > 0 && err / n < bestErr) { bestErr = err / n; best = k }
        }
        return best * dt * 1000
    }

    private fun maxSpeed(fs: List<Frame>): Double = fs.zipWithNext { a, b ->
        val dt = b.t - a.t
        if (dt > 1e-3) a.palm.distance(b.palm) / dt else 0.0
    }.maxOrNull() ?: 0.0

    private fun pct(sorted: List<Double>, p: Double) =
        if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    private fun List<Double>.averageOr0() = if (isEmpty()) 0.0 else average()

    private fun StringBuilder.line(s: String) { append(s).append('\n') }

    private companion object {
        /** Ignore this long after each cue (reaction time) when scoring. */
        const val SETTLE = 0.6
    }
}
