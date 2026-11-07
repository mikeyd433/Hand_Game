package com.handgame.prototype.game

import com.handgame.prototype.math.Vec3
import kotlin.math.hypot

/**
 * A round push button on the table. Any fingertip coming down onto its cap presses it, and so
 * does pointing at it and pushing "into" the screen ([pointer]); the cap travels down with the
 * press, and [onPressed] fires once per press (re-arms when released).
 */
class PushButton(val center: Vec3, val radius: Float = 0.075f, val height: Float = 0.055f, val color: FloatArray) {
    /** 0..1 how far the cap is pushed down. */
    var press = 0f
        private set
    var pressed = false
        private set
    var lit = 0f
        private set
    var onPressed: (() -> Unit)? = null

    val top get() = center.y + height

    /**
     * @param pointer the pointing index fingertip and how far the hand is pushed "into" the screen
     *   (0..1 dip), or null when not pointing.
     */
    fun update(dt: Float, fingertips: List<Pair<Vec3, Float>>, pointer: Pair<Vec3, Float>? = null) {
        var depth = 0f
        for ((tip, r) in fingertips) {
            if (hypot(tip.x - center.x, tip.z - center.z) > radius) continue
            depth = maxOf(depth, (top + r - tip.y) / TRAVEL)
        }
        if (pointer != null && hypot(pointer.first.x - center.x, pointer.first.z - center.z) < radius * 1.2f) {
            depth = maxOf(depth, pointer.second * 1.25f)
        }
        val target = depth.coerceIn(0f, 1f)
        // Snappy going down, springy coming back up.
        press += (target - press) * ((if (target > press) 30f else 12f) * dt).coerceAtMost(1f)
        if (!pressed && press > PRESS_AT) { pressed = true; lit = 1f; onPressed?.invoke() }
        if (pressed && press < RELEASE_AT) pressed = false
        lit = (lit - dt * 0.6f).coerceAtLeast(if (pressed) 1f else 0f)
    }

    companion object {
        /** How far the cap can travel (m). */
        const val TRAVEL = 0.02f
        const val PRESS_AT = 0.6f
        const val RELEASE_AT = 0.25f
    }
}
