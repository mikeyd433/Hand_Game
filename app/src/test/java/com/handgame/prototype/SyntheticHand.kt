package com.handgame.prototype

import com.handgame.prototype.input.Gesture
import com.handgame.prototype.tracking.Lm
import com.handgame.prototype.tracking.RawHand
import com.handgame.prototype.tracking.TrackingSample

/**
 * Builds plausible MediaPipe-style hands for tests: an upright hand facing the camera, fingers up,
 * in MediaPipe camera axes (x right, y down, z away from camera, meters).
 */
object SyntheticHand {
    private val fingerX = floatArrayOf(0.03f, 0.01f, -0.01f, -0.03f) // index, middle, ring, pinky

    fun world(g: Gesture, pointing: Boolean = false): FloatArray {
        if (pointing) {
            // Fist with the index finger straight out.
            val fist = world(Gesture.FIST); val open = world(Gesture.OPEN)
            for (i in 5..8) for (k in 0..2) fist[i * 3 + k] = open[i * 3 + k]
            return fist
        }
        val p = Array(Lm.COUNT) { floatArrayOf(0f, 0f, 0f) }
        p[Lm.WRIST] = floatArrayOf(0f, 0.09f, 0f)
        // Thumb on the +x side.
        p[Lm.THUMB_CMC] = floatArrayOf(0.035f, 0.07f, -0.005f)
        p[Lm.THUMB_MCP] = floatArrayOf(0.05f, 0.045f, -0.01f)
        p[Lm.THUMB_IP] = floatArrayOf(0.065f, 0.025f, -0.015f)
        p[Lm.THUMB_TIP] = floatArrayOf(0.08f, 0.005f, -0.02f)
        for (f in 0 until 4) {
            val mcp = 5 + f * 4
            val x = fingerX[f]
            p[mcp] = floatArrayOf(x, 0f, 0f)
            when (g) {
                Gesture.OPEN -> {
                    p[mcp + 1] = floatArrayOf(x, -0.04f, 0f)
                    p[mcp + 2] = floatArrayOf(x, -0.065f, 0f)
                    p[mcp + 3] = floatArrayOf(x, -0.085f, 0f)
                }
                Gesture.FIST -> {
                    p[mcp + 1] = floatArrayOf(x, -0.025f, -0.025f)
                    p[mcp + 2] = floatArrayOf(x, 0.0f, -0.04f)
                    p[mcp + 3] = floatArrayOf(x, 0.03f, -0.03f)
                }
                Gesture.PINCH -> if (f == 0) {
                    p[mcp + 1] = floatArrayOf(0.04f, -0.035f, -0.01f)
                    p[mcp + 2] = floatArrayOf(0.055f, -0.025f, -0.02f)
                    p[mcp + 3] = floatArrayOf(0.075f, -0.0f, -0.02f)
                } else {
                    p[mcp + 1] = floatArrayOf(x, -0.04f, 0f)
                    p[mcp + 2] = floatArrayOf(x, -0.065f, 0f)
                    p[mcp + 3] = floatArrayOf(x, -0.085f, 0f)
                }
            }
        }
        if (g == Gesture.FIST) {
            p[Lm.THUMB_IP] = floatArrayOf(0.04f, 0.02f, -0.04f)
            p[Lm.THUMB_TIP] = floatArrayOf(0.02f, 0.01f, -0.05f)
        }
        return FloatArray(Lm.COUNT * 3) { p[it / 3][it % 3] }
    }

    /**
     * @param u,v palm position in the (un-mirrored) image.
     * @param scale image units per meter: larger = closer to the camera.
     */
    fun hand(g: Gesture, u: Float = 0.5f, v: Float = 0.5f, scale: Float = 2.5f, pointing: Boolean = false): RawHand {
        val w = world(g, pointing)
        val img = FloatArray(Lm.COUNT * 2)
        // Palm center in world (average of wrist + MCPs) maps to (u, v).
        var cx = 0f; var cy = 0f
        for (i in Lm.PALM_POINTS) { cx += w[i * 3]; cy += w[i * 3 + 1] }
        cx /= 5; cy /= 5
        for (i in 0 until Lm.COUNT) {
            img[i * 2] = u + (w[i * 3] - cx) * scale * 0.75f // 480/640 aspect
            img[i * 2 + 1] = v + (w[i * 3 + 1] - cy) * scale
        }
        return RawHand(img, w, "Left", 0.97f)
    }

    private var clockNs = 1_000_000_000L

    fun sample(hand: RawHand?, front: Boolean = true, dtSec: Float = 1f / 30f): TrackingSample {
        clockNs += (dtSec * 1e9).toLong()
        return TrackingSample(clockNs, hand, 480, 640, front, 12f)
    }
}
