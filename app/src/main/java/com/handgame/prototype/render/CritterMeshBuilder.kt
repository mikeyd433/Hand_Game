package com.handgame.prototype.render

import com.handgame.prototype.game.Critter
import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds the critter from its animation state each frame (after the player's sketch): a tall
 * gumdrop body with a rounded tip that leans and squashes, two wide flat feet spreading sideways
 * and curling up at the ends, and a small wavy mouth. Eyes and pupils are placed via [eyes].
 */
class CritterMeshBuilder {
    val body = Loft(segments = 18)
    val mouth = Loft(segments = 6)

    /** Eye placements: center, outward normal and "up" for each eye (left, right). */
    class Eye(val center: Vec3, val normal: Vec3, val up: Vec3)
    val eyes = arrayListOf<Eye>()

    fun build(c: Critter) {
        val rot = Quat.fromAxisAngle(Vec3.UP, c.facing)
        val base = c.position + Vec3(0f, c.hop, 0f)
        fun world(local: Vec3) = base + rot.rotate(local)

        val h = Critter.HEIGHT * (1f + c.squash) * (1f - c.breathe * 0.5f)
        val widen = (1f - c.squash * 0.6f) * (1f + c.breathe)
        // Spine: bends more toward the top when leaning.
        fun spine(t: Float) = Vec3(sin(c.leanX) * h * t * t, h * t, sin(c.leanZ) * h * t * t)
        // Gumdrop: wide base, tapering steadily to a soft rounded tip.
        fun radius(t: Float) = Critter.BASE_RADIUS * widen * (1.08f - 0.9f * t * kotlin.math.sqrt(t))

        body.begin()
        val ts = floatArrayOf(0f, 0.25f, 0.5f, 0.72f, 0.88f, 1f)
        body.tube(ts.map { world(spine(it)) }, FloatArray(ts.size) { radius(ts[it]).coerceAtLeast(0.012f) }, rot.rotate(Vec3(0f, 0f, 1f)), baseExtend = 0f)
        // Feet: flat lobes to each side, curling up at the tips.
        for (side in floatArrayOf(-1f, 1f)) {
            val len = Critter.FOOT_LENGTH * widen
            val pts = listOf(0.15f, 0.45f, 0.75f, 1f).map { k ->
                world(Vec3(side * len * k, 0.012f + 0.02f * k * k * k, 0.012f * k))
            }
            ellipseTube(pts, floatArrayOf(0.034f, 0.032f, 0.028f, 0.022f).map { it * widen }.toFloatArray(),
                floatArrayOf(0.016f, 0.015f, 0.014f, 0.013f), rot.rotate(Vec3.UP))
        }
        body.end()

        // Face: on the front of the body (+z, toward the viewer).
        eyes.clear()
        val eyeT = 0.55f
        val eyeY = spine(eyeT)
        val r = radius(eyeT)
        for (side in floatArrayOf(-1f, 1f)) {
            val ang = side * 0.42f
            val normalLocal = Vec3(sin(ang), 0.12f, cos(ang)).normalized()
            val center = eyeY + Vec3(normalLocal.x * r * 0.92f, 0f, normalLocal.z * r * 0.92f)
            eyes += Eye(world(center), rot.rotate(normalLocal), rot.rotate(Vec3.UP))
        }
        // Mouth: a little wave, turning into a smile when happy.
        val mouthT = 0.36f
        val m = spine(mouthT)
        val mr = radius(mouthT) * 0.98f
        mouth.begin()
        val n = 7
        val pts = (0 until n).map { i ->
            val x = (i / (n - 1f) - 0.5f) * 2f // -1..1
            val ang = x * 0.38f
            val wave = 0.004f * sin(x * 3.6f * 3.1416f / 2f) * (1f - c.happy)
            val smile = -0.007f * c.happy * (1f - x * x)
            world(Vec3(m.x + sin(ang) * mr, m.y + wave + smile, m.z + cos(ang) * mr))
        }
        mouth.tube(pts, FloatArray(n) { 0.0032f }, rot.rotate(Vec3(0f, 0f, 1f)), baseExtend = 0f, subdivisions = 2)
        mouth.end()
    }

    /** A tube with an elliptical (wide, flat) cross-section and rounded ends. */
    private fun ellipseTube(pts: List<Vec3>, widths: FloatArray, heights: FloatArray, up: Vec3) {
        val n = pts.size
        val tangents = Array(n) { i -> (pts[(i + 1).coerceAtMost(n - 1)] - pts[(i - 1).coerceAtLeast(0)]).normalized() }
        fun axes(i: Int): Pair<Vec3, Vec3> {
            val t = tangents[i]
            val a = (up - t * (up dot t)).normalized() // vertical
            return a to (t cross a)                    // (a, b = t × a): right-handed with t
        }
        val caps = HandMeshBuilder.CAP_ANGLES
        val (a0, b0) = axes(0)
        for (k in caps.size - 1 downTo 1) {
            val th = caps[k]
            body.ring(pts[0] - tangents[0] * (widths[0] * 0.6f * sin(th)), a0, b0, heights[0] * cos(th), widths[0] * cos(th))
        }
        for (i in 0 until n) { val (a, b) = axes(i); body.ring(pts[i], a, b, heights[i], widths[i]) }
        val (an, bn) = axes(n - 1)
        for (k in 1 until caps.size) {
            val th = caps[k]
            body.ring(pts[n - 1] + tangents[n - 1] * (widths[n - 1] * 0.8f * sin(th)), an, bn, heights[n - 1] * cos(th), widths[n - 1] * cos(th))
        }
        body.endPart()
    }
}
