package com.handgame.prototype.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** CPU-side mesh: positions/normals (xyz), optional colors (rgba), 16-bit triangle indices. */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: ShortArray,
    val colors: FloatArray? = null,
) {
    val vertexCount get() = positions.size / 3

    /** Axis-aligned bounds as (center xyz, half-extent xyz). */
    fun bounds(): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertexCount) {
            val x = positions[i * 3]; val y = positions[i * 3 + 1]; val z = positions[i * 3 + 2]
            minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
            maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
        }
        return floatArrayOf(
            (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2,
            (maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2,
        )
    }
}

/** Procedural meshes for the toy-like scene. Everything is generated in code; no asset files. */
object MeshFactory {

    /** Unit UV sphere centered at the origin. */
    fun sphere(rings: Int = 16, segments: Int = 24): MeshData {
        val pos = ArrayList<Float>(); val idx = ArrayList<Short>()
        for (r in 0..rings) {
            val phi = PI * r / rings
            for (s in 0..segments) {
                val theta = 2 * PI * s / segments
                val x = (sin(phi) * cos(theta)).toFloat()
                val y = cos(phi).toFloat()
                val z = (sin(phi) * sin(theta)).toFloat()
                pos += x; pos += y; pos += z
            }
        }
        val stride = segments + 1
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = r * stride + s; val b = a + stride
            // Counter-clockwise when viewed from outside.
            idx += a.toShort(); idx += (a + 1).toShort(); idx += b.toShort()
            idx += (a + 1).toShort(); idx += (b + 1).toShort(); idx += b.toShort()
        }
        val p = pos.toFloatArray()
        return MeshData(p, p.copyOf(), idx.toShortArray())
    }

    /** Open cylinder of radius 1 along +Y from y=0 to y=1 (ends are capped by joint spheres). */
    fun cylinder(segments: Int = 20): MeshData {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val idx = ArrayList<Short>()
        for (s in 0..segments) {
            val theta = 2 * PI * s / segments
            val x = cos(theta).toFloat(); val z = sin(theta).toFloat()
            for (y in 0..1) {
                pos += x; pos += y.toFloat(); pos += z
                nrm += x; nrm += 0f; nrm += z
            }
        }
        for (s in 0 until segments) {
            val a = s * 2; val b = a + 1; val c = a + 2; val d = a + 3
            idx += a.toShort(); idx += b.toShort(); idx += c.toShort()
            idx += c.toShort(); idx += b.toShort(); idx += d.toShort()
        }
        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), idx.toShortArray())
    }

    /**
     * Box with rounded edges and corners: a subdivided cube whose vertices are pulled onto a
     * rounded surface (inner box of half-size h - r, inflated by r).
     */
    fun roundedBox(hx: Float, hy: Float, hz: Float, radius: Float, divisions: Int = 8): MeshData {
        val r = min(radius, min(hx, min(hy, hz)))
        val ix = hx - r; val iy = hy - r; val iz = hz - r
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val idx = ArrayList<Short>()

        // Each face: origin corner + two axes spanning it, in a [-1, 1] cube.
        val faces = arrayOf(
            floatArrayOf(1f, -1f, 1f, 0f, 0f, -2f, 0f, 2f, 0f),   // +X
            floatArrayOf(-1f, -1f, -1f, 0f, 0f, 2f, 0f, 2f, 0f),  // -X
            floatArrayOf(-1f, 1f, 1f, 2f, 0f, 0f, 0f, 0f, -2f),   // +Y
            floatArrayOf(-1f, -1f, -1f, 2f, 0f, 0f, 0f, 0f, 2f),  // -Y
            floatArrayOf(-1f, -1f, 1f, 2f, 0f, 0f, 0f, 2f, 0f),   // +Z
            floatArrayOf(1f, -1f, -1f, -2f, 0f, 0f, 0f, 2f, 0f),  // -Z
        )
        val n = divisions
        for (f in faces) {
            val base = pos.size / 3
            for (j in 0..n) for (i in 0..n) {
                // Cluster vertices near the edges where the curvature is.
                val u = edgeBias(i.toFloat() / n); val v = edgeBias(j.toFloat() / n)
                val cx = f[0] + f[3] * u + f[6] * v
                val cy = f[1] + f[4] * u + f[7] * v
                val cz = f[2] + f[5] * u + f[8] * v
                val px = cx * hx; val py = cy * hy; val pz = cz * hz
                val qx = px.coerceIn(-ix, ix); val qy = py.coerceIn(-iy, iy); val qz = pz.coerceIn(-iz, iz)
                var dx = px - qx; var dy = py - qy; var dz = pz - qz
                val len = sqrt(dx * dx + dy * dy + dz * dz)
                if (len > 1e-6f) { dx /= len; dy /= len; dz /= len } else {
                    // Flat face center: normal is the face normal.
                    val fn = faceNormal(f); dx = fn[0]; dy = fn[1]; dz = fn[2]
                }
                pos += qx + dx * r; pos += qy + dy * r; pos += qz + dz * r
                nrm += dx; nrm += dy; nrm += dz
            }
            for (j in 0 until n) for (i in 0 until n) {
                val a = base + j * (n + 1) + i
                val b = a + 1; val c = a + (n + 1); val d = c + 1
                idx += a.toShort(); idx += b.toShort(); idx += d.toShort()
                idx += a.toShort(); idx += d.toShort(); idx += c.toShort()
            }
        }
        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), idx.toShortArray())
    }

    private fun faceNormal(f: FloatArray): FloatArray {
        // cross(axisU, axisV), normalized.
        val ux = f[3]; val uy = f[4]; val uz = f[5]; val vx = f[6]; val vy = f[7]; val vz = f[8]
        val x = uy * vz - uz * vy; val y = uz * vx - ux * vz; val z = ux * vy - uy * vx
        val l = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / l, y / l, z / l)
    }

    private fun edgeBias(t: Float): Float {
        // Smooth remap that keeps 0 and 1 fixed but puts more samples near them.
        val s = t * 2f - 1f
        val biased = sin(s * PI.toFloat() / 2f)
        return (biased + 1f) / 2f
    }

    /**
     * Large inward-facing sphere with a vertical pastel gradient (vertex colors), used as a dreamy
     * backdrop.
     */
    fun gradientDome(radius: Float, top: FloatArray, horizon: FloatArray, bottom: FloatArray): MeshData {
        val base = sphere(24, 32)
        val pos = FloatArray(base.positions.size) { base.positions[it] * radius }
        val colors = FloatArray(base.vertexCount * 4)
        for (i in 0 until base.vertexCount) {
            val y = base.positions[i * 3 + 1]
            val c = if (y >= 0f) mix(horizon, top, smooth(y)) else mix(horizon, bottom, smooth(-y))
            colors[i * 4] = c[0]; colors[i * 4 + 1] = c[1]; colors[i * 4 + 2] = c[2]; colors[i * 4 + 3] = 1f
        }
        // Flip winding so the inside faces the camera.
        val idx = base.indices.copyOf()
        for (t in idx.indices step 3) { val tmp = idx[t + 1]; idx[t + 1] = idx[t + 2]; idx[t + 2] = tmp }
        val nrm = FloatArray(base.normals.size) { -base.normals[it] }
        return MeshData(pos, nrm, idx, colors)
    }

    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
    private fun mix(a: FloatArray, b: FloatArray, t: Float) =
        floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
}
