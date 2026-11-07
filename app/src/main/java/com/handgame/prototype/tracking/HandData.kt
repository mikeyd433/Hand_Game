package com.handgame.prototype.tracking

import com.handgame.prototype.math.Vec3
import kotlin.math.hypot

/** MediaPipe hand landmark indices. */
object Lm {
    const val COUNT = 21
    const val WRIST = 0
    const val THUMB_CMC = 1; const val THUMB_MCP = 2; const val THUMB_IP = 3; const val THUMB_TIP = 4
    const val INDEX_MCP = 5; const val INDEX_PIP = 6; const val INDEX_DIP = 7; const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9; const val MIDDLE_PIP = 10; const val MIDDLE_DIP = 11; const val MIDDLE_TIP = 12
    const val RING_MCP = 13; const val RING_PIP = 14; const val RING_DIP = 15; const val RING_TIP = 16
    const val PINKY_MCP = 17; const val PINKY_PIP = 18; const val PINKY_DIP = 19; const val PINKY_TIP = 20

    val FINGER_TIPS = intArrayOf(THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
    val PALM_POINTS = intArrayOf(WRIST, INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP)

    /** Bones drawn as capsules. */
    val BONES: Array<IntArray> = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
        intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
        intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
        intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
        intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
        // Palm frame.
        intArrayOf(0, 5), intArrayOf(5, 9), intArrayOf(9, 13), intArrayOf(13, 17), intArrayOf(0, 17),
        intArrayOf(0, 9), intArrayOf(0, 13), intArrayOf(1, 5),
    )
}

enum class Handedness { LEFT, RIGHT, UNKNOWN }

/**
 * One detected hand as MediaPipe reports it, for an upright (rotation-corrected) but NOT mirrored
 * camera image.
 *
 * @param image 21 × (u, v) normalized image coordinates, u right, v down.
 * @param world 21 × (x, y, z) metric landmarks in MediaPipe's camera axes (x right, y down,
 *   z away from the camera), origin near the hand's center.
 * @param mpLabel MediaPipe's handedness label. MediaPipe assumes a mirrored (selfie) image, so for
 *   our un-mirrored images the label names the opposite physical hand.
 */
class RawHand(
    val image: FloatArray,
    val world: FloatArray,
    val mpLabel: String?,
    val confidence: Float,
)

/** One camera frame's tracking result. [hand] is null when no hand was found. */
class TrackingSample(
    val timestampNs: Long,
    val hand: RawHand?,
    val imageWidth: Int,
    val imageHeight: Int,
    val frontCamera: Boolean,
    val inferenceMs: Float,
)

/**
 * Converts a [RawHand] into game-friendly measurements: mirrored screen-space position, apparent
 * size (for depth), and the 3D hand shape rotated into game axes.
 */
object HandMeasure {

    /** Normalized palm center (u, v) as the player perceives it (front camera is mirrored). */
    fun palmCenterUv(hand: RawHand, frontCamera: Boolean): Pair<Float, Float> {
        var u = 0f; var v = 0f
        for (i in Lm.PALM_POINTS) { u += hand.image[i * 2]; v += hand.image[i * 2 + 1] }
        u /= Lm.PALM_POINTS.size; v /= Lm.PALM_POINTS.size
        return Pair(if (frontCamera) 1f - u else u, v)
    }

    /**
     * Apparent hand size in the image, as a fraction of the image's shorter side. Mixes palm length
     * (wrist → middle knuckle) and knuckle width so it is less sensitive to hand pitch and roll.
     */
    fun apparentSize(hand: RawHand, width: Int, height: Int): Float {
        val minSide = minOf(width, height).toFloat()
        fun dist(a: Int, b: Int): Float {
            val dx = (hand.image[a * 2] - hand.image[b * 2]) * width
            val dy = (hand.image[a * 2 + 1] - hand.image[b * 2 + 1]) * height
            return hypot(dx, dy) / minSide
        }
        val length = dist(Lm.WRIST, Lm.MIDDLE_MCP)
        val width2 = dist(Lm.INDEX_MCP, Lm.PINKY_MCP) * 1.25f
        return 0.5f * (length + width2)
    }

    /** Metric palm length (wrist → middle knuckle) from world landmarks. */
    fun worldPalmLength(hand: RawHand): Float = worldPoint(hand, Lm.WRIST).distance(worldPoint(hand, Lm.MIDDLE_MCP))

    fun worldPoint(hand: RawHand, i: Int) = Vec3(hand.world[i * 3], hand.world[i * 3 + 1], hand.world[i * 3 + 2])

    /**
     * Rotates MediaPipe's camera-axis landmarks into game axes (x right, y up, z toward viewer).
     *
     * Both mappings are proper rotations, so a left hand stays a left hand. They are chosen so the
     * natural pose for each camera shows up as a palm-down hand reaching into the scene:
     *  - Back camera: hand behind the phone, palm facing away from the player, fingers up.
     *  - Front camera: hand in front of the screen, palm facing the screen, fingers up. The x flip
     *    also gives the mirror behaviour players expect (move right → on-screen hand moves right).
     */
    fun toGameAxes(mx: Float, my: Float, mz: Float, frontCamera: Boolean): Vec3 =
        if (frontCamera) Vec3(-mx, mz, my) else Vec3(mx, -mz, my)

    /** Hand shape in game axes, centered on the palm center, in meters. */
    fun gameShape(hand: RawHand, frontCamera: Boolean, out: Array<Vec3>) {
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in Lm.PALM_POINTS) { cx += hand.world[i * 3]; cy += hand.world[i * 3 + 1]; cz += hand.world[i * 3 + 2] }
        val n = Lm.PALM_POINTS.size.toFloat()
        cx /= n; cy /= n; cz /= n
        for (i in 0 until Lm.COUNT) {
            out[i] = toGameAxes(hand.world[i * 3] - cx, hand.world[i * 3 + 1] - cy, hand.world[i * 3 + 2] - cz, frontCamera)
        }
    }

    /** The physical hand, given MediaPipe's label on an un-mirrored image. */
    fun physicalHandedness(mpLabel: String?): Handedness = when (mpLabel?.lowercase()) {
        "left" -> Handedness.RIGHT
        "right" -> Handedness.LEFT
        else -> Handedness.UNKNOWN
    }
}

/** Majority vote over recent frames so the handedness label doesn't flicker. */
class HandednessVoter(private val window: Int = 20) {
    private val history = ArrayDeque<Handedness>()

    fun reset() = history.clear()

    fun add(h: Handedness): Handedness {
        if (h != Handedness.UNKNOWN) {
            history.addLast(h)
            while (history.size > window) history.removeFirst()
        }
        val left = history.count { it == Handedness.LEFT }
        val right = history.size - left
        return when {
            history.isEmpty() -> Handedness.UNKNOWN
            left > right -> Handedness.LEFT
            else -> Handedness.RIGHT
        }
    }
}
