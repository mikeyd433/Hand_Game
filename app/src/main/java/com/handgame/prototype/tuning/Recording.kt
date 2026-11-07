package com.handgame.prototype.tuning

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HandednessSetting
import com.handgame.prototype.input.Calibration
import com.handgame.prototype.tracking.TrackingSample

/**
 * A tuning recording: raw hand-tracking samples (landmark numbers only, never camera images),
 * what the game did with them, the guided-test cues the player was following, and any issue
 * marks. Stored as gzipped JSON lines so it can be replayed through the real game code offline.
 */
class Recording(val header: Map<String, Any?>, val events: List<Event>) {

    sealed class Event { abstract val t: Double }

    class Sample(
        override val t: Double,
        val image: FloatArray?,
        val world: FloatArray?,
        val label: String?,
        val confidence: Float,
        val inferenceMs: Float,
        val imageWidth: Int,
        val imageHeight: Int,
        val frontCamera: Boolean,
        // What the live game did, for cross-checking replays.
        val gesture: String,
        val holding: Boolean,
        val phase: String,
        val lift: Float,
    ) : Event()

    /** A guided-test prompt: from [t], the player should be doing [expect] as part of [step]. */
    class Cue(override val t: Double, val step: String, val expect: String) : Event()

    /** The player tapped "something felt wrong" with a category. */
    class Mark(override val t: Double, val kind: String) : Event()

    /** Settings and calibration in effect from [t] on. */
    class Config(override val t: Double, val settings: GameSettings, val calibration: Calibration) : Event()

    val guided get() = header["guided"] == true
    val duration get() = events.lastOrNull()?.t ?: 0.0

    fun toJsonLines(): String {
        val sb = StringBuilder()
        Json.write(sb, header); sb.append('\n')
        for (e in events) {
            val m: Map<String, Any?> = when (e) {
                is Sample -> linkedMapOf(
                    "k" to "s", "t" to e.t, "img" to e.image, "w" to e.world, "lab" to e.label,
                    "c" to e.confidence, "inf" to e.inferenceMs, "iw" to e.imageWidth, "ih" to e.imageHeight,
                    "front" to e.frontCamera, "g" to e.gesture, "hold" to e.holding, "ph" to e.phase, "lift" to e.lift,
                )
                is Cue -> linkedMapOf("k" to "cue", "t" to e.t, "step" to e.step, "expect" to e.expect)
                is Mark -> linkedMapOf("k" to "mark", "t" to e.t, "kind" to e.kind)
                is Config -> linkedMapOf("k" to "cfg", "t" to e.t, "settings" to settingsToMap(e.settings), "cal" to calToMap(e.calibration))
            }
            Json.write(sb, m); sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun fromJsonLines(text: String): Recording {
            val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
            val header = Json.parse(lines.next()).obj()
            val events = ArrayList<Event>()
            for (line in lines) {
                val m = Json.parse(line).obj()
                val t = m.num("t")
                events += when (m.str("k")) {
                    "s" -> Sample(
                        t, m.floats("img"), m.floats("w"), m.str("lab"), m.num("c").toFloat(), m.num("inf").toFloat(),
                        m.num("iw").toInt(), m.num("ih").toInt(), m["front"] == true,
                        m.str("g") ?: "OPEN", m["hold"] == true, m.str("ph") ?: "", m.num("lift").toFloat(),
                    )
                    "cue" -> Cue(t, m.str("step") ?: "", m.str("expect") ?: "")
                    "mark" -> Mark(t, m.str("kind") ?: "other")
                    "cfg" -> Config(t, settingsFromMap(m["settings"].obj()), calFromMap(m["cal"].obj()))
                    else -> continue
                }
            }
            return Recording(header, events)
        }

        fun settingsToMap(s: GameSettings): Map<String, Any?> = linkedMapOf(
            "frontCamera" to s.frontCamera, "handedness" to s.handedness.name, "sensitivity" to s.sensitivity,
            "depthSensitivity" to s.depthSensitivity, "invertLift" to s.invertLift, "handScale" to s.handScale,
            "posMinCutoff" to s.posMinCutoff, "posBeta" to s.posBeta, "shapeMinCutoff" to s.shapeMinCutoff,
            "shapeBeta" to s.shapeBeta, "depthMinCutoff" to s.depthMinCutoff, "depthBeta" to s.depthBeta,
        )

        fun settingsFromMap(m: Map<String, Any?>): GameSettings {
            val d = GameSettings()
            fun f(k: String, def: Float) = m.num(k, def.toDouble()).toFloat()
            return d.copy(
                frontCamera = (m["frontCamera"] as? Boolean) ?: d.frontCamera,
                handedness = runCatching { HandednessSetting.valueOf(m.str("handedness")!!) }.getOrDefault(d.handedness),
                sensitivity = f("sensitivity", d.sensitivity),
                depthSensitivity = f("depthSensitivity", d.depthSensitivity),
                invertLift = (m["invertLift"] as? Boolean) ?: d.invertLift,
                handScale = f("handScale", d.handScale),
                posMinCutoff = f("posMinCutoff", d.posMinCutoff), posBeta = f("posBeta", d.posBeta),
                shapeMinCutoff = f("shapeMinCutoff", d.shapeMinCutoff), shapeBeta = f("shapeBeta", d.shapeBeta),
                depthMinCutoff = f("depthMinCutoff", d.depthMinCutoff), depthBeta = f("depthBeta", d.depthBeta),
            )
        }

        fun calToMap(c: Calibration): Map<String, Any?> = linkedMapOf(
            "baseSize" to c.baseSize, "centerU" to c.centerU, "centerV" to c.centerV,
            "rangeU" to c.rangeU, "rangeV" to c.rangeV, "worldPalm" to c.worldPalmLength,
            "ext" to c.extents,
        )

        fun calFromMap(m: Map<String, Any?>) = Calibration(
            m.num("baseSize").toFloat(), m.num("centerU").toFloat(), m.num("centerV").toFloat(),
            m.num("rangeU").toFloat(), m.num("rangeV").toFloat(), m.num("worldPalm").toFloat(),
            (m["ext"] as? List<*>)?.map { (it as Double).toFloat() },
        )
    }
}

/** The guided test: a fixed script of prompts, some with timed cues to follow. */
object GuidedScript {
    class Step(val id: String, val title: String, val prompt: String, val seconds: Float, val cues: List<Pair<Float, String>>)

    private fun reps(a: String, b: String, count: Int, period: Float) =
        (0 until count).flatMap { listOf(it * period to a, it * period + period / 2 to b) }

    fun steps(frontCamera: Boolean): List<Step> {
        val liftHow = if (frontCamera) "pull your hand back toward you" else "bring your hand back toward the phone"
        val lowerHow = if (frontCamera) "push it toward the screen" else "reach farther behind the phone"
        return listOf(
            Step("ready", "Get ready", "Hold your hand up over the middle of the table.", 3f, emptyList()),
            Step("still_open", "Hold still", "Open hand, fingers spread, as still as you comfortably can.", 6f, listOf(0f to "OPEN")),
            Step("still_relaxed", "Relax", "Let your hand relax naturally and hold it there.", 5f, listOf(0f to "OPEN")),
            Step("pinch_reps", "Pinch", "Follow the cue: pinch thumb and index together, then open.", 15f, reps("PINCH", "OPEN", 5, 3f)),
            Step("fist_reps", "Fist", "Follow the cue: make a fist, then open.", 15f, reps("FIST", "OPEN", 5, 3f)),
            Step("sweep_slow", "Slow sweep", "Move slowly left and right across the whole table.", 8f, emptyList()),
            Step("sweep_fast", "Fast sweep", "Now quickly, left and right.", 6f, emptyList()),
            Step("depth", "Lift", "LIFT: $liftHow. LOWER: $lowerHow.", 15f, reps("LIFT", "LOWER", 3, 5f)),
            Step("edges", "Edges", "Slowly trace around the edges of the screen.", 10f, emptyList()),
        )
    }
}

/**
 * Collects a recording while the game runs. Pure Kotlin so the same logic works in tests.
 */
class TuningRecorder {
    var active = false
        private set
    var guidedSteps: List<GuidedScript.Step>? = null
        private set
    var time = 0.0
        private set

    private val events = ArrayList<Recording.Event>()
    private var header: Map<String, Any?> = emptyMap()
    private var lastSampleNs = 0L
    private var lastSettings: GameSettings? = null
    private var lastCalibration: Calibration? = null
    private var stepIndex = -1
    private var cueIndex = -1

    /** Current guided-test state for the UI. */
    val currentStep get() = guidedSteps?.getOrNull(stepIndex)
    var currentCue: String = ""
        private set
    var stepProgress = 0f
        private set
    val stepNumber get() = stepIndex + 1
    var finished = false
        private set

    fun start(guided: Boolean, info: Map<String, Any?>, frontCamera: Boolean) {
        events.clear()
        time = 0.0
        lastSampleNs = 0L
        lastSettings = null; lastCalibration = null
        finished = false
        guidedSteps = if (guided) GuidedScript.steps(frontCamera) else null
        stepIndex = -1; cueIndex = -1; currentCue = ""
        header = LinkedHashMap(info).apply {
            put("format", Recording.FORMAT_VERSION)
            put("guided", guided)
        }
        active = true
    }

    fun mark(kind: String) {
        if (active) events += Recording.Mark(time, kind)
    }

    fun onFrame(dt: Float, sample: TrackingSample?, game: GameController) {
        if (!active) return
        time += dt
        if (game.settings != lastSettings || game.calibration != lastCalibration) {
            lastSettings = game.settings; lastCalibration = game.calibration
            events += Recording.Config(time, game.settings, game.calibration)
        }
        advanceGuided()
        if (sample != null && sample.timestampNs != lastSampleNs) {
            lastSampleNs = sample.timestampNs
            val h = sample.hand
            events += Recording.Sample(
                time, h?.image?.copyOf(), h?.world?.copyOf(), h?.mpLabel, h?.confidence ?: 0f, sample.inferenceMs,
                sample.imageWidth, sample.imageHeight, sample.frontCamera,
                game.gestures.state.name, game.holding, game.phase.name, game.rig.pose.lift,
            )
        }
        if (guidedSteps == null && time > MAX_FREE_SECONDS) finished = true
    }

    private fun advanceGuided() {
        val steps = guidedSteps ?: return
        var start = 0.0
        var idx = -1
        for ((i, s) in steps.withIndex()) {
            if (time < start + s.seconds) { idx = i; break }
            start += s.seconds
        }
        if (idx == -1) {
            if (!finished) { events += Recording.Cue(time, "end", ""); finished = true }
            return
        }
        val step = steps[idx]
        val local = (time - start).toFloat()
        stepProgress = local / step.seconds
        var cue = -1
        for ((i, c) in step.cues.withIndex()) if (local >= c.first) cue = i
        if (idx != stepIndex || cue != cueIndex) {
            stepIndex = idx; cueIndex = cue
            currentCue = step.cues.getOrNull(cue)?.second ?: ""
            events += Recording.Cue(time, step.id, currentCue)
        }
    }

    fun stop(): Recording {
        active = false
        if (guidedSteps != null && !finished) events += Recording.Cue(time, "end", "")
        return Recording(header, ArrayList(events))
    }

    companion object {
        const val MAX_FREE_SECONDS = 600.0
    }
}
