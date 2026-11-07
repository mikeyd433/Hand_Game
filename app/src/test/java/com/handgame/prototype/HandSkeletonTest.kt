package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HandLook
import com.handgame.prototype.game.HandRig
import com.handgame.prototype.game.HandSkeleton
import com.handgame.prototype.game.ViewRig
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.Lm
import com.handgame.prototype.tracking.RawHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HandSkeletonTest {
    private fun bones(j: Array<Vec3>) = Lm.BONES.take(16).map { j[it[0]].distance(j[it[1]]) }

    @Test fun boneLengthsNeverChangeEvenWithGarbageInput() {
        val rnd = Random(3)
        val reference = Array(Lm.COUNT) { Vec3.ZERO }
        HandSkeleton.pose(HandSkeleton.restAngles(true), true, 1f, reference)
        val refBones = bones(reference)
        repeat(200) {
            val raw = Array(Lm.COUNT) { Vec3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) }
            val angles = FloatArray(HandSkeleton.SIZE)
            val rot = Quat.fromAxisAngle(Vec3(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()), rnd.nextFloat() * 6f)
            HandSkeleton.extract(raw, rot, rnd.nextBoolean(), angles)
            val out = Array(Lm.COUNT) { Vec3.ZERO }
            HandSkeleton.pose(angles, true, 1f, out)
            assertTrue(out.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
            bones(out).zip(refBones).forEach { (a, b) -> assertEquals(b, a, 1e-4f) }
        }
    }

    @Test fun fingersNeverBendBackwards() {
        // A finger bent "backwards" (toward the back of the hand) in the input stays straight.
        val raw = Array(Lm.COUNT) { Vec3.ZERO }
        HandSkeleton.pose(HandSkeleton.restAngles(true), true, 1f, raw)
        raw[Lm.INDEX_TIP] = raw[Lm.INDEX_DIP] + Vec3(0f, 0.3f, 0f) // tip flipped up
        val a = FloatArray(HandSkeleton.SIZE)
        HandSkeleton.extract(raw, Quat.IDENTITY, true, a)
        for (f in 0 until 4) for (k in 1..3) assertTrue(a[f * 4 + k] >= -0.36f)
        for (f in 0 until 4) for (k in 2..3) assertTrue(a[f * 4 + k] >= 0f)
    }

    @Test fun tracksOpenAndFist() {
        val rig = HandRig(); val s = GameSettings(); rig.applySettings(s)
        fun curl(g: Gesture): Float {
            rig.reset()
            var t = 0.0
            repeat(60) { t += 1 / 30.0; rig.update(SyntheticHand.hand(g), true, 480, 640, GameController.DEFAULT_CALIBRATION, s, true, ViewRig(), t) }
            val j = rig.pose.joints
            return j[Lm.MIDDLE_TIP].distance(j[Lm.WRIST]) / j[Lm.MIDDLE_MCP].distance(j[Lm.WRIST])
        }
        assertTrue("open hand should be extended", curl(Gesture.OPEN) > 1.6f)
        assertTrue("fist should be curled", curl(Gesture.FIST) < 1.2f)
    }

    @Test fun pinchBringsTipsTogether() {
        val rig = HandRig(); val s = GameSettings(); rig.applySettings(s)
        var t = 0.0
        repeat(60) { t += 1 / 30.0; rig.update(SyntheticHand.hand(Gesture.PINCH), true, 480, 640, GameController.DEFAULT_CALIBRATION, s, true, ViewRig(), t) }
        val j = rig.pose.joints
        val gap = j[Lm.THUMB_TIP].distance(j[Lm.INDEX_TIP])
        assertTrue("tips should touch, gap=$gap", gap < (HandLook.jointRadius(Lm.THUMB_TIP) + HandLook.jointRadius(Lm.INDEX_TIP)) * 1.3f)
    }

    @Test fun screenEdgeIsReachedBeforeTheHandLeavesTheCamera() {
        // A calibration centered high in the frame: reaching the top of the screen must not need
        // the fingers to go past the top of the camera image.
        // Calibrated like the app does: including the open hand's measured reach.
        val reach = com.handgame.prototype.tracking.HandMeasure.extents(SyntheticHand.hand(Gesture.OPEN), true).toList()
        val cal = GameController.DEFAULT_CALIBRATION.copy(centerV = 0.38f, rangeV = 0.3f, extents = reach)
        val rig = HandRig(); val s = GameSettings(); rig.applySettings(s)
        val view = ViewRig()
        var reachedTop = Float.NaN
        for (step in 0..60) {
            val v = 0.38f - step * 0.005f
            val hand = SyntheticHand.hand(Gesture.OPEN, v = v)
            rig.reset()
            rig.update(hand, true, 480, 640, cal, s, true, view, 0.0)
            val c = rig.pose.palmCenter
            val ndcY = view.project(Vec3(c.x, 0.1f, c.z)).second
            if (ndcY > 0.77f) { reachedTop = (0 until Lm.COUNT).minOf { hand.image[it * 2 + 1] }; break }
        }
        assertTrue("top of screen should be reachable", !reachedTop.isNaN())
        assertTrue("fingers still in frame when reaching the top (min v=$reachedTop)", reachedTop > 0f)
    }

    @Test fun warnsNearTheFrameEdge() {
        val rig = HandRig(); val s = GameSettings(); rig.applySettings(s)
        rig.update(SyntheticHand.hand(Gesture.OPEN), true, 480, 640, GameController.DEFAULT_CALIBRATION, s, true, ViewRig(), 0.0)
        assertEquals(0f, rig.pose.edgeWarning, 1e-4f)
        // Fingers near the top of the image.
        val high: RawHand = SyntheticHand.hand(Gesture.OPEN, v = 0.26f)
        rig.update(high, true, 480, 640, GameController.DEFAULT_CALIBRATION, s, true, ViewRig(), 0.1)
        assertTrue(rig.pose.edgeWarning > 0.3f)
        assertEquals(-1, rig.pose.edgeY)
    }
}
