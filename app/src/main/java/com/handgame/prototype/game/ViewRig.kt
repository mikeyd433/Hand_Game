package com.handgame.prototype.game

import com.handgame.prototype.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The scene camera, kept in plain Kotlin so both the renderer and the game logic share it.
 *
 * [fit] frames the table for the current screen shape. [screenToPlane] turns a screen position
 * into a point in the world, which lets the hand's movement range cover the whole screen.
 */
class ViewRig {
    var verticalFovDeg = 46.0
        private set
    var eye = Vec3(0f, 2f, 1f)
        private set
    var target = Vec3.ZERO
        private set
    var aspect = 9f / 19.5f
        private set

    private var forward = Vec3(0f, 0f, -1f)
    private var right = Vec3(1f, 0f, 0f)
    private var up = Vec3.UP
    private var tanY = 0f
    private var tanX = 0f

    init { fit(aspect) }

    /**
     * Places the camera at a fixed downward tilt and the closest distance that keeps the whole
     * table on screen, leaving room for the buttons along the top edge.
     */
    fun fit(newAspect: Float) {
        aspect = newAspect.coerceIn(0.3f, 3f)
        tanY = tan(verticalFovDeg / 2 * PI / 180).toFloat()
        tanX = tanY * aspect
        val pitch = (PITCH_DEG * PI / 180).toFloat()
        val dir = Vec3(0f, -sin(pitch), -cos(pitch))

        var tz = 0f
        var distance = 3f
        repeat(8) {
            // Closest distance at which every table corner fits within the margins.
            var lo = 0.5f; var hi = 12f
            repeat(30) {
                val mid = (lo + hi) / 2
                place(Vec3(0f, 0f, tz), dir, mid)
                if (cornersFit()) hi = mid else lo = mid
            }
            distance = hi
            place(Vec3(0f, 0f, tz), dir, distance)
            // Re-center the table vertically within the usable band.
            val (minY, maxY) = cornerNdcYRange()
            val mid = (minY + maxY) / 2
            val desired = (NDC_TOP + NDC_BOTTOM) / 2
            tz -= (mid - desired) * PlaySpace.TABLE_HALF_Z * 0.5f
        }
        place(Vec3(0f, 0f, tz), dir, distance)
    }

    private fun place(t: Vec3, dir: Vec3, distance: Float) {
        target = t
        eye = t - dir * distance
        forward = dir
        right = (forward cross Vec3.UP).normalized()
        up = right cross forward
    }

    /** Normalized device coordinates (x right, y up, both −1..1) of a world point. */
    fun project(p: Vec3): Pair<Float, Float> {
        val d = p - eye
        val z = d dot forward
        return Pair((d dot right) / (z * tanX), (d dot up) / (z * tanY))
    }

    /** Where the ray through screen position (ndcX, ndcY) hits the horizontal plane y = [planeY]. */
    fun screenToPlane(ndcX: Float, ndcY: Float, planeY: Float): Vec3? {
        val dir = (forward + right * (ndcX * tanX) + up * (ndcY * tanY)).normalized()
        if (dir.y > -1e-4f) return null
        val t = (planeY - eye.y) / dir.y
        return eye + dir * t
    }

    private fun corners() = listOf(
        Vec3(-PlaySpace.TABLE_HALF_X, 0f, -PlaySpace.TABLE_HALF_Z),
        Vec3(PlaySpace.TABLE_HALF_X, 0f, -PlaySpace.TABLE_HALF_Z),
        Vec3(-PlaySpace.TABLE_HALF_X, 0f, PlaySpace.TABLE_HALF_Z),
        Vec3(PlaySpace.TABLE_HALF_X, 0f, PlaySpace.TABLE_HALF_Z),
    )

    private fun cornersFit() = corners().all { c ->
        val (x, y) = project(c)
        x in -NDC_SIDE..NDC_SIDE && y in NDC_BOTTOM..NDC_TOP
    }

    private fun cornerNdcYRange(): Pair<Float, Float> {
        val ys = corners().map { project(it).second }
        return Pair(ys.min(), ys.max())
    }

    companion object {
        /** Camera tilt below horizontal: mostly top-down, with enough angle to read depth. */
        const val PITCH_DEG = 62.0
        const val NDC_SIDE = 0.97f
        const val NDC_TOP = 0.82f   // leaves space for the top buttons
        const val NDC_BOTTOM = -0.95f
    }
}
