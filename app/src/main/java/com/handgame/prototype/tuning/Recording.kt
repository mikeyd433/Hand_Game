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
        /** Camera capture time, seconds since recording start (null in older recordings). */
        val ct: Double? = null,
        /** Cube position (x, y, z). */
        val cube: FloatArray? = null,
        /** Drawn-hand overlap this frame, deepest before and after resolving (m). */
        val collision: Float = 0f,
        val collisionResidual: Float = 0f,
    ) : Event()

    /** Something the cube did: grab, release, drop_forced, respawn. */
    class GameEvt(override val t: Double, val kind: String, val gesture: String, val speed: Float, val cube: FloatArray) : Event()

    /** A guided task finished, successfully or by timing out. */
    class Outcome(override val t: Double, val step: String, val success: Boolean, val seconds: Double, val detail: Map<String, Any?>) : Event()

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
                    "ct" to e.ct, "cube" to e.cube, "col" to e.collision, "colr" to e.collisionResidual,
                )
                is GameEvt -> linkedMapOf("k" to "evt", "t" to e.t, "kind" to e.kind, "g" to e.gesture, "v" to e.speed, "cube" to e.cube)
                is Outcome -> linkedMapOf("k" to "out", "t" to e.t, "step" to e.step, "ok" to e.success, "sec" to e.seconds, "detail" to e.detail)
                is Cue -> linkedMapOf("k" to "cue", "t" to e.t, "step" to e.step, "expect" to e.expect)
                is Mark -> linkedMapOf("k" to "mark", "t" to e.t, "kind" to e.kind)
                is Config -> linkedMapOf("k" to "cfg", "t" to e.t, "settings" to settingsToMap(e.settings), "cal" to calToMap(e.calibration))
            }
            Json.write(sb, m); sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val FORMAT_VERSION = 2

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
                        m["ct"] as? Double, m.floats("cube"), m.num("col").toFloat(), m.num("colr").toFloat(),
                    )
                    "evt" -> GameEvt(t, m.str("kind") ?: "", m.str("g") ?: "", m.num("v").toFloat(), m.floats("cube") ?: FloatArray(3))
                    "out" -> Outcome(t, m.str("step") ?: "", m["ok"] == true, m.num("sec"), (m["detail"] as? Map<*, *>)?.obj() ?: emptyMap())
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

/** The guided test: a fixed script of prompts, some with timed cues, some with tasks to complete. */
object GuidedScript {
    /**
     * @param seconds fixed length, or the time limit for a step with an [objective].
     * @param objective a task that ends the step early when completed (see [ObjectiveTracker]).
     */
    class Step(
        val id: String, val title: String, val prompt: String, val seconds: Float,
        val cues: List<Pair<Float, String>> = emptyList(), val objective: String? = null,
    )

    private fun reps(a: String, b: String, count: Int, period: Float) =
        (0 until count).flatMap { listOf(it * period to a, it * period + period / 2 to b) }

    fun steps(frontCamera: Boolean): List<Step> {
        val liftHow = if (frontCamera) "pull your hand back toward you" else "bring your hand back toward the phone"
        val lowerHow = if (frontCamera) "push it toward the screen" else "reach farther behind the phone"
        return listOf(
            Step("ready", "Get ready", "Hold your hand up over the middle of the table.", 3f),
            Step("still_open", "Hold still", "Open hand, fingers spread, as still as you comfortably can.", 6f, listOf(0f to "OPEN")),
            Step("still_relaxed", "Relax", "Let your hand relax naturally and hold it there.", 5f, listOf(0f to "OPEN")),
            Step("pinch_reps", "Pinch", "Follow the cue: pinch thumb and index together, then open.", 15f, reps("PINCH", "OPEN", 5, 3f)),
            Step("fist_reps", "Fist", "Follow the cue: make a fist, then open.", 15f, reps("FIST", "OPEN", 5, 3f)),
            Step("sweep_slow", "Slow sweep", "Move slowly left and right across the whole table.", 8f),
            Step("sweep_fast", "Fast sweep", "Now quickly, left and right.", 6f),
            Step("depth", "Lift", "LIFT: $liftHow. LOWER: $lowerHow.", 15f, reps("LIFT", "LOWER", 3, 5f)),
            Step("edges", "Edges", "Slowly trace around the edges of the screen.", 10f),
            // Object tasks: each ends as soon as it's done.
            Step("grab_pinch", "Pinch-grab", "Reach over the cube and pinch to pick it up.", 15f, objective = "grab_pinch"),
            Step("lift_hold", "Lift and hold", "Lift the cube up high and hold it steady. (Grab it again if you dropped it.)", 12f, objective = "lift_hold"),
            Step("place_ring", "Place", "Set the cube down gently inside the glowing ring.", 15f, objective = "place_ring"),
            Step("fist_carry", "Fist carry", "Grab the cube with a fist and carry it to the new ring.", 18f, objective = "fist_carry"),
            Step("throw", "Throw", "Pick the cube up and throw it off the table.", 12f, objective = "throw"),
        )
    }
}

/**
 * Sets up and judges the guided object tasks against the live game.
 */
class ObjectiveTracker {
    var done = false
        private set
    val detail = LinkedHashMap<String, Any?>()

    private var id: String? = null
    private var timer = 0f
    private var holdTimer = 0f
    private var lastGesture = "OPEN"
    private var usedFist = false
    private var releaseAt: com.handgame.prototype.math.Vec3? = null
    private var releaseTime = 0f
    private val heldPositions = ArrayList<com.handgame.prototype.math.Vec3>()

    fun begin(objective: String?, game: GameController) {
        id = objective; done = false; detail.clear()
        timer = 0f; holdTimer = 0f; usedFist = false; releaseAt = null; heldPositions.clear()
        lastGesture = game.gestures.state.name
        detail["attempts"] = 0; detail["grabs"] = 0; detail["drops"] = 0
        val v = { x: Float, y: Float, z: Float -> com.handgame.prototype.math.Vec3(x, y, z) }
        val h = com.handgame.prototype.game.PlaySpace.CUBE_HALF
        when (objective) {
            "grab_pinch" -> { game.placeCube(v(-0.15f, h, -0.15f)); game.target = null }
            "lift_hold" -> game.target = null
            "place_ring" -> game.target = com.handgame.prototype.game.Target(v(0.2f, 0f, 0.4f), 0.13f)
            "fist_carry" -> { game.placeCube(v(0.2f, h, -0.3f)); game.target = com.handgame.prototype.game.Target(v(-0.2f, 0f, 0.35f), 0.13f) }
            "throw" -> game.target = null
            else -> game.target = null
        }
    }

    /** @return true on the frame the task is completed. */
    fun update(dt: Float, game: GameController, events: List<com.handgame.prototype.game.GameEvent>): Boolean {
        val objective = id ?: return false
        if (done) return false
        timer += dt
        val g = game.gestures.state.name
        if (g != lastGesture && g != "OPEN") detail["attempts"] = (detail["attempts"] as Int) + 1
        lastGesture = g
        for (e in events) {
            when (e.kind) {
                "grab" -> { detail["grabs"] = (detail["grabs"] as Int) + 1; if (e.gesture == "FIST") usedFist = true }
                "release" -> {
                    releaseAt = e.cube; releaseTime = timer
                    detail["release_speed"] = e.speed
                    if (objective == "lift_hold") detail["drops"] = (detail["drops"] as Int) + 1
                }
            }
        }
        val cube = game.cube
        val target = game.target
        fun inRing() = target != null && kotlin.math.hypot(cube.position.x - target.center.x, cube.position.z - target.center.z) < target.radius &&
            !game.holding && cube.position.y < com.handgame.prototype.game.PlaySpace.CUBE_HALF * 1.3f && cube.linearVelocity.length() < 0.08f
        val complete = when (objective) {
            "grab_pinch" -> {
                holdTimer = if (game.heldWith.name == "PINCH") holdTimer + dt else 0f
                holdTimer > 0.3f
            }
            "lift_hold" -> {
                val high = game.holding && cube.position.y > 0.22f
                holdTimer = if (high) holdTimer + dt else 0f
                if (high) heldPositions += cube.position else heldPositions.clear()
                if (holdTimer > 2f) {
                    // How much the held cube shook while lifted (frame-to-frame change in motion).
                    val vel = heldPositions.zipWithNext { a, b -> b - a }
                    detail["held_shake_mm"] = vel.zipWithNext { a, b -> (b - a).length().toDouble() }.average() * 1000
                    detail["held_motion_mm"] = vel.map { it.length().toDouble() }.average() * 1000
                    true
                } else false
            }
            "place_ring", "fist_carry" -> {
                val ok = inRing() && (objective == "place_ring" || usedFist)
                if (ok) detail["error_mm"] = kotlin.math.hypot(cube.position.x - target!!.center.x, cube.position.z - target.center.z) * 1000.0
                if (objective == "fist_carry") detail["used_fist"] = usedFist
                ok
            }
            "throw" -> {
                val r = releaseAt
                r != null && (detail["release_speed"] as? Float ?: 0f) > 0.8f && timer - releaseTime < 3f &&
                    (cube.position.y < -0.2f || kotlin.math.hypot(cube.position.x - r.x, cube.position.z - r.z) > 0.5f)
            }
            else -> false
        }
        if (complete) done = true
        return complete
    }

    fun end(game: GameController) {
        game.target = null
        id = null
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
    private var stepStart = 0.0
    private var doneAt = -1.0
    private val objectives = ObjectiveTracker()
    private var startNs = 0L

    /** Current guided-test state for the UI. */
    val currentStep get() = guidedSteps?.getOrNull(stepIndex)
    var currentCue: String = ""
        private set
    var stepProgress = 0f
        private set
    val stepNumber get() = stepIndex + 1
    /** True briefly after a task step is completed (for a ✓ on screen). */
    val objectiveDone get() = doneAt >= 0
    var finished = false
        private set

    /** @param nowNs System.nanoTime at start, to express capture times relative to the recording. */
    fun start(guided: Boolean, info: Map<String, Any?>, frontCamera: Boolean, nowNs: Long = 0L, game: GameController? = null) {
        startNs = nowNs
        // Events from before the recording aren't part of it.
        game?.drainEvents()
        events.clear()
        time = 0.0
        lastSampleNs = 0L
        lastSettings = null; lastCalibration = null
        finished = false
        guidedSteps = if (guided) GuidedScript.steps(frontCamera) else null
        stepIndex = -1; cueIndex = -1; currentCue = ""; doneAt = -1.0
        header = LinkedHashMap(info).apply {
            put("format", Recording.FORMAT_VERSION)
            put("guided", guided)
        }
        active = true
    }

    fun mark(kind: String) {
        if (active) events += Recording.Mark(time, kind)
    }

    fun onFrame(dt: Float, sample: TrackingSample?, game: GameController, nowNs: Long? = null) {
        if (!active) return
        time += dt
        // Keep the recording clock on real time when available (so capture times line up).
        if (nowNs != null && startNs != 0L) time = (nowNs - startNs) / 1e9
        if (game.settings != lastSettings || game.calibration != lastCalibration) {
            lastSettings = game.settings; lastCalibration = game.calibration
            events += Recording.Config(time, game.settings, game.calibration)
        }
        val gameEvents = game.drainEvents()
        for (e in gameEvents) events += Recording.GameEvt(time, e.kind, e.gesture, e.speed, floatArrayOf(e.cube.x, e.cube.y, e.cube.z))
        advanceGuided(dt, game, gameEvents)
        if (sample != null && sample.timestampNs != lastSampleNs) {
            lastSampleNs = sample.timestampNs
            val h = sample.hand
            val c = game.cube.position
            events += Recording.Sample(
                time, h?.image?.copyOf(), h?.world?.copyOf(), h?.mpLabel, h?.confidence ?: 0f, sample.inferenceMs,
                sample.imageWidth, sample.imageHeight, sample.frontCamera,
                game.gestures.state.name, game.holding, game.phase.name, game.rig.pose.lift,
                if (startNs != 0L) (sample.timestampNs - startNs) / 1e9 else null,
                floatArrayOf(c.x, c.y, c.z), game.collisionStats.maxDepth, game.collisionStats.residual,
            )
        }
        if (guidedSteps == null && time > MAX_FREE_SECONDS) finished = true
    }

    private fun advanceGuided(dt: Float, game: GameController, gameEvents: List<com.handgame.prototype.game.GameEvent>) {
        val steps = guidedSteps ?: return
        if (finished) return
        if (stepIndex == -1) enterStep(0, game)
        val step = steps[stepIndex]
        val local = (time - stepStart).toFloat()
        stepProgress = (local / step.seconds).coerceIn(0f, 1f)

        if (step.objective != null && doneAt < 0 && objectives.update(dt, game, gameEvents)) {
            doneAt = time
            events += Recording.Outcome(time, step.id, true, local.toDouble(), LinkedHashMap(objectives.detail))
        }
        val timedOut = local >= step.seconds
        if (step.objective != null && timedOut && doneAt < 0) {
            events += Recording.Outcome(time, step.id, false, local.toDouble(), LinkedHashMap(objectives.detail))
        }
        if (timedOut || (doneAt >= 0 && time - doneAt > 1.0)) {
            if (stepIndex + 1 >= steps.size) {
                objectives.end(game)
                events += Recording.Cue(time, "end", "")
                finished = true
            } else enterStep(stepIndex + 1, game)
            return
        }
        var cue = -1
        for ((i, c) in step.cues.withIndex()) if (local >= c.first) cue = i
        if (cue != cueIndex) {
            cueIndex = cue
            currentCue = step.cues.getOrNull(cue)?.second ?: ""
            events += Recording.Cue(time, step.id, currentCue)
        }
    }

    private fun enterStep(index: Int, game: GameController) {
        val step = guidedSteps!![index]
        stepIndex = index; stepStart = time; cueIndex = -1; doneAt = -1.0
        currentCue = step.cues.firstOrNull { it.first <= 0f }?.second ?: ""
        if (step.cues.firstOrNull()?.first == 0f) cueIndex = 0
        events += Recording.Cue(time, step.id, currentCue)
        objectives.begin(step.objective, game)
    }

    fun stop(game: GameController? = null): Recording {
        active = false
        if (guidedSteps != null && !finished) events += Recording.Cue(time, "end", "")
        game?.let { objectives.end(it) }
        return Recording(header, ArrayList(events))
    }

    companion object {
        const val MAX_FREE_SECONDS = 600.0
    }
}
