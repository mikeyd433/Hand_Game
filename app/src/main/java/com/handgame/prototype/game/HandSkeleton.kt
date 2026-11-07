package com.handgame.prototype.game

import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.Lm
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rigid cartoon hand skeleton driven by joint *angles* instead of raw landmark positions.
 *
 * MediaPipe's per-joint depth is noisy, so placing joints directly makes fingers stretch, shrink
 * and fold through each other. Instead we read only how each finger bends from the tracked
 * landmarks, clamp those angles to what a real hand can do, smooth them, and pose a skeleton with
 * fixed bone lengths. The hand can then curl and spread but never distort.
 *
 * Local palm frame (matches [HandRig]'s palm rotation): x = hand's right, y = back of the hand,
 * z = toward the wrist (fingers point along −z). Lengths are in units of palm length (wrist →
 * middle knuckle). The index finger sits on −x for a right hand and +x for a left hand.
 */
object HandSkeleton {

    /** Angle vector layout: per finger [spread, flex1, flex2, flex3] ×4, then thumb (8 values). */
    const val FINGER_VALUES = 4
    const val THUMB_OFFSET = 16
    const val SIZE = 24 // thumb: dir xyz, bend-axis xyz, flex2, flex3

    // Knuckle positions (x is mirrored for handedness via the index-side sign).
    private val MCP = arrayOf(
        Vec3(0.33f, 0f, -0.93f),  // index
        Vec3(0.10f, 0f, -1.00f),  // middle
        Vec3(-0.13f, 0f, -0.95f), // ring
        Vec3(-0.34f, 0f, -0.84f), // pinky
    )
    // Bone lengths: proximal, middle, distal. Slightly short and chunky for a toy look.
    private val LENGTHS = arrayOf(
        floatArrayOf(0.42f, 0.25f, 0.21f),
        floatArrayOf(0.46f, 0.29f, 0.23f),
        floatArrayOf(0.43f, 0.27f, 0.22f),
        floatArrayOf(0.33f, 0.21f, 0.19f),
    )
    /** Resting spread of each finger, radians toward the index side. */
    private val REST_SPREAD = floatArrayOf(0.10f, 0.02f, -0.07f, -0.16f)

    private val THUMB_CMC = Vec3(0.28f, -0.08f, -0.22f)
    private val THUMB_LENGTHS = floatArrayOf(0.38f, 0.30f, 0.26f)

    // Joint limits (radians).
    private const val FLEX1_MIN = -0.35f; private const val FLEX1_MAX = 1.65f
    private const val FLEX2_MAX = 1.85f; private const val FLEX3_MAX = 1.5f
    private const val SPREAD_MAX = 0.30f
    private const val THUMB_CONE = 1.2f   // max deviation of the thumb from its rest direction
    private const val THUMB_FLEX2_MAX = 1.3f; private const val THUMB_FLEX3_MAX = 1.5f

    private fun restThumbDir(s: Float) = Vec3(0.65f * s, -0.25f, -0.70f).normalized()
    private fun restThumbAxis(s: Float): Vec3 {
        val d = restThumbDir(s)
        // Thumb curls across the palm, toward the pinky side and down.
        return (d cross Vec3(-s, -0.6f, -0.3f).normalized()).normalized()
    }

    /** Index-side sign: −1 for a right hand, +1 for a left hand. */
    fun sideSign(rightHand: Boolean) = if (rightHand) -1f else 1f

    /** A natural relaxed pose, used before tracking has produced anything. */
    fun restAngles(rightHand: Boolean): FloatArray {
        val s = sideSign(rightHand)
        val a = FloatArray(SIZE)
        for (f in 0 until 4) { a[f * 4 + 1] = 0.25f; a[f * 4 + 2] = 0.35f; a[f * 4 + 3] = 0.2f }
        val d = restThumbDir(s); val n = restThumbAxis(s)
        a[THUMB_OFFSET] = d.x; a[THUMB_OFFSET + 1] = d.y; a[THUMB_OFFSET + 2] = d.z
        a[THUMB_OFFSET + 3] = n.x; a[THUMB_OFFSET + 4] = n.y; a[THUMB_OFFSET + 5] = n.z
        a[THUMB_OFFSET + 6] = 0.2f; a[THUMB_OFFSET + 7] = 0.2f
        return a
    }

    /**
     * Reads joint angles from tracked joints (any scale, world axes) given the tracked palm
     * rotation. Writes clamped values into [out].
     */
    fun extract(raw: Array<Vec3>, palm: Quat, rightHand: Boolean, out: FloatArray) {
        val inv = palm.conjugate()
        val s = sideSign(rightHand)
        fun local(a: Int, b: Int) = inv.rotate(raw[b] - raw[a]).normalized()

        for (f in 0 until 4) {
            val mcp = Lm.INDEX_MCP + f * 4
            val b1 = local(mcp, mcp + 1); val b2 = local(mcp + 1, mcp + 2); val b3 = local(mcp + 2, mcp + 3)
            val horiz = sqrt(b1.x * b1.x + b1.z * b1.z)
            val flex1 = atan2(-b1.y, horiz).coerceIn(FLEX1_MIN, FLEX1_MAX)
            // Sideways spread is unreliable when the finger points down, so fade it out then.
            val spreadAbs = atan2(b1.x * s, -b1.z)
            val spread = ((spreadAbs - REST_SPREAD[f]) * cos(flex1).coerceAtLeast(0f)).coerceIn(-SPREAD_MAX, SPREAD_MAX)
            out[f * 4] = spread
            out[f * 4 + 1] = flex1
            out[f * 4 + 2] = angle(b1, b2).coerceIn(0f, FLEX2_MAX)
            out[f * 4 + 3] = angle(b2, b3).coerceIn(0f, FLEX3_MAX)
        }

        // Thumb: direction of its first bone (kept inside a cone), the axis it curls around, and
        // how far it curls at the two outer joints.
        val t1 = local(Lm.THUMB_CMC, Lm.THUMB_MCP)
        val t2 = local(Lm.THUMB_MCP, Lm.THUMB_IP)
        val t3 = local(Lm.THUMB_IP, Lm.THUMB_TIP)
        val rest = restThumbDir(s)
        val dir = clampToCone(t1, rest, THUMB_CONE)
        var axis = (t1 cross t2) + (t2 cross t3)
        axis = if (axis.length() < 0.15f) restThumbAxis(s) else axis.normalized()
        // Make the axis perpendicular to the thumb so the curl stays planar.
        axis = (axis - dir * (axis dot dir)).normalized().let { if (it.lengthSq() < 0.5f) restThumbAxis(s) else it }
        val o = THUMB_OFFSET
        out[o] = dir.x; out[o + 1] = dir.y; out[o + 2] = dir.z
        out[o + 3] = axis.x; out[o + 4] = axis.y; out[o + 5] = axis.z
        out[o + 6] = angle(t1, t2).coerceIn(0f, THUMB_FLEX2_MAX)
        out[o + 7] = angle(t2, t3).coerceIn(0f, THUMB_FLEX3_MAX)
    }

    /**
     * Poses the skeleton from [angles]. Writes 21 joints in the local palm frame, scaled to
     * [palmLength] and centered on the palm center (average of wrist and the four knuckles).
     */
    fun pose(angles: FloatArray, rightHand: Boolean, palmLength: Float, out: Array<Vec3>) {
        val s = sideSign(rightHand)
        val down = Vec3(0f, -1f, 0f)
        out[Lm.WRIST] = Vec3.ZERO
        for (f in 0 until 4) {
            val mcpIdx = Lm.INDEX_MCP + f * 4
            val m = MCP[f]
            val mcp = Vec3(m.x * s, m.y, m.z)
            val spread = REST_SPREAD[f] + angles[f * 4]
            val fwd = Vec3(sin(spread) * s, 0f, -cos(spread))
            val hinge = (fwd cross down).normalized()
            val f1 = angles[f * 4 + 1]
            val f2 = f1 + angles[f * 4 + 2]
            val f3 = f2 + angles[f * 4 + 3]
            val len = LENGTHS[f]
            val pip = mcp + Quat.fromAxisAngle(hinge, f1).rotate(fwd) * len[0]
            val dip = pip + Quat.fromAxisAngle(hinge, f2).rotate(fwd) * len[1]
            val tip = dip + Quat.fromAxisAngle(hinge, f3).rotate(fwd) * len[2]
            out[mcpIdx] = mcp; out[mcpIdx + 1] = pip; out[mcpIdx + 2] = dip; out[mcpIdx + 3] = tip
        }
        val o = THUMB_OFFSET
        val dir = Vec3(angles[o], angles[o + 1], angles[o + 2]).normalized().let { if (it.lengthSq() < 0.5f) restThumbDir(s) else it }
        val axis = Vec3(angles[o + 3], angles[o + 4], angles[o + 5]).normalized().let { if (it.lengthSq() < 0.5f) restThumbAxis(s) else it }
        val cmc = Vec3(THUMB_CMC.x * s, THUMB_CMC.y, THUMB_CMC.z)
        val mcp = cmc + dir * THUMB_LENGTHS[0]
        val ip = mcp + Quat.fromAxisAngle(axis, angles[o + 6]).rotate(dir) * THUMB_LENGTHS[1]
        val tip = ip + Quat.fromAxisAngle(axis, angles[o + 6] + angles[o + 7]).rotate(dir) * THUMB_LENGTHS[2]
        out[Lm.THUMB_CMC] = cmc; out[Lm.THUMB_MCP] = mcp; out[Lm.THUMB_IP] = ip; out[Lm.THUMB_TIP] = tip

        // Center on the palm and scale.
        var c = Vec3.ZERO
        for (i in Lm.PALM_POINTS) c += out[i]
        c /= Lm.PALM_POINTS.size.toFloat()
        for (i in out.indices) out[i] = (out[i] - c) * palmLength
    }

    private fun angle(a: Vec3, b: Vec3) = acos((a dot b).coerceIn(-1f, 1f))

    private fun clampToCone(v: Vec3, axis: Vec3, maxAngle: Float): Vec3 {
        val a = angle(v, axis)
        if (a <= maxAngle || a < 1e-4f) return v
        return Quat.slerp(Quat.IDENTITY, Quat.fromTo(axis, v), maxAngle / a).rotate(axis).normalized()
    }
}
