package com.handgame.prototype

import com.handgame.prototype.game.HandCollision
import com.handgame.prototype.game.HandLook
import com.handgame.prototype.game.HandSkeleton
import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.physics.RigidBox
import com.handgame.prototype.tracking.Lm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandCollisionTest {
    private fun restHand(): Array<Vec3> {
        val j = Array(Lm.COUNT) { Vec3.ZERO }
        HandSkeleton.pose(HandSkeleton.restAngles(true), true, HandLook.palmLength, j)
        return Array(Lm.COUNT) { j[it] + Vec3(0f, 0.15f, 0f) }
    }

    private fun boneLengths(j: Array<Vec3>) = Lm.BONES.take(16).map { j[it[0]].distance(j[it[1]]) }

    @Test fun crossedFingersArePushedApartKeepingBoneLengths() {
        val j = restHand()
        // Fold the middle finger's tip across onto the index finger's tip.
        j[Lm.MIDDLE_TIP] = j[Lm.INDEX_TIP] + Vec3(0.002f, 0.001f, 0f)
        j[Lm.MIDDLE_DIP] = Vec3.lerp(j[Lm.MIDDLE_PIP], j[Lm.MIDDLE_TIP], 0.5f)
        val lengths = boneLengths(j)
        val stats = HandCollision.Stats()
        HandCollision.solve(j, Quat.IDENTITY, null, null, stats)
        assertTrue("found the overlap", stats.maxDepth > 0.02f)
        assertTrue("resolved (residual ${stats.residual})", stats.residual < 0.004f)
        boneLengths(j).zip(lengths).forEach { (a, b) -> assertEquals(b, a, 1e-4f) }
    }

    @Test fun fingertipsDontSinkIntoThePalm() {
        val j = restHand()
        val center = (j[Lm.WRIST] + j[Lm.INDEX_MCP] + j[Lm.MIDDLE_MCP] + j[Lm.RING_MCP] + j[Lm.PINKY_MCP]) * 0.2f
        j[Lm.MIDDLE_TIP] = center + Vec3(0f, -0.005f, 0f) // curled right into the palm
        val stats = HandCollision.Stats()
        HandCollision.solve(j, Quat.IDENTITY, null, null, stats)
        assertTrue(stats.maxDepth > 0.02f)
        assertTrue("tip under the palm now (y=${j[Lm.MIDDLE_TIP].y})", j[Lm.MIDDLE_TIP].y < center.y - HandLook.PALM_HALF_THICKNESS)
    }

    @Test fun jointsStayAboveTheTable() {
        val j = Array(Lm.COUNT) { restHand()[it] - Vec3(0f, 0.15f, 0f) } // hand sunk into the table
        val stats = HandCollision.Stats()
        HandCollision.solve(j, Quat.IDENTITY, null, 0f, stats)
        for (i in 0 until Lm.COUNT) if (i != Lm.WRIST && i !in intArrayOf(1, 5, 9, 13, 17)) {
            assertTrue("joint $i at ${j[i].y}", j[i].y >= HandLook.jointRadius(i) * 0.85f)
        }
    }

    @Test fun fingerBonesDontCutThroughTheCube() {
        val j = restHand()
        val cube = RigidBox(Vec3(0.07f, 0.07f, 0.07f), 0.4f).apply {
            // Cube sitting across the middle of the index finger's middle bone.
            position = Vec3.lerp(j[Lm.INDEX_PIP], j[Lm.INDEX_DIP], 0.5f) + Vec3(0f, -0.075f, 0f)
        }
        val stats = HandCollision.Stats()
        HandCollision.solve(j, Quat.IDENTITY, cube, null, stats)
        assertTrue(stats.maxDepth > 0.005f)
        val mid = Vec3.lerp(j[Lm.INDEX_PIP], j[Lm.INDEX_DIP], 0.5f)
        assertTrue("bone clear of the cube (${cube.distanceTo(mid)})", cube.distanceTo(mid) > HandLook.jointRadius(Lm.INDEX_PIP) * 0.7f)
    }

    @Test fun relaxedHandHasNothingToFix() {
        val stats = HandCollision.Stats()
        HandCollision.solve(restHand(), Quat.IDENTITY, null, null, stats)
        assertEquals(0f, stats.maxDepth, 1e-4f)
    }
}
