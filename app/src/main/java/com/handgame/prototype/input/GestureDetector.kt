package com.handgame.prototype.input

import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.Lm

enum class Gesture { OPEN, PINCH, FIST }

/** Tunable gesture thresholds. All distances are in units of the hand's own palm length. */
data class GestureThresholds(
    /** Thumb-tip to index-tip distance that starts a pinch. */
    val pinchEnter: Float = 0.32f,
    /** Distance that ends a pinch (larger than [pinchEnter] = hysteresis). */
    val pinchExit: Float = 0.50f,
    /** A finger counts as curled when tip→wrist / knuckle→wrist is below this. Fist needs all four. */
    val fistEnter: Float = 1.30f,
    val fistExit: Float = 1.50f,
    /** Consecutive frames a new grab gesture must be seen before it's accepted. */
    val enterFrames: Int = 2,
    /** Consecutive frames the hand must look open before a grab is released. */
    val exitFrames: Int = 2,
)

/** Raw per-frame measurements, exposed for the debug overlay. */
data class GestureMetrics(val pinchDistance: Float, val maxCurl: Float)

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

    private var candidate: Gesture = Gesture.OPEN
    private var candidateFrames = 0

    fun reset() {
        state = Gesture.OPEN
        candidate = Gesture.OPEN
        candidateFrames = 0
    }

    fun update(joints: Array<Vec3>): Gesture {
        val palm = joints[Lm.WRIST].distance(joints[Lm.MIDDLE_MCP]).coerceAtLeast(1e-4f)
        val pinch = joints[Lm.THUMB_TIP].distance(joints[Lm.INDEX_TIP]) / palm

        var maxCurl = 0f
        for ((mcp, tip) in FINGERS) {
            val ratio = joints[tip].distance(joints[Lm.WRIST]) /
                joints[mcp].distance(joints[Lm.WRIST]).coerceAtLeast(1e-4f)
            if (ratio > maxCurl) maxCurl = ratio
        }
        metrics = GestureMetrics(pinch, maxCurl)

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

    private companion object {
        val FINGERS = listOf(
            Lm.INDEX_MCP to Lm.INDEX_TIP,
            Lm.MIDDLE_MCP to Lm.MIDDLE_TIP,
            Lm.RING_MCP to Lm.RING_TIP,
            Lm.PINKY_MCP to Lm.PINKY_TIP,
        )
    }
}
