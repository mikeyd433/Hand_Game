package com.handgame.prototype.render

import com.handgame.prototype.game.HandLook
import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.Lm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sign

/**
 * Builds smooth hand meshes from the 21 joints every frame. Every part is a "loft": a stack of
 * rings (circles or rounded rectangles) joined into one surface.
 *  - [body]: palm slab, four tapered fingers and the thumb, each one seamless tube with rounded caps.
 *  - [cuff]: a rolled cuff closing off the wrist.
 *
 * The topology never changes between frames, so index buffers are built once and only
 * positions and normals are rewritten.
 */
class HandMeshBuilder {
    val body = Loft(segments = 14)
    val cuff = Loft(segments = 20)

    fun build(joints: Array<Vec3>, rotation: Quat) {
        val right = rotation.rotate(Vec3(1f, 0f, 0f))
        val up = rotation.rotate(Vec3.UP)

        body.begin()
        buildPalm(joints, right, up)
        for (f in 0 until 4) {
            val mcp = Lm.INDEX_MCP + f * 4
            val pts = listOf(joints[mcp], joints[mcp + 1], joints[mcp + 2], joints[mcp + 3])
            val radii = floatArrayOf(
                HandLook.jointRadius(mcp), HandLook.jointRadius(mcp + 1),
                HandLook.jointRadius(mcp + 2), HandLook.jointRadius(mcp + 3),
            )
            // Start a little behind the knuckle so the finger root is buried in the palm.
            body.tube(pts, radii, up, baseExtend = 0.022f)
        }
        // Thumb: rooted between wrist and its first joint, with a fat pad at the base.
        val thumbRoot = Vec3.lerp(joints[Lm.WRIST], joints[Lm.THUMB_CMC], 0.45f)
        body.tube(
            listOf(thumbRoot, joints[Lm.THUMB_CMC], joints[Lm.THUMB_MCP], joints[Lm.THUMB_IP], joints[Lm.THUMB_TIP]),
            floatArrayOf(
                HandLook.THUMB_PAD_RADIUS, HandLook.jointRadius(Lm.THUMB_CMC), HandLook.jointRadius(Lm.THUMB_MCP),
                HandLook.jointRadius(Lm.THUMB_IP), HandLook.jointRadius(Lm.THUMB_TIP),
            ),
            up,
            baseExtend = 0.01f,
        )
        body.end()

        cuff.begin()
        buildCuff(joints, right)
        cuff.end()
    }

    private fun palmFrame(joints: Array<Vec3>, right: Vec3): Triple<Vec3, Vec3, Vec3> {
        val wrist = joints[Lm.WRIST]
        val knuckles = (joints[Lm.INDEX_MCP] + joints[Lm.MIDDLE_MCP] + joints[Lm.RING_MCP] + joints[Lm.PINKY_MCP]) * 0.25f
        val d = (knuckles - wrist).normalized().let { if (it.lengthSq() < 0.5f) Vec3(0f, 0f, -1f) else it }
        val a = (right - d * (right dot d)).normalized().let { if (it.lengthSq() < 0.5f) Vec3(1f, 0f, 0f) else it }
        return Triple(d, a, d cross a)
    }

    private fun buildPalm(j: Array<Vec3>, right: Vec3, up: Vec3) {
        val wrist = j[Lm.WRIST]
        val knuckles = (j[Lm.INDEX_MCP] + j[Lm.MIDDLE_MCP] + j[Lm.RING_MCP] + j[Lm.PINKY_MCP]) * 0.25f
        val (d, a, b) = palmFrame(j, right)
        val length = wrist.distance(knuckles)
        val knuckleHalfWidth = j[Lm.INDEX_MCP].distance(j[Lm.PINKY_MCP]) * 0.5f + 0.016f
        val wristHalfWidth = HandLook.WRIST_HALF_WIDTH
        val p = 3.2f // rounded-rectangle cross-section

        fun halfWidth(t: Float) = wristHalfWidth + (knuckleHalfWidth - wristHalfWidth) * smooth(t)
        fun halfThick(t: Float) = HandLook.PALM_HALF_THICKNESS * (0.85f + 0.15f * t)

        // Wrist-end cap (hidden inside the cuff).
        for (k in CAP_ANGLES.size - 1 downTo 1) {
            val th = CAP_ANGLES[k]
            body.ring(wrist - d * (0.02f * sin(th)), a, b, halfWidth(0f) * cos(th), halfThick(0f) * cos(th), p)
        }
        for (t in floatArrayOf(0f, 0.3f, 0.6f, 0.85f, 1f)) {
            body.ring(wrist + d * (length * t), a, b, halfWidth(t), halfThick(t), p)
        }
        // Domed knuckle end.
        for (k in 1 until CAP_ANGLES.size) {
            val th = CAP_ANGLES[k]
            body.ring(knuckles + d * (0.026f * sin(th)), a, b, halfWidth(1f) * cos(th), halfThick(1f) * cos(th), p)
        }
    }

    private fun buildCuff(j: Array<Vec3>, right: Vec3) {
        val (d, a, _) = palmFrame(j, right)
        val back = -d
        val bAxis = back cross a
        val wrist = j[Lm.WRIST]
        val hw = HandLook.WRIST_HALF_WIDTH
        val ht = HandLook.PALM_HALF_THICKNESS * 0.85f
        // (distance behind the wrist, scale) — a rolled lip, closed at the back.
        for ((offset, scale) in CUFF_PROFILE) {
            cuff.ring(wrist + back * offset, a, bAxis, hw * scale, ht * scale + 0.006f * (scale > 0f).toFloat(), 2.6f)
        }
    }

    private fun Boolean.toFloat() = if (this) 1f else 0f
    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

    companion object {
        /** Angles for hemispherical caps: index 0 = full size, last = closed pole. */
        val CAP_ANGLES = floatArrayOf(0f, 0.55f, 1.0f, 1.35f, (PI / 2).toFloat())

        val CUFF_PROFILE = listOf(
            -0.008f to 0.95f,
            0.006f to 1.14f,
            0.020f to 1.22f,
            0.034f to 1.26f,
            0.045f to 1.22f,
            0.051f to 1.08f,
            0.054f to 0.75f,
            0.055f to 0f,
        )
    }
}

/**
 * Accumulates rings into a mesh. Call [begin], add parts with [ring]/[tube] (each part is closed
 * with [endPart], which [tube] does itself), then [end]. The first [end] fixes the index layout.
 */
class Loft(val segments: Int) {
    var positions = FloatArray(0); private set
    var normals = FloatArray(0); private set
    var indices = ShortArray(0); private set
    val vertexCount get() = cursor / 3

    private var cursor = 0
    private val partRings = ArrayList<Int>()
    private var ringsInPart = 0
    private var layoutFixed = false
    private var buildPositions = FloatArray(4096)

    fun begin() {
        cursor = 0
        ringsInPart = 0
        if (!layoutFixed) partRings.clear()
    }

    fun ring(center: Vec3, a: Vec3, b: Vec3, ra: Float, rb: Float, exponent: Float = 2f) {
        ensure(cursor + segments * 3)
        val e = 2f / exponent
        for (i in 0 until segments) {
            val phi = 2.0 * PI * i / segments
            val c = cos(phi).toFloat(); val s = sin(phi).toFloat()
            val x = sign(c) * abs(c).pow(e) * ra
            val y = sign(s) * abs(s).pow(e) * rb
            buildPositions[cursor++] = center.x + a.x * x + b.x * y
            buildPositions[cursor++] = center.y + a.y * x + b.y * y
            buildPositions[cursor++] = center.z + a.z * x + b.z * y
        }
        ringsInPart++
    }

    fun endPart() {
        if (!layoutFixed) partRings += ringsInPart
        ringsInPart = 0
    }

    /**
     * Smooth tapered tube through [pts] (Catmull-Rom), radii interpolated from [radii], with a
     * rounded cap at each end. [up] seeds the ring orientation; frames are parallel-transported so
     * the surface never twists.
     */
    fun tube(pts: List<Vec3>, radii: FloatArray, up: Vec3, baseExtend: Float, subdivisions: Int = 3) {
        // Extend the start backwards so the root sinks into whatever it's attached to.
        val dir0 = (pts[1] - pts[0]).normalized()
        val control = ArrayList<Vec3>(pts.size + 1).apply { add(pts[0] - dir0 * baseExtend); addAll(pts) }
        val rad = FloatArray(radii.size + 1).also { it[0] = radii[0]; radii.copyInto(it, 1) }

        val samples = ArrayList<Vec3>(); val sr = ArrayList<Float>()
        for (i in 0 until control.size - 1) {
            val p0 = if (i > 0) control[i - 1] else control[i] * 2f - control[i + 1]
            val p1 = control[i]; val p2 = control[i + 1]
            val p3 = if (i + 2 < control.size) control[i + 2] else control[i + 1] * 2f - control[i]
            val steps = if (i == 0) 1 else subdivisions
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                samples += catmullRom(p0, p1, p2, p3, t)
                sr += rad[i] + (rad[i + 1] - rad[i]) * t
            }
        }
        samples += control.last(); sr += rad.last()

        val n = samples.size
        val tangents = Array(n) { i ->
            val a = samples[(i - 1).coerceAtLeast(0)]; val b = samples[(i + 1).coerceAtMost(n - 1)]
            (b - a).normalized().let { if (it.lengthSq() < 0.5f) dir0 else it }
        }
        // Parallel-transported frames.
        val aAxes = arrayOfNulls<Vec3>(n)
        var a = (up - tangents[0] * (up dot tangents[0])).normalized()
        if (a.lengthSq() < 0.5f) a = (Vec3(1f, 0f, 0f) - tangents[0] * tangents[0].x).normalized()
        aAxes[0] = a
        for (i in 1 until n) {
            a = Quat.fromTo(tangents[i - 1], tangents[i]).rotate(a)
            a = (a - tangents[i] * (a dot tangents[i])).normalized()
            aAxes[i] = a
        }
        fun frameB(i: Int) = tangents[i] cross aAxes[i]!!

        val caps = HandMeshBuilder.CAP_ANGLES
        // Base cap: from the pole up to full size.
        for (k in caps.size - 1 downTo 1) {
            val th = caps[k]; val r = sr[0]
            ring(samples[0] - tangents[0] * (r * sin(th)), aAxes[0]!!, frameB(0), r * cos(th), r * cos(th))
        }
        for (i in 0 until n) ring(samples[i], aAxes[i]!!, frameB(i), sr[i], sr[i])
        // Rounded fingertip.
        for (k in 1 until caps.size) {
            val th = caps[k]; val r = sr[n - 1]
            ring(samples[n - 1] + tangents[n - 1] * (r * sin(th)), aAxes[n - 1]!!, frameB(n - 1), r * cos(th), r * cos(th))
        }
        endPart()
    }

    fun end() {
        if (ringsInPart > 0) endPart()
        if (!layoutFixed) {
            buildIndices()
            layoutFixed = true
        }
        check(cursor == positions.size || positions.isEmpty()) { "Loft topology changed between frames" }
        if (positions.size != cursor) {
            positions = FloatArray(cursor)
            normals = FloatArray(cursor)
        }
        buildPositions.copyInto(positions, 0, 0, cursor)
        computeNormals()
    }

    private fun buildIndices() {
        val idx = ArrayList<Short>()
        var base = 0
        val s = segments
        for (rings in partRings) {
            for (k in 0 until rings - 1) for (i in 0 until s) {
                val i2 = (i + 1) % s
                val v00 = base + k * s + i; val v01 = base + k * s + i2
                val v10 = base + (k + 1) * s + i; val v11 = base + (k + 1) * s + i2
                idx += v00.toShort(); idx += v01.toShort(); idx += v11.toShort()
                idx += v00.toShort(); idx += v11.toShort(); idx += v10.toShort()
            }
            base += rings * s
        }
        check(base < 65536) { "Too many vertices for 16-bit indices" }
        indices = idx.toShortArray()
    }

    /** Smooth normals from area-weighted face normals. */
    private fun computeNormals() {
        java.util.Arrays.fill(normals, 0f)
        val p = positions
        for (t in indices.indices step 3) {
            val a = (indices[t].toInt() and 0xFFFF) * 3
            val b = (indices[t + 1].toInt() and 0xFFFF) * 3
            val c = (indices[t + 2].toInt() and 0xFFFF) * 3
            val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
            val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
            val nx = e1y * e2z - e1z * e2y; val ny = e1z * e2x - e1x * e2z; val nz = e1x * e2y - e1y * e2x
            for (v in intArrayOf(a, b, c)) { normals[v] += nx; normals[v + 1] += ny; normals[v + 2] += nz }
        }
        for (v in normals.indices step 3) {
            val l = kotlin.math.sqrt(normals[v] * normals[v] + normals[v + 1] * normals[v + 1] + normals[v + 2] * normals[v + 2])
            if (l > 1e-12f) { normals[v] /= l; normals[v + 1] /= l; normals[v + 2] /= l } else normals[v + 1] = 1f
        }
    }

    private fun ensure(size: Int) {
        if (size > buildPositions.size) buildPositions = buildPositions.copyOf(maxOf(size, buildPositions.size * 2))
    }

    private fun catmullRom(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Float): Vec3 {
        val t2 = t * t; val t3 = t2 * t
        return (p1 * 2f + (p2 - p0) * t + (p0 * 2f - p1 * 5f + p2 * 4f - p3) * t2 + (p1 * 3f - p0 - p2 * 3f + p3) * t3) * 0.5f
    }
}
