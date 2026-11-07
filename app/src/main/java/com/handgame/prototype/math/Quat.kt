package com.handgame.prototype.math

import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (x, y, z, w). */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {

    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun conjugate() = Quat(-x, -y, -z, w)

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l > 1e-8f) Quat(x / l, y / l, z / l, w / l) else IDENTITY
    }

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q×v) + 2q×(q×v)
        val q = Vec3(x, y, z)
        val t = (q cross v) * 2f
        return v + t * w + (q cross t)
    }

    /** Returns the 3x3 rotation matrix in row-major order. */
    fun toMat3(): FloatArray {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        )
    }

    /** Rotation vector (axis * angle) that takes [IDENTITY] to this quaternion along the short path. */
    fun toRotationVector(): Vec3 {
        val q = if (w < 0f) Quat(-x, -y, -z, -w) else this
        val s = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        if (s < 1e-6f) return Vec3(q.x * 2f, q.y * 2f, q.z * 2f)
        val angle = 2f * acos(q.w.coerceIn(-1f, 1f))
        return Vec3(q.x, q.y, q.z) * (angle / s)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun fromAxisAngle(axis: Vec3, angle: Float): Quat {
            val a = axis.normalized()
            val s = sin(angle / 2f)
            return Quat(a.x * s, a.y * s, a.z * s, kotlin.math.cos(angle / 2f))
        }

        /** Builds a rotation from orthonormal basis columns (right, up, back). */
        fun fromBasis(right: Vec3, up: Vec3, back: Vec3): Quat {
            val m00 = right.x; val m01 = up.x; val m02 = back.x
            val m10 = right.y; val m11 = up.y; val m12 = back.y
            val m20 = right.z; val m21 = up.z; val m22 = back.z
            val trace = m00 + m11 + m22
            val q = if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }
            return q.normalized()
        }

        /** Shortest-path spherical interpolation. */
        fun slerp(a: Quat, b0: Quat, t: Float): Quat {
            var b = b0
            var d = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
            if (d < 0f) { b = Quat(-b.x, -b.y, -b.z, -b.w); d = -d }
            if (d > 0.9995f) {
                return Quat(
                    a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
                    a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t,
                ).normalized()
            }
            val theta = acos(d)
            val sa = sin((1 - t) * theta) / sin(theta)
            val sb = sin(t * theta) / sin(theta)
            return Quat(a.x * sa + b.x * sb, a.y * sa + b.y * sb, a.z * sa + b.z * sb, a.w * sa + b.w * sb)
        }
    }
}
