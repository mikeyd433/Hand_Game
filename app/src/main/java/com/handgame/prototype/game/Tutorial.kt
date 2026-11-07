package com.handgame.prototype.game

import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Vec3
import kotlin.math.hypot

/**
 * The tutorial room: one lesson per gesture, each with a small task, introducing the critter
 * friend along the way (spec §6). Lessons move on by themselves when their task is done.
 *
 *  1. Pinch: pick up the little block and lift it.
 *  2. Fist: grab the big block with a fist and set it in the ring.
 *  3. Poke: press the glowing button, which wakes the sleeping critter.
 *  4. Pet: glide an open hand over the critter.
 *  5. Flick: flick a little block across to the critter.
 *  6. Throw: throw a block into the far ring.
 */
class Tutorial(private val game: GameController) {

    enum class Lesson(val title: String) {
        PINCH("Pinch"), FIST("Fist grab"), POKE("Poke"), PET("Pet"), FLICK("Flick"), THROW("Throw"), DONE("Ready!")
    }

    var lesson = Lesson.PINCH
        private set
    /** 0..1 progress within lessons that build up (lift height, petting time). */
    var progress = 0f
        private set
    /** Seconds left of the "well done" moment before the next lesson. */
    var celebrating = 0f
        private set
    val finished get() = lesson == Lesson.DONE

    var onFinished: (() -> Unit)? = null

    private val props = ArrayList<Toy>()
    private var timer = 0f
    private var flag = false
    private var flickedAt = -10f
    private var thrownAt = -10f
    private var petStart = 0f

    val lessonCount get() = Lesson.entries.size - 1
    val lessonNumber get() = lesson.ordinal + 1

    fun start() {
        clearProps()
        game.setToyActive(game.mainToy, false)
        game.target = null
        // The friend is there from the start, asleep on the far side of the table.
        game.critter = Critter(CRITTER_HOME, facing = 0f)
        enter(Lesson.PINCH)
    }

    /** Leaves the tutorial: tidies the room, keeps the friend (awake) and brings back the cube. */
    fun exit() {
        clearProps()
        game.target = null
        game.buttons.clear()
        game.critter?.wake()
        game.setToyActive(game.mainToy, true)
        game.respawnCube()
    }

    fun skipLesson() {
        if (finished) return
        complete(cheer = false)
        celebrating = 0.01f
    }

    /** The instruction for the current lesson. */
    fun prompt(frontCamera: Boolean): String {
        val lift = if (frontCamera) "pull your hand back toward you" else "bring your hand back toward the phone"
        val push = if (frontCamera) "push your hand toward the screen" else "reach farther behind the phone"
        return when (lesson) {
            Lesson.PINCH -> "Hold your hand over the little yellow block, pinch thumb and index together, then $lift to lift it."
            Lesson.FIST -> "Make a fist over the big block to grab it, carry it to the glowing ring and open your hand."
            Lesson.POKE -> "Point your index finger over the glowing button, then $push to press it."
            Lesson.PET -> "Your new friend is awake! Say hello: glide your open hand gently over its head."
            Lesson.FLICK -> "Point your index finger and flick the little block over to your friend with a quick swipe."
            Lesson.THROW -> "Pick up the block, swing your hand toward the far ring and let go while moving."
            Lesson.DONE -> "You've learned every move. Your friend will be waiting on the table."
        }
    }

    fun update(dt: Float, events: List<GameEvent>) {
        if (finished) return
        if (celebrating > 0f) {
            celebrating -= dt
            if (celebrating <= 0f) enter(Lesson.entries[lesson.ordinal + 1])
            return
        }
        timer += dt
        val critter = game.critter
        for (e in events) {
            if (e.kind == "flick") flickedAt = timer
            // A throw: let go far enough from the ring that the block had to fly or slide in.
            val ring = game.target
            if (e.kind == "release" && ring != null && hypot(e.cube.x - ring.center.x, e.cube.z - ring.center.z) > THROW_MIN_DISTANCE) thrownAt = timer
        }
        val done = when (lesson) {
            Lesson.PINCH -> {
                val toy = props.firstOrNull()
                val lifted = toy != null && game.held === toy && game.heldWith == Gesture.PINCH
                val y = toy?.body?.position?.y ?: 0f
                progress = if (lifted) ((y - 0.06f) / (LIFT_HEIGHT - 0.06f)).coerceIn(0f, 1f) else 0f
                lifted && y > LIFT_HEIGHT
            }
            Lesson.FIST -> {
                val toy = props.firstOrNull()
                if (toy != null && game.held === toy && game.heldWith == Gesture.FIST) flag = true
                val t = game.target
                toy != null && t != null && flag && game.held == null && restingIn(toy, t)
            }
            Lesson.POKE -> game.buttons.firstOrNull()?.pressed == true || flag
            Lesson.PET -> {
                progress = (((critter?.petSeconds ?: 0f) - petStart) / PET_SECONDS).coerceIn(0f, 1f)
                progress >= 1f
            }
            Lesson.FLICK -> {
                val toy = props.firstOrNull()
                toy != null && critter != null && timer - flickedAt < 3f &&
                    hypot(toy.body.position.x - critter.position.x, toy.body.position.z - critter.position.z) < 0.2f
            }
            Lesson.THROW -> {
                val toy = props.firstOrNull()
                val t = game.target
                toy != null && t != null && timer - thrownAt < 6f && game.held == null && restingIn(toy, t, slack = 0.03f)
            }
            Lesson.DONE -> false
        }
        if (done) complete(cheer = true)
    }

    private fun complete(cheer: Boolean) {
        progress = 1f
        celebrating = CELEBRATE_SECONDS
        if (lesson == Lesson.POKE) game.critter?.wake()
        if (cheer && lesson != Lesson.POKE) game.critter?.takeIf { it.awake }?.cheer()
        game.emitEvent("lesson_done:${lesson.name}", Vec3.ZERO)
    }

    private fun enter(next: Lesson) {
        clearProps()
        game.target = null
        game.buttons.clear()
        lesson = next
        progress = 0f; timer = 0f; flag = false; flickedAt = -10f; thrownAt = -10f
        val h = { half: Float -> half }
        when (next) {
            Lesson.PINCH -> props += game.addToy(Vec3(0.045f, 0.045f, 0.045f), YELLOW, Vec3(-0.12f, h(0.045f), 0.2f), "tut_small")
            Lesson.FIST -> {
                props += game.addToy(Vec3(0.085f, 0.085f, 0.085f), CORAL, Vec3(-0.2f, 0.085f, 0.15f), "tut_big", mass = 0.6f)
                game.target = Target(Vec3(0.17f, 0f, 0.4f), 0.15f)
            }
            Lesson.POKE -> {
                val b = PushButton(Vec3(-0.05f, 0f, 0.15f), color = CORAL)
                b.onPressed = { flag = true }
                game.buttons += b
            }
            Lesson.PET -> { game.critter?.wake(); petStart = game.critter?.petSeconds ?: 0f }
            Lesson.FLICK -> props += game.addToy(Vec3(0.035f, 0.035f, 0.035f), MINT, Vec3(0.1f, 0.035f, 0.05f), "tut_flick", mass = 0.15f)
            Lesson.THROW -> {
                props += game.addToy(Vec3(0.05f, 0.05f, 0.05f), YELLOW, Vec3(-0.15f, 0.05f, 0.35f), "tut_throw")
                game.target = Target(Vec3(-0.1f, 0f, -0.55f), 0.15f)
            }
            Lesson.DONE -> { game.critter?.cheer(); onFinished?.invoke() }
        }
    }

    private fun restingIn(toy: Toy, t: Target, slack: Float = 0f): Boolean {
        val b = toy.body
        return hypot(b.position.x - t.center.x, b.position.z - t.center.z) < t.radius + slack &&
            b.position.y < b.halfExtents.y * 1.3f && b.linearVelocity.length() < 0.1f
    }

    private fun clearProps() {
        props.forEach { game.removeToy(it) }
        props.clear()
    }

    /** The current lesson's prop (for tests and hints). */
    val prop: Toy? get() = props.firstOrNull()

    companion object {
        val CRITTER_HOME = Vec3(0.22f, 0f, -0.35f)
        const val LIFT_HEIGHT = 0.2f
        const val PET_SECONDS = 2f
        /** Letting go at least this far from the ring counts as a throw (not carrying it in). */
        const val THROW_MIN_DISTANCE = 0.3f
        const val CELEBRATE_SECONDS = 1.6f
        val YELLOW = floatArrayOf(1f, 0.82f, 0.3f)
        val CORAL = floatArrayOf(1f, 0.5f, 0.45f)
        val MINT = floatArrayOf(0.25f, 0.85f, 0.7f)
    }
}
