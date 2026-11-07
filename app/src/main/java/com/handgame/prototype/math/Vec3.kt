package com.handgame.prototype.math

import kotlin.math.sqrt

/** Small immutable 3D vector. World space is right-handed, +Y up, -Z away from the viewer. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun lengthSq() = x * x + y * y + z * z
    fun length() = sqrt(lengthSq())
    fun normalized(): Vec3 {
        val l = length()
        return if (l > 1e-8f) this / l else ZERO
    }

    fun distance(o: Vec3) = (this - o).length()

    fun clampLength(max: Float): Vec3 {
        val l = length()
        return if (l > max && l > 0f) this * (max / l) else this
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        fun lerp(a: Vec3, b: Vec3, t: Float) = a + (b - a) * t
    }
}
