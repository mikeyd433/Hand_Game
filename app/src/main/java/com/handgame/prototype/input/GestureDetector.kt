package com.handgame.prototype.input

import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.Lm

enum class Gesture { OPEN, PINCH, FIST }

/** Tunable gesture thresholds. All distances are in units of the hand's own palm length. */
data class GestureThresholds(
    // Tuned on guided recordings (Galaxy S24, front camera): pinches read 0.19–0.32 in one
    // session and 0.32–0.39 in another (p10–p90), while open hands stay ≥ 0.88; fists curl
    // 0.80–0.97 while every other pose stays ≥ 1.80.
    /** Thumb-tip to index-tip distance that starts a pinch. */
    val pinchEnter: Float = 0.45f,
    /** Distance that ends a pinch (larger than [pinchEnter] = hysteresis). */
    val pinchExit: Float = 0.62f,
    /** A finger counts as curled when tip→wrist / knuckle→wrist is below this. Fist needs all four. */
    val fistEnter: Float = 1.45f,
    val fistExit: Float = 1.65f,
    /** Consecutive frames a new grab gesture must be seen before it's accepted. */
    val enterFrames: Int = 2,
    /** Consecutive frames the hand must look open before a grab is released. */
    val exitFrames: Int = 2,
)

/**
 * Raw per-frame measurements, exposed for the debug overlay and tuning reports.
 * [pinchDistance] is the effective value used for detection; [pinch3d] / [pinch2d] are its inputs.
 */
data class GestureMetrics(val pinchDistance: Float, val maxCurl: Float, val pinch3d: Float = pinchDistance, val pinch2d: Float = Float.NaN)

/**
 * Detects OPEN / PINCH / FIST from 21 hand landmarks. Uses hysteresis (separate enter and exit
 * thresholds) plus a short frame debounce so grabs don't flicker. Distances are normalized by the
 * hand's palm length so they work for any hand size and distance from the camera.
 */
class GestureDetector(var thresholds: GestureThresholds = GestureThresholds()) {
    var state: Gesture = Gesture.OPEN
        private set
    var metrics = GestureMetrics(1f, 2f)
        private set

    /** Index finger out with the other fingers curled (for poking). Not a grab gesture. */
    var pointing = false
        private set

    private var candidate: Gesture = Gesture.OPEN
    private var candidateFrames = 0

    fun reset() {
        state = Gesture.OPEN
        candidate = Gesture.OPEN
        candidateFrames = 0
    }

    /**
     * @param pinch2d thumb-to-index distance measured flat in the camera image, relative to the
     *   hand's apparent size (see [com.handgame.prototype.tracking.HandMeasure.imagePinch]).
     *   MediaPipe's 3D depth for two touching fingertips seen face-on is often off by a lot, so a
     *   pinch that clearly overlaps in the image counts too, as long as the 3D distance agrees the
     *   fingers are at least fairly close.
     */
    fun update(joints: Array<Vec3>, pinch2d: Float = Float.NaN): Gesture {
        val palm = joints[Lm.WRIST].distance(joints[Lm.MIDDLE_MCP]).coerceAtLeast(1e-4f)
        val pinch3d = joints[Lm.THUMB_TIP].distance(joints[Lm.INDEX_TIP]) / palm
        val pinch = if (!pinch2d.isNaN() && pinch3d < PINCH_3D_GUARD) minOf(pinch3d, pinch2d * PINCH_2D_SCALE) else pinch3d

        var maxCurl = 0f
        var indexRatio = 0f
        var othersMax = 0f
        for ((k, pair) in FINGERS.withIndex()) {
            val (mcp, tip) = pair
            val ratio = joints[tip].distance(joints[Lm.WRIST]) /
                joints[mcp].distance(joints[Lm.WRIST]).coerceAtLeast(1e-4f)
            if (ratio > maxCurl) maxCurl = ratio
            if (k == 0) indexRatio = ratio else othersMax = maxOf(othersMax, ratio)
        }
        pointing = if (pointing) indexRatio > POINT_INDEX_EXIT && othersMax < POINT_OTHERS_EXIT
        else indexRatio > POINT_INDEX_ENTER && othersMax < POINT_OTHERS_ENTER
        metrics = GestureMetrics(pinch, maxCurl, pinch3d, pinch2d)

        val t = thresholds
        // Instantaneous classification, with the current state widening its own band (hysteresis).
        val fist = if (state == Gesture.FIST) maxCurl < t.fistExit else maxCurl < t.fistEnter
        val pinching = if (state == Gesture.PINCH) pinch < t.pinchExit else pinch < t.pinchEnter
        val observed = when {
            fist -> Gesture.FIST
            pinching -> Gesture.PINCH
            else -> Gesture.OPEN
        }

        if (observed == state) {
            candidateFrames = 0
            candidate = state
            return state
        }
        if (observed == candidate) candidateFrames++ else { candidate = observed; candidateFrames = 1 }
        val needed = if (observed == Gesture.OPEN) t.exitFrames else t.enterFrames
        if (candidateFrames >= needed) {
            state = observed
            candidateFrames = 0
        }
        return state
    }

    companion object {
        /** 2D pinch is ~half the 3D value for the same finger gap; this puts both on one scale. */
        const val PINCH_2D_SCALE = 2.0f
        /** The 2D pinch only counts when the 3D estimate is at least this close (rules out a thumb
         *  that merely crosses in front of the index finger in the camera's view). */
        const val PINCH_3D_GUARD = 0.75f
        const val POINT_INDEX_ENTER = 1.65f; const val POINT_INDEX_EXIT = 1.5f
        const val POINT_OTHERS_ENTER = 1.4f; const val POINT_OTHERS_EXIT = 1.55f
        private val FINGERS = listOf(
            Lm.INDEX_MCP to Lm.INDEX_TIP,
            Lm.MIDDLE_MCP to Lm.MIDDLE_TIP,
            Lm.RING_MCP to Lm.RING_TIP,
            Lm.PINKY_MCP to Lm.PINKY_TIP,
        )
    }
}
