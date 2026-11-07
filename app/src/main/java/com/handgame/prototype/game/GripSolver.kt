package com.handgame.prototype.game

import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.physics.RigidBox
import com.handgame.prototype.tracking.Lm
import kotlin.math.abs

/** Proportions of the rendered cartoon hand, shared by the renderer and the grip solver. */
object HandLook {
    /**
     * Overall size of the drawn hand relative to the original proportions. Set from
     * [GameSettings.handScale]; everything below (and the hand's shape) scales with it, so
     * gripping and table contact stay consistent.
     */
    @Volatile var scale = GameSettings.DEFAULT_HAND_SCALE

    val PALM_HALF_THICKNESS get() = 0.030f * scale
    val WRIST_HALF_WIDTH get() = 0.040f * scale
    val THUMB_PAD_RADIUS get() = 0.034f * scale

    /** Finger thickness at each joint: chunky, gently tapering, with soft rounded tips. */
    fun jointRadius(i: Int): Float = scale * when (i) {
        Lm.WRIST -> 0.034f
        Lm.THUMB_CMC -> 0.029f
        Lm.THUMB_MCP -> 0.026f
        Lm.THUMB_IP -> 0.024f
        Lm.THUMB_TIP -> 0.023f
        Lm.INDEX_MCP, Lm.MIDDLE_MCP, Lm.RING_MCP -> 0.025f
        Lm.INDEX_PIP, Lm.MIDDLE_PIP, Lm.RING_PIP -> 0.023f
        Lm.INDEX_DIP, Lm.MIDDLE_DIP, Lm.RING_DIP -> 0.022f
        Lm.INDEX_TIP, Lm.MIDDLE_TIP, Lm.RING_TIP -> 0.022f
        Lm.PINKY_MCP -> 0.021f
        Lm.PINKY_PIP -> 0.0195f
        else -> 0.0185f // pinky DIP / tip
    }

    /** Rendered palm length (wrist → middle knuckle). */
    val palmLength get() = PlaySpace.HAND_PALM_LENGTH * scale
}

/**
 * Adjusts the *drawn* hand so it holds the cube instead of passing through it. Tracking and
 * gestures keep using the real hand, because the player's real fingers close fully with nothing
 * between them.
 *
 *  - Pinch: thumb tip and index tip are placed on opposite faces of the cube (the faces that
 *    [gripAxis] points at), and the rest of each finger bends along with its tip.
 *  - Fist: curled fingers wrap over the cube's front face and the thumb rests on its side.
 *  - Always: any joint that would be inside the cube is pushed out to its surface, so the hand also
 *    rests on a cube it isn't holding.
 */
object GripSolver {

    /** How far each joint follows its fingertip's correction (tip = 1). */
    private val THUMB_CHAIN = intArrayOf(Lm.THUMB_TIP, Lm.THUMB_IP, Lm.THUMB_MCP, Lm.THUMB_CMC)
    private val INDEX_CHAIN = intArrayOf(Lm.INDEX_TIP, Lm.INDEX_DIP, Lm.INDEX_PIP, Lm.INDEX_MCP)
    private val CHAIN_WEIGHTS = floatArrayOf(1f, 0.7f, 0.35f, 0.1f)
    private val CURLED = intArrayOf(
        Lm.INDEX_PIP, Lm.INDEX_DIP, Lm.INDEX_TIP,
        Lm.MIDDLE_PIP, Lm.MIDDLE_DIP, Lm.MIDDLE_TIP,
        Lm.RING_PIP, Lm.RING_DIP, Lm.RING_TIP,
        Lm.PINKY_PIP, Lm.PINKY_DIP, Lm.PINKY_TIP,
    )
    private val THUMB = intArrayOf(Lm.THUMB_IP, Lm.THUMB_TIP)

    /**
     * @param src tracked joints.
     * @param out receives the adjusted joints.
     * @param mode PINCH or FIST while holding (blending in or out), OPEN otherwise.
     * @param gripAxis cube-local unit axis pointing at the face the thumb holds (pinch).
     * @param weight 0..1 blend of the grip pose.
     * @param handForward,thumbSide world directions used to wrap a fist around the cube.
     */
    fun solve(
        src: Array<Vec3>,
        out: Array<Vec3>,
        cube: RigidBox,
        mode: Gesture,
        gripAxis: Vec3,
        weight: Float,
        handForward: Vec3,
        thumbSide: Vec3,
    ) {
        for (i in src.indices) out[i] = src[i]
        val w = weight.coerceIn(0f, 1f)
        val half = cube.halfExtents

        if (w > 0f && mode == Gesture.PINCH) {
            val g = cube.orientation.rotate(gripAxis)
            val faceHalf = abs(gripAxis.x) * half.x + abs(gripAxis.y) * half.y + abs(gripAxis.z) * half.z
            val thumbTarget = cube.position + g * (faceHalf + HandLook.jointRadius(Lm.THUMB_TIP) * 0.85f)
            val indexTarget = cube.position - g * (faceHalf + HandLook.jointRadius(Lm.INDEX_TIP) * 0.85f)
            bendChain(out, THUMB_CHAIN, thumbTarget, w)
            bendChain(out, INDEX_CHAIN, indexTarget, w)
        }

        for (i in out.indices) {
            val r = HandLook.jointRadius(i) * 0.9f
            val preferred = when {
                w > 0f && mode == Gesture.FIST && i in CURLED -> handForward
                w > 0f && mode == Gesture.FIST && i in THUMB -> thumbSide
                else -> null
            }
            val pushed = pushOut(out[i], cube, r, preferred)
            out[i] = if (preferred != null) Vec3.lerp(pushOut(out[i], cube, r, null), pushed, w) else pushed
        }
    }

    private fun bendChain(joints: Array<Vec3>, chain: IntArray, tipTarget: Vec3, weight: Float) {
        val delta = (tipTarget - joints[chain[0]]) * weight
        for (k in chain.indices) joints[chain[k]] = joints[chain[k]] + delta * CHAIN_WEIGHTS[k]
    }

    /**
     * If a sphere at [p] with [radius] overlaps the box, moves it out to just touch the surface.
     * With [preferredDir], it exits through the face most aligned with that direction (used to
     * wrap fingers around a side); otherwise through the nearest face. Points outside the box
     * but within [radius] of an edge or corner are pushed straight away from the box.
     */
    fun pushOut(p: Vec3, box: RigidBox, radius: Float, preferredDir: Vec3?): Vec3 {
        val q = box.orientation
        val local = q.conjugate().rotate(p - box.position)
        val h = box.halfExtents
        val ex = abs(local.x) - h.x; val ey = abs(local.y) - h.y; val ez = abs(local.z) - h.z

        if (ex > 0f || ey > 0f || ez > 0f) {
            // Outside: push away from the closest surface point if closer than the radius.
            val closest = Vec3(local.x.coerceIn(-h.x, h.x), local.y.coerceIn(-h.y, h.y), local.z.coerceIn(-h.z, h.z))
            val d = local - closest
            val dist = d.length()
            if (dist >= radius || dist < 1e-6f) return p
            val fixed = closest + d * (radius / dist)
            return box.position + q.rotate(fixed)
        }

        // Inside: pick the exit axis.
        val axis: Int
        val sign: Float
        if (preferredDir != null) {
            val pl = q.conjugate().rotate(preferredDir)
            axis = if (abs(pl.x) >= abs(pl.y) && abs(pl.x) >= abs(pl.z)) 0 else if (abs(pl.y) >= abs(pl.z)) 1 else 2
            sign = if (component(pl, axis) >= 0f) 1f else -1f
        } else {
            // Nearest face: the axis with the least depth.
            axis = if (ex >= ey && ex >= ez) 0 else if (ey >= ez) 1 else 2
            sign = if (component(local, axis) >= 0f) 1f else -1f
        }
        val v = when (axis) {
            0 -> Vec3(sign * (h.x + radius), local.y, local.z)
            1 -> Vec3(local.x, sign * (h.y + radius), local.z)
            else -> Vec3(local.x, local.y, sign * (h.z + radius))
        }
        return box.position + q.rotate(v)
    }

    private fun component(v: Vec3, axis: Int) = when (axis) { 0 -> v.x; 1 -> v.y; else -> v.z }

    /** The signed cube-local axis most aligned with world direction [dir]. */
    fun nearestAxis(box: RigidBox, dir: Vec3): Vec3 {
        val l = box.orientation.conjugate().rotate(dir)
        return when {
            abs(l.x) >= abs(l.y) && abs(l.x) >= abs(l.z) -> Vec3(if (l.x >= 0) 1f else -1f, 0f, 0f)
            abs(l.y) >= abs(l.z) -> Vec3(0f, if (l.y >= 0) 1f else -1f, 0f)
            else -> Vec3(0f, 0f, if (l.z >= 0) 1f else -1f)
        }
    }
}
