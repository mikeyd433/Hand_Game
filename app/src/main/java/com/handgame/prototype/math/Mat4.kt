package com.handgame.prototype.math

/** Column-major 4x4 transforms in the layout Filament's TransformManager expects. */
object Mat4 {
    /** Translation * rotation * scale (scale is applied in the object's local axes). */
    fun trs(out: FloatArray, t: Vec3, r: Quat, sx: Float, sy: Float, sz: Float): FloatArray {
        val m = r.toMat3()
        out[0] = m[0] * sx; out[1] = m[3] * sx; out[2] = m[6] * sx; out[3] = 0f
        out[4] = m[1] * sy; out[5] = m[4] * sy; out[6] = m[7] * sy; out[7] = 0f
        out[8] = m[2] * sz; out[9] = m[5] * sz; out[10] = m[8] * sz; out[11] = 0f
        out[12] = t.x; out[13] = t.y; out[14] = t.z; out[15] = 1f
        return out
    }

    /**
     * Places a unit-height, unit-radius Y-axis cylinder (y from 0 to 1) so it spans a→b with [radius].
     */
    fun segment(out: FloatArray, a: Vec3, b: Vec3, radius: Float): FloatArray {
        val d = b - a
        val len = d.length()
        val yAxis = if (len > 1e-6f) d / len else Vec3.UP
        val helper = if (kotlin.math.abs(yAxis.y) < 0.95f) Vec3.UP else Vec3(1f, 0f, 0f)
        val xAxis = (helper cross yAxis).normalized()
        val zAxis = xAxis cross yAxis
        out[0] = xAxis.x * radius; out[1] = xAxis.y * radius; out[2] = xAxis.z * radius; out[3] = 0f
        out[4] = yAxis.x * len; out[5] = yAxis.y * len; out[6] = yAxis.z * len; out[7] = 0f
        out[8] = zAxis.x * radius; out[9] = zAxis.y * radius; out[10] = zAxis.z * radius; out[11] = 0f
        out[12] = a.x; out[13] = a.y; out[14] = a.z; out[15] = 1f
        return out
    }
}
