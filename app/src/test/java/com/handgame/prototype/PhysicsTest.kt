package com.handgame.prototype

import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.physics.PhysicsWorld
import com.handgame.prototype.physics.RigidBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsTest {
    private fun world(): Pair<PhysicsWorld, RigidBox> {
        val w = PhysicsWorld(0.6f, 0.4f)
        val b = RigidBox(Vec3(0.07f, 0.07f, 0.07f), 0.4f)
        w.bodies += b
        return w to b
    }

    private fun run(w: PhysicsWorld, seconds: Float) {
        repeat((seconds * 60).toInt()) { w.step(1f / 60f) }
    }

    @Test fun droppedCubeSettlesOnTableAndSleeps() {
        val (w, b) = world()
        b.position = Vec3(0f, 0.35f, 0f)
        b.orientation = Quat.fromAxisAngle(Vec3(0.3f, 1f, 0.2f), 0.5f)
        run(w, 4f)
        assertTrue("cube should be asleep", b.sleeping)
        assertEquals(0.07f, b.position.y, 0.006f)
        // No drift afterwards.
        val p = b.position
        run(w, 5f)
        assertEquals(0f, b.position.distance(p), 1e-6f)
    }

    @Test fun tiltedDropLandsFlat() {
        val (w, b) = world()
        b.position = Vec3(0.1f, 0.5f, 0.05f)
        b.orientation = Quat.fromAxisAngle(Vec3(1f, 0.2f, 0.4f), 0.9f)
        b.angularVelocity = Vec3(3f, 1f, -2f)
        run(w, 5f)
        // A face should be down: the up axis of the cube is aligned with some local axis.
        val axes = listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)).map { b.orientation.rotate(it) }
        val best = axes.maxOf { kotlin.math.abs(it.y) }
        assertTrue("cube should rest flat (best axis alignment $best)", best > 0.99f)
        assertEquals(0.07f, b.position.y, 0.008f)
    }

    @Test fun slidingCubeStopsFromFriction() {
        val (w, b) = world()
        b.position = Vec3(-0.3f, 0.071f, 0f)
        b.linearVelocity = Vec3(0.8f, 0f, 0f)
        run(w, 3f)
        assertTrue(b.linearVelocity.length() < 0.01f)
        assertTrue("should have slid but stayed on the table: ${b.position}", b.position.x > -0.3f && b.position.x < 0.6f)
    }

    @Test fun cubeThrownOffTableFallsToFloor() {
        val (w, b) = world()
        b.position = Vec3(0.5f, 0.2f, 0f)
        b.linearVelocity = Vec3(2f, 1f, 0f)
        run(w, 3f)
        assertTrue("should be on the floor: ${b.position}", b.position.y < -0.6f)
    }

    @Test fun driveMovesHeldBodyToTarget() {
        val (w, b) = world()
        b.position = Vec3(0f, 0.07f, 0f)
        b.held = true
        val target = Vec3(0.2f, 0.3f, -0.1f)
        val rot = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 1f)
        repeat(60) { w.step(1f / 60f) { dt -> w.drive(b, target, rot, dt) } }
        assertEquals(0f, b.position.distance(target), 0.01f)
    }
}
