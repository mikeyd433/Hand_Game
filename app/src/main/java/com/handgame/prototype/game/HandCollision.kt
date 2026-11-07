package com.handgame.prototype.game

import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.physics.RigidBox
import com.handgame.prototype.tracking.Lm
import kotlin.math.abs

/**
 * Finds and resolves places where the *drawn* hand would pass through itself or the world:
 *  - finger against finger (each bone is a capsule with the cartoon finger thickness),
 *  - fingertips sinking into the palm (e.g. in a fist),
 *  - joints going into the table,
 *  - finger bones cutting through the cube's edges (the grip pass only checks joints).
 *
 * Overlapping parts are pushed apart over a few relaxation passes, then every finger chain is
 * re-walked from its root so bone lengths stay exactly as they were: the hand stays solid.
 * Stats report how deep the worst overlap was before and after, for tuning.
 */
object HandCollision {

    class Stats {
        /** Deepest overlap found before resolving (m). */
        var maxDepth = 0f
        /** Deepest overlap left after resolving (m). */
        var residual = 0f
        /** Number of overlapping pairs found before resolving. */
        var contacts = 0
        /** Deepest overlap before resolving, by kind. */
        var fingers = 0f; var palm = 0f; var cube = 0f; var table = 0f
        /** Which bones overlapped worst (debug/tuning). */
        var worstFingerPair = ""
        var worstCubeBone = ""
        fun reset() { maxDepth = 0f; residual = 0f; contacts = 0; fingers = 0f; palm = 0f; cube = 0f; table = 0f }
    }

    /** Finger chains from root to tip. Roots (first entry) never move. */
    private val CHAINS = arrayOf(
        intArrayOf(Lm.THUMB_CMC, Lm.THUMB_MCP, Lm.THUMB_IP, Lm.THUMB_TIP),
        intArrayOf(Lm.INDEX_MCP, Lm.INDEX_PIP, Lm.INDEX_DIP, Lm.INDEX_TIP),
        intArrayOf(Lm.MIDDLE_MCP, Lm.MIDDLE_PIP, Lm.MIDDLE_DIP, Lm.MIDDLE_TIP),
        intArrayOf(Lm.RING_MCP, Lm.RING_PIP, Lm.RING_DIP, Lm.RING_TIP),
        intArrayOf(Lm.PINKY_MCP, Lm.PINKY_PIP, Lm.PINKY_DIP, Lm.PINKY_TIP),
    )

    /** Overlap tolerated between chunky parts before it counts (cartoon fingers squish a bit). */
    const val SQUISH = 0.85f
    /** Side-by-side neighbouring fingers may squish further: they read as fused, like a mitten. */
    const val NEIGHBOUR_SQUISH = 0.6f
    private const val ITERATIONS = 6
    /** In thumb-vs-finger contacts the thumb, which wraps over the outside, does most of the moving. */
    private const val THUMB_SHARE = 0.8f

    private val NAMES = arrayOf("thumb", "index", "middle", "ring", "pinky")

    private class Seg(val finger: Int, val index: Int, val a: Int, val b: Int)

    private fun squish(a: Seg, b: Seg) =
        if (a.finger != 0 && b.finger != 0 && abs(a.finger - b.finger) == 1) NEIGHBOUR_SQUISH else SQUISH

    private val SEGMENTS: List<Seg> = CHAINS.indices.flatMap { f ->
        (0 until 3).map { k -> Seg(f, k, CHAINS[f][k], CHAINS[f][k + 1]) }
    }

    /** Segment pairs worth testing: different fingers, skipping parts that are fused into the palm. */
    private val PAIRS: List<Pair<Seg, Seg>> = run {
        val out = ArrayList<Pair<Seg, Seg>>()
        for (i in SEGMENTS.indices) for (j in i + 1 until SEGMENTS.size) {
            val a = SEGMENTS[i]; val b = SEGMENTS[j]
            if (a.finger == b.finger) continue
            val thumb = a.finger == 0 || b.finger == 0
            // The thumb's root bone and neighbouring finger roots sit inside the palm together.
            if (thumb && ((a.finger == 0 && a.index == 0) || (b.finger == 0 && b.index == 0))) continue
            val neighbours = !thumb && abs(a.finger - b.finger) == 1
            if (neighbours && (a.index == 0 || b.index == 0)) continue
            out += a to b
        }
        out
    }

    private val PALM_TOUCHERS = intArrayOf(
        Lm.INDEX_DIP, Lm.INDEX_TIP, Lm.MIDDLE_DIP, Lm.MIDDLE_TIP, Lm.RING_DIP, Lm.RING_TIP,
        Lm.PINKY_DIP, Lm.PINKY_TIP, Lm.THUMB_IP, Lm.THUMB_TIP,
    )

    /**
     * Resolves overlaps in [joints] in place.
     * @param tableY table-top height, or null to skip the table (e.g. hand beyond its edge).
     */
    fun solve(joints: Array<Vec3>, palm: Quat, cube: RigidBox?, tableY: Float?, stats: Stats, holdingCube: Boolean = false) {
        stats.reset()
        val nearCubeForStats = cube != null && cube.distanceTo(joints[Lm.MIDDLE_MCP]) < 0.35f
        measureKinds(joints, palm, cube.takeIf { nearCubeForStats }, tableY, stats)
        // A free cube under the hand is a surface to rest on, like the table: lift the whole hand
        // just enough that no joint over the cube's footprint dips into it.
        if (cube != null && !holdingCube && nearCubeForStats) rideOver(joints, cube)
        val lengths = Array(CHAINS.size) { f -> FloatArray(3) { k -> joints[CHAINS[f][k]].distance(joints[CHAINS[f][k + 1]]) } }
        val pinned = BooleanArray(Lm.COUNT).also { p -> CHAINS.forEach { p[it[0]] = true }; p[Lm.WRIST] = true }
        val nearCube = cube != null && cube.distanceTo(joints[Lm.MIDDLE_MCP]) < 0.35f

        for (iter in 0 until ITERATIONS) {
            val first = iter == 0
            var worst = 0f
            // Finger vs finger.
            for ((sa, sb) in PAIRS) {
                val ra = minOf(HandLook.jointRadius(sa.a), HandLook.jointRadius(sa.b))
                val rb = minOf(HandLook.jointRadius(sb.a), HandLook.jointRadius(sb.b))
                val minDist = (ra + rb) * squish(sa, sb)
                val (s, t) = closestParams(joints[sa.a], joints[sa.b], joints[sb.a], joints[sb.b])
                val pa = Vec3.lerp(joints[sa.a], joints[sa.b], s)
                val pb = Vec3.lerp(joints[sb.a], joints[sb.b], t)
                var d = pa - pb
                val dist = d.length()
                if (dist >= minDist) continue
                val depth = minDist - dist
                worst = maxOf(worst, depth)
                if (first) stats.contacts++
                d = if (dist > 1e-6f) d / dist else palm.rotate(Vec3(1f, 0f, 0f))
                // Split the push between the two sides (mostly the thumb when it's involved),
                // spread over each bone's two joints by where the contact is.
                val shareA = when { sa.finger == 0 -> THUMB_SHARE; sb.finger == 0 -> 1f - THUMB_SHARE; else -> 0.5f }
                move(joints, pinned, sa.a, sa.b, s, d * (depth * shareA))
                move(joints, pinned, sb.a, sb.b, t, d * (-depth * (1f - shareA)))
            }
            // Fingertips into the palm slab.
            worst = maxOf(worst, palmPass(joints, palm, first, stats))
            // Finger bones through the cube.
            if (nearCube) worst = maxOf(worst, cubePass(joints, pinned, cube!!, first, stats))
            restoreLengths(joints, lengths)
            // Table last, after lengths are restored, so nothing drags a finger back under.
            if (tableY != null) worst = maxOf(worst, tablePass(joints, pinned, tableY))
            if (worst < 1e-4f) break
        }
        stats.residual = measure(joints, palm, cube.takeIf { nearCube }, tableY)
    }

    private fun rideOver(joints: Array<Vec3>, cube: RigidBox) {
        val inv = cube.orientation.conjugate()
        val h = cube.halfExtents
        var lift = 0f
        for (i in 0 until Lm.COUNT) {
            val r = HandLook.jointRadius(i) * 0.9f
            val local = inv.rotate(joints[i] - cube.position)
            if (abs(local.x) > h.x + r * 0.5f || abs(local.z) > h.z + r * 0.5f) continue
            // Only joints coming down onto the top (not a hand beside the cube at table level).
            if (local.y < 0f) continue
            lift = maxOf(lift, h.y + r - local.y)
        }
        if (lift <= 0f) return
        for (i in 0 until Lm.COUNT) joints[i] = joints[i] + Vec3(0f, lift, 0f)
    }

    private fun tablePass(joints: Array<Vec3>, pinned: BooleanArray, tableY: Float): Float {
        var worst = 0f
        for (i in 0 until Lm.COUNT) {
            val floor = tableY + HandLook.jointRadius(i) * 0.9f
            if (joints[i].y < floor) {
                worst = maxOf(worst, floor - joints[i].y)
                if (!pinned[i]) joints[i] = Vec3(joints[i].x, floor, joints[i].z)
            }
        }
        return worst
    }

    /** Overlap depth of each kind on the hand as given (nothing moved), into [stats]. */
    private fun measureKinds(joints: Array<Vec3>, palm: Quat, cube: RigidBox?, tableY: Float?, stats: Stats) {
        var fingers = 0f
        stats.worstFingerPair = ""
        for ((sa, sb) in PAIRS) {
            val ra = minOf(HandLook.jointRadius(sa.a), HandLook.jointRadius(sa.b))
            val rb = minOf(HandLook.jointRadius(sb.a), HandLook.jointRadius(sb.b))
            val (s, t) = closestParams(joints[sa.a], joints[sa.b], joints[sb.a], joints[sb.b])
            val dist = Vec3.lerp(joints[sa.a], joints[sa.b], s).distance(Vec3.lerp(joints[sb.a], joints[sb.b], t))
            val d = (ra + rb) * squish(sa, sb) - dist
            if (d > fingers) { fingers = d; stats.worstFingerPair = "${NAMES[sa.finger]}${sa.index}-${NAMES[sb.finger]}${sb.index}" }
        }
        stats.worstCubeBone = ""
        if (cube != null) {
            var w = 0f
            for (seg in SEGMENTS) for (sp in floatArrayOf(0.33f, 0.66f)) {
                val p = Vec3.lerp(joints[seg.a], joints[seg.b], sp)
                val d = GripSolver.pushOut(p, cube, minOf(HandLook.jointRadius(seg.a), HandLook.jointRadius(seg.b)) * 0.9f, null).distance(p)
                if (d > w) { w = d; stats.worstCubeBone = "${NAMES[seg.finger]}${seg.index}" }
            }
        }
        val scratch = joints.copyOf()
        stats.fingers = fingers.coerceAtLeast(0f)
        stats.palm = palmPass(scratch, palm, false, Stats())
        stats.cube = if (cube != null) cubePass(joints.copyOf(), BooleanArray(Lm.COUNT), cube, false, Stats()) else 0f
        stats.table = if (tableY != null) (0 until Lm.COUNT).maxOf { tableY + HandLook.jointRadius(it) * 0.9f - joints[it].y }.coerceAtLeast(0f) else 0f
        stats.maxDepth = maxOf(stats.fingers, stats.palm, stats.cube, stats.table)
    }

    private fun palmPass(joints: Array<Vec3>, palm: Quat, first: Boolean, stats: Stats): Float {
        val inv = palm.conjugate()
        val center = (joints[Lm.WRIST] + joints[Lm.INDEX_MCP] + joints[Lm.MIDDLE_MCP] + joints[Lm.RING_MCP] + joints[Lm.PINKY_MCP]) * 0.2f
        val hw = joints[Lm.INDEX_MCP].distance(joints[Lm.PINKY_MCP]) * 0.5f
        val zWrist = inv.rotate(joints[Lm.WRIST] - center).z
        val zKnuckle = inv.rotate(joints[Lm.MIDDLE_MCP] - center).z
        val ht = HandLook.PALM_HALF_THICKNESS
        var worst = 0f
        for (i in PALM_TOUCHERS) {
            val r = HandLook.jointRadius(i) * SQUISH
            val q = inv.rotate(joints[i] - center)
            if (abs(q.x) > hw || q.z < minOf(zKnuckle, zWrist) || q.z > maxOf(zKnuckle, zWrist)) continue
            val limit = ht + r
            if (abs(q.y) >= limit) continue
            val depth = limit - abs(q.y)
            worst = maxOf(worst, depth)
            if (first) stats.contacts++
            // Out through the side it's on: palm side for curled fingers.
            val y = if (q.y < 0f) -limit else limit
            joints[i] = center + palm.rotate(Vec3(q.x, y, q.z))
        }
        return worst
    }

    private fun cubePass(joints: Array<Vec3>, pinned: BooleanArray, cube: RigidBox, first: Boolean, stats: Stats): Float {
        var worst = 0f
        for (seg in SEGMENTS) {
            val r = minOf(HandLook.jointRadius(seg.a), HandLook.jointRadius(seg.b)) * 0.9f
            for (s in floatArrayOf(0.33f, 0.66f)) {
                val p = Vec3.lerp(joints[seg.a], joints[seg.b], s)
                val q = GripSolver.pushOut(p, cube, r, null)
                val push = q - p
                val depth = push.length()
                if (depth < 1e-5f) continue
                worst = maxOf(worst, depth)
                if (first) stats.contacts++
                move(joints, pinned, seg.a, seg.b, s, push)
            }
        }
        return worst
    }

    /**
     * Moves the contact point at parameter [s] on bone a→b by [delta], by moving the bone's free
     * ends: the contact point moves by (1−s)·δa + s·δb, so with δa = (1−s)·k·delta and
     * δb = s·k·delta, k = 1 / ((1−s)² + s²) moves it exactly by delta. Pinned ends stay put.
     */
    private fun move(joints: Array<Vec3>, pinned: BooleanArray, a: Int, b: Int, s: Float, delta: Vec3) {
        val wa = if (pinned[a]) 0f else 1f - s
        val wb = if (pinned[b]) 0f else s
        val norm = wa * wa + wb * wb
        if (norm < 1e-3f) return
        val k = (1f / norm).coerceAtMost(4f)
        if (wa > 0f) joints[a] = joints[a] + delta * (wa * k)
        if (wb > 0f) joints[b] = joints[b] + delta * (wb * k)
    }

    /** Re-walks each finger from its root so every bone keeps its length. */
    private fun restoreLengths(joints: Array<Vec3>, lengths: Array<FloatArray>) {
        for ((f, chain) in CHAINS.withIndex()) for (k in 1 until chain.size) {
            val parent = joints[chain[k - 1]]
            val dir = (joints[chain[k]] - parent).let { if (it.lengthSq() < 1e-12f) Vec3(0f, 0f, -1f) else it.normalized() }
            joints[chain[k]] = parent + dir * lengths[f][k - 1]
        }
    }

    /** Deepest remaining overlap of any kind (for stats). */
    fun measure(joints: Array<Vec3>, palm: Quat, cube: RigidBox?, tableY: Float?): Float {
        var worst = 0f
        for ((sa, sb) in PAIRS) {
            val ra = minOf(HandLook.jointRadius(sa.a), HandLook.jointRadius(sa.b))
            val rb = minOf(HandLook.jointRadius(sb.a), HandLook.jointRadius(sb.b))
            val (s, t) = closestParams(joints[sa.a], joints[sa.b], joints[sb.a], joints[sb.b])
            val dist = Vec3.lerp(joints[sa.a], joints[sa.b], s).distance(Vec3.lerp(joints[sb.a], joints[sb.b], t))
            worst = maxOf(worst, (ra + rb) * squish(sa, sb) - dist)
        }
        val scratch = joints.copyOf()
        worst = maxOf(worst, palmPass(scratch, palm, false, Stats()))
        if (cube != null) worst = maxOf(worst, cubePass(scratch, BooleanArray(Lm.COUNT), cube, false, Stats()))
        if (tableY != null) for (i in 0 until Lm.COUNT) worst = maxOf(worst, tableY + HandLook.jointRadius(i) * 0.9f - joints[i].y)
        return worst.coerceAtLeast(0f)
    }

    /** Parameters (s, t) of the closest points between segments p1–q1 and p2–q2. */
    fun closestParams(p1: Vec3, q1: Vec3, p2: Vec3, q2: Vec3): Pair<Float, Float> {
        val d1 = q1 - p1; val d2 = q2 - p2; val r = p1 - p2
        val a = d1 dot d1; val e = d2 dot d2; val f = d2 dot r
        if (a < 1e-10f && e < 1e-10f) return 0f to 0f
        if (a < 1e-10f) return 0f to (f / e).coerceIn(0f, 1f)
        val c = d1 dot r
        if (e < 1e-10f) return (-c / a).coerceIn(0f, 1f) to 0f
        val b = d1 dot d2
        val denom = a * e - b * b
        var s = if (denom > 1e-10f) ((b * f - c * e) / denom).coerceIn(0f, 1f) else 0f
        var t = (b * s + f) / e
        if (t < 0f) { t = 0f; s = (-c / a).coerceIn(0f, 1f) } else if (t > 1f) { t = 1f; s = ((b - c) / a).coerceIn(0f, 1f) }
        return s to t
    }
}
