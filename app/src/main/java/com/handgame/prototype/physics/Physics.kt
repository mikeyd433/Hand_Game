package com.handgame.prototype.physics

import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** A solid box rigid body. */
class RigidBox(val halfExtents: Vec3, mass: Float) {
    var position = Vec3.ZERO
    var orientation = Quat.IDENTITY
    var linearVelocity = Vec3.ZERO
    var angularVelocity = Vec3.ZERO

    val invMass = 1f / mass
    private val invInertiaBody: Vec3

    /** Held bodies ignore gravity and are steered by [PhysicsWorld.drive]. */
    var held = false
    var sleeping = false
        private set
    private var sleepTimer = 0f

    /** True if the box touched the table or floor during the last step. */
    var grounded = false
        internal set

    init {
        val w = halfExtents.x * 2; val h = halfExtents.y * 2; val d = halfExtents.z * 2
        invInertiaBody = Vec3(
            12f / (mass * (h * h + d * d)),
            12f / (mass * (w * w + d * d)),
            12f / (mass * (w * w + h * h)),
        )
    }

    fun wake() { sleeping = false; sleepTimer = 0f }

    /** World-space inverse inertia tensor applied to [v]: R · I⁻¹ · Rᵀ · v. */
    fun applyInvInertia(v: Vec3): Vec3 {
        val local = orientation.conjugate().rotate(v)
        val scaled = Vec3(local.x * invInertiaBody.x, local.y * invInertiaBody.y, local.z * invInertiaBody.z)
        return orientation.rotate(scaled)
    }

    fun corner(i: Int): Vec3 {
        val local = Vec3(
            if (i and 1 == 0) -halfExtents.x else halfExtents.x,
            if (i and 2 == 0) -halfExtents.y else halfExtents.y,
            if (i and 4 == 0) -halfExtents.z else halfExtents.z,
        )
        return position + orientation.rotate(local)
    }

    /** Distance from [p] to the box surface (0 if inside). */
    fun distanceTo(p: Vec3): Float {
        val local = orientation.conjugate().rotate(p - position)
        val dx = (abs(local.x) - halfExtents.x).coerceAtLeast(0f)
        val dy = (abs(local.y) - halfExtents.y).coerceAtLeast(0f)
        val dz = (abs(local.z) - halfExtents.z).coerceAtLeast(0f)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    internal fun updateSleep(dt: Float) {
        if (held) { wake(); return }
        val calm = linearVelocity.lengthSq() < 0.03f * 0.03f && angularVelocity.lengthSq() < 0.15f * 0.15f
        if (calm && grounded) {
            sleepTimer += dt
            if (sleepTimer > 0.4f) {
                sleeping = true
                linearVelocity = Vec3.ZERO
                angularVelocity = Vec3.ZERO
            }
        } else {
            sleepTimer = 0f
        }
    }
}

/**
 * Minimal rigid-body world for the prototype: boxes resting on a finite table top and an infinite
 * floor below it. Uses fixed-step sequential impulses on box corners with Coulomb friction,
 * restitution, positional correction and sleeping (so a resting cube has zero jitter).
 */
class PhysicsWorld(
    val tableHalfX: Float,
    val tableHalfZ: Float,
    val tableTopY: Float = 0f,
    val floorY: Float = -0.75f,
) {
    val bodies = ArrayList<RigidBox>()
    var gravity = Vec3(0f, -9.81f, 0f)
    var friction = 0.6f
    var restitution = 0.25f
    var fixedStep = 1f / 240f
    var iterations = 10

    private var accumulator = 0f

    private class Contact(val r: Vec3, val depth: Float) {
        var normalImpulse = 0f
        var tangentImpulse1 = 0f
        var tangentImpulse2 = 0f
        var bounceTarget = 0f
    }

    /** Advances by [dt] using as many fixed sub-steps as needed (capped to avoid spirals). */
    fun step(dt: Float, beforeSubstep: ((Float) -> Unit)? = null) {
        accumulator = (accumulator + dt).coerceAtMost(fixedStep * 16)
        while (accumulator >= fixedStep) {
            beforeSubstep?.invoke(fixedStep)
            for (b in bodies) stepBody(b, fixedStep)
            accumulator -= fixedStep
        }
    }

    /**
     * Steers a held body toward a target pose by setting its velocities (a "velocity drive"), so it
     * still collides correctly with the table instead of teleporting through it.
     */
    fun drive(b: RigidBox, targetPos: Vec3, targetRot: Quat, dt: Float, stiffness: Float = 0.5f) {
        b.wake()
        b.linearVelocity = ((targetPos - b.position) * (stiffness / dt)).clampLength(12f)
        val dq = (targetRot * b.orientation.conjugate()).normalized()
        b.angularVelocity = (dq.toRotationVector() * (stiffness / dt)).clampLength(30f)
    }

    private fun stepBody(b: RigidBox, dt: Float) {
        if (b.sleeping) return

        if (!b.held) b.linearVelocity += gravity * dt

        val contacts = gatherContacts(b)
        b.grounded = contacts.isNotEmpty()

        val n = Vec3.UP
        // Restitution targets from the pre-solve approach speed.
        for (c in contacts) {
            val vn = (b.linearVelocity + (b.angularVelocity cross c.r)) dot n
            c.bounceTarget = if (vn < -0.6f) -restitution * vn else 0f
        }

        repeat(iterations) {
            for (c in contacts) solveContact(b, c, n)
        }

        // Integrate.
        b.position += b.linearVelocity * dt
        val w = b.angularVelocity
        val q = b.orientation
        val spin = Quat(w.x, w.y, w.z, 0f) * q
        b.orientation = Quat(
            q.x + 0.5f * dt * spin.x, q.y + 0.5f * dt * spin.y,
            q.z + 0.5f * dt * spin.z, q.w + 0.5f * dt * spin.w,
        ).normalized()

        // Positional correction: push the box out along the normal by the deepest penetration.
        var maxDepth = 0f
        for (i in 0 until 8) maxDepth = maxOf(maxDepth, penetration(b.corner(i)))
        if (maxDepth > SLOP) b.position += n * ((maxDepth - SLOP) * 0.8f)

        // Gentle damping (air drag + rolling resistance).
        b.linearVelocity *= (1f - 0.05f * dt)
        b.angularVelocity *= (1f - if (b.grounded) 2.0f * dt else 0.1f * dt)

        b.updateSleep(dt)
    }

    private fun gatherContacts(b: RigidBox): List<Contact> {
        val out = ArrayList<Contact>(4)
        for (i in 0 until 8) {
            val p = b.corner(i)
            val d = penetration(p)
            if (d > 0f) out.add(Contact(p - b.position, d))
        }
        return out
    }

    /** How far [p] is below whichever support surface is under it (≤ 0 means not touching). */
    fun penetration(p: Vec3): Float {
        val overTable = abs(p.x) <= tableHalfX && abs(p.z) <= tableHalfZ
        // Corners that are already well below the table top (fell past the edge) ignore the table.
        if (overTable && p.y > tableTopY - 0.06f) return tableTopY - p.y
        return floorY - p.y
    }

    private fun solveContact(b: RigidBox, c: Contact, n: Vec3) {
        val r = c.r
        // Normal.
        val vRel = b.linearVelocity + (b.angularVelocity cross r)
        val vn = vRel dot n
        val kN = b.invMass + (b.applyInvInertia(r cross n) cross r dot n)
        var jn = (c.bounceTarget - vn) / kN
        val old = c.normalImpulse
        c.normalImpulse = (old + jn).coerceAtLeast(0f)
        jn = c.normalImpulse - old
        applyImpulse(b, n * jn, r)

        // Friction along a fixed tangent basis (the normal is always +Y here), so accumulated
        // impulses stay consistent across iterations.
        val t1 = Vec3(1f, 0f, 0f)
        val t2 = Vec3(0f, 0f, 1f)
        val maxF = friction * c.normalImpulse
        c.tangentImpulse1 = solveTangent(b, r, t1, c.tangentImpulse1, maxF)
        c.tangentImpulse2 = solveTangent(b, r, t2, c.tangentImpulse2, maxF)
    }

    private fun solveTangent(b: RigidBox, r: Vec3, t: Vec3, accumulated: Float, maxF: Float): Float {
        val v = b.linearVelocity + (b.angularVelocity cross r)
        val k = b.invMass + (b.applyInvInertia(r cross t) cross r dot t)
        val j = -(v dot t) / k
        val next = (accumulated + j).coerceIn(-maxF, maxF)
        applyImpulse(b, t * (next - accumulated), r)
        return next
    }

    private fun applyImpulse(b: RigidBox, impulse: Vec3, r: Vec3) {
        b.linearVelocity += impulse * b.invMass
        b.angularVelocity += b.applyInvInertia(r cross impulse)
    }

    private companion object {
        const val SLOP = 0.002f
    }
}
