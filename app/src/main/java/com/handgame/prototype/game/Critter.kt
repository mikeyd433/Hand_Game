package com.handgame.prototype.game

import com.handgame.prototype.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The hand's little friend: a gumdrop-shaped blob with two flat feet, big eyes and a wavy mouth.
 * Purely reactive (no needs or stats, per the spec). It can sleep, wakes with a hop, breathes,
 * blinks and sways, follows the hand with its eyes, squishes when poked, wiggles happily (with
 * hearts) when petted, and hops when it cheers.
 *
 * This class is the behaviour; [com.handgame.prototype.render.CritterMeshBuilder] draws it from
 * the animation values exposed here.
 */
class Critter(var position: Vec3, var facing: Float = 0f, val color: FloatArray = DEFAULT_COLOR) {

    // --- State the renderer reads -----------------------------------------------------------
    var awake = false
        private set
    /** Squash (−) / stretch (+) of the body, from springs. */
    var squash = 0f
        private set
    /** Body lean (x = sideways, z = toward/away), radians. */
    var leanX = 0f
        private set
    var leanZ = 0f
        private set
    /** Height off the table (hops). */
    var hop = 0f
        private set
    /** 0..1 eyelid closure (1 = closed). */
    var eyesClosed = 1f
        private set
    /** Where the pupils look, in the face's local frame (x right, y up), −1..1. */
    var lookX = 0f
        private set
    var lookY = 0f
        private set
    /** 0..1 happiness (drives the smile and wiggle). */
    var happy = 0f
        private set
    var breathe = 0f
        private set
    /** Floating hearts: position and age (s). */
    val hearts = ArrayList<Pair<Vec3, Float>>()

    /** Seconds of petting so far (the tutorial's pet lesson reads it). */
    var petSeconds = 0f
        private set
    var petting = false
        private set

    var onPoked: (() -> Unit)? = null

    // --- Internals -----------------------------------------------------------------------------
    private var time = Random.nextFloat() * 10f
    private var squashVel = 0f
    private var leanXVel = 0f
    private var leanZVel = 0f
    private var hopVel = 0f
    private var blinkTimer = 2f
    private var blink = 0f
    private var heartTimer = 0f
    private var pokeCooldown = 0f
    private var lookTargetX = 0f
    private var lookTargetY = 0f
    private val rnd = Random(7)

    val height get() = HEIGHT * (1f + squash)
    val top get() = position.y + hop + height

    fun wake() {
        if (awake) return
        awake = true
        hopVel = 1.6f
        squashVel -= 3f
        happy = 1f
    }

    fun sleep() { awake = false }

    /** A happy hop with hearts (e.g. something good happened nearby). */
    fun cheer() {
        if (!awake) wake()
        if (hop < 0.01f) hopVel = 1.4f
        happy = 1f
        repeat(3) { spawnHeart() }
    }

    fun update(dt: Float, game: GameController) = update(dt, game.handForCritter())

    fun update(dt: Float, hand: HandState) {
        time += dt
        pokeCooldown -= dt

        // Eyes follow the hand when awake (in the critter's facing frame), else drift.
        if (awake && hand.visible) {
            val d = hand.palm - position
            val side = d.x * cos(facing) - d.z * sin(facing)
            val fwd = -(d.x * sin(facing) + d.z * cos(facing))
            lookTargetX = (side / 0.25f).coerceIn(-1f, 1f)
            lookTargetY = ((d.y - HEIGHT * 0.7f) / 0.25f + fwd * 0.5f).coerceIn(-1f, 1f)
        } else {
            lookTargetX = 0.3f * sin(time * 0.4f); lookTargetY = -0.2f
        }
        lookX += (lookTargetX - lookX) * (6f * dt).coerceAtMost(1f)
        lookY += (lookTargetY - lookY) * (6f * dt).coerceAtMost(1f)

        // Poke: a fingertip touching the body squishes it.
        if (hand.visible && pokeCooldown <= 0f) {
            for ((tip, r) in hand.tips.take(2)) {
                if (insideBody(tip, r)) {
                    squashVel -= 2.5f
                    leanXVel += (position.x - tip.x).coerceIn(-0.1f, 0.1f) * 25f
                    pokeCooldown = 0.4f
                    if (!awake) wake() else happy = maxOf(happy, 0.5f)
                    onPoked?.invoke()
                    break
                }
            }
        }

        // Pet: an open hand gliding over the critter's head.
        val flat = hypot(hand.palm.x - position.x, hand.palm.z - position.z)
        val above = hand.palm.y - top
        val speed = hypot(hand.palmVelocity.x, hand.palmVelocity.z)
        // The tracked palm can sit below the head (the drawn hand rides up over it), so any height
        // from mid-body to a little above the tip counts.
        petting = awake && hand.visible && hand.open && flat < PET_RADIUS && above > -height * 0.6f && above < 0.18f && speed > 0.08f
        if (petting) {
            petSeconds += dt
            happy = 1f
            leanXVel += (hand.palmVelocity.x).coerceIn(-1f, 1f) * 4f * dt * 10f
            leanZVel += (hand.palmVelocity.z).coerceIn(-1f, 1f) * 4f * dt * 10f
            heartTimer -= dt
            if (heartTimer <= 0f) { spawnHeart(); heartTimer = 0.35f }
        }

        // Happiness fades; while happy, a little wiggle.
        happy = (happy - dt * 0.25f).coerceAtLeast(0f)
        val wiggle = if (awake) happy * 0.12f * sin(time * 14f) else 0f

        // Breathing (slow when asleep), and idle sway.
        breathe = sin(time * (if (awake) 2.2f else 1.1f)) * (if (awake) 0.03f else 0.05f)
        val swayX = REST_LEAN + if (awake) 0.05f * sin(time * 0.9f) else 0.02f * sin(time * 0.5f)

        // Springs: squash, lean, hop.
        squashVel += (-squash * 120f - squashVel * 9f) * dt
        squash += squashVel * dt
        squash = squash.coerceIn(-0.45f, 0.45f)
        leanXVel += ((swayX + wiggle - leanX) * 80f - leanXVel * 8f) * dt
        leanZVel += ((0f - leanZ) * 80f - leanZVel * 8f) * dt
        leanX = (leanX + leanXVel * dt).coerceIn(-0.6f, 0.6f)
        leanZ = (leanZ + leanZVel * dt).coerceIn(-0.6f, 0.6f)
        hopVel -= 9.81f * dt
        hop += hopVel * dt
        if (hop < 0f) {
            if (hopVel < -0.8f) squashVel -= hopVel * -1.5f // land with a squish
            hop = 0f; hopVel = 0f
        }

        // Blinking (eyes closed while asleep).
        blinkTimer -= dt
        if (blinkTimer <= 0f) { blink = 0.15f; blinkTimer = 2f + rnd.nextFloat() * 3f }
        blink = (blink - dt).coerceAtLeast(0f)
        val targetClosed = if (!awake) 1f else if (blink > 0f) 1f else 0f
        eyesClosed += (targetClosed - eyesClosed) * (18f * dt).coerceAtMost(1f)

        // Hearts float up and fade.
        for (i in hearts.indices.reversed()) {
            val (p, age) = hearts[i]
            if (age > HEART_LIFE) hearts.removeAt(i) else hearts[i] = Vec3(p.x + 0.02f * sin(age * 6f + i) * dt, p.y + 0.12f * dt, p.z) to age + dt
        }
    }

    private fun spawnHeart() {
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        hearts += Vec3(position.x + 0.03f * cos(a), top + 0.02f, position.z + 0.03f * sin(a)) to 0f
    }

    /** True if a sphere at [p] with [r] touches the body (a cone-ish dome over the base). */
    fun insideBody(p: Vec3, r: Float): Boolean {
        val y = p.y - (position.y + hop)
        if (y < -r || y > height + r) return false
        val t = (y / height).coerceIn(0f, 1f)
        val radius = BASE_RADIUS * (1.05f - 0.85f * t * t) // tapers to a rounded tip
        return hypot(p.x - position.x, p.z - position.z) < radius + r
    }

    companion object {
        const val HEIGHT = 0.19f
        /** Resting lean of the tip (the sketch leans a little to one side). */
        const val REST_LEAN = 0.18f
        const val BASE_RADIUS = 0.055f
        const val FOOT_LENGTH = 0.11f
        const val PET_RADIUS = 0.13f
        const val HEART_LIFE = 1.4f
        /** Bright violet, after the sketch, lighter than the lavender table. */
        val DEFAULT_COLOR = floatArrayOf(0.84f, 0.52f, 1.0f)
    }
}
