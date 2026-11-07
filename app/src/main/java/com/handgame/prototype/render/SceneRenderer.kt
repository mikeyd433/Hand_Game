package com.handgame.prototype.render

import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.HandLook
import com.handgame.prototype.game.Phase
import com.handgame.prototype.game.PlaySpace
import com.handgame.prototype.math.Mat4
import com.handgame.prototype.math.Quat
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.Lm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.sin

/**
 * Filament scene for the prototype: pastel backdrop, table, cube and a chunky cartoon hand built
 * from spheres (joints) and cylinders (bones). Drives the game loop from Choreographer so game
 * logic and rendering stay in lockstep with the display.
 */
class SceneRenderer(
    private val surfaceView: SurfaceView,
    private val game: GameController,
    private val onFrame: (dt: Float, frameTimeNanos: Long) -> Unit,
) : Choreographer.FrameCallback {

    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val camera: Camera
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(surfaceView.context)
    private var swapChain: SwapChain? = null
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNs = 0L

    private val toyMaterial: Material
    private val gradientMaterial: Material

    // GPU resources to free.
    private val vertexBuffers = ArrayList<VertexBuffer>()
    private val indexBuffers = ArrayList<IndexBuffer>()
    private val materialInstances = ArrayList<MaterialInstance>()
    private val entities = ArrayList<Int>()

    // Scene objects.
    /** One renderable per toy, created the first time the toy is seen. */
    private class ToyView(val entity: Int, val material: MaterialInstance) { var inScene = false }
    private val toyViews = HashMap<com.handgame.prototype.game.Toy, ToyView>()
    private val boxMeshes = HashMap<Vec3, Gpu>()
    private val ringEntity: Int
    private var ringInScene = false
    private val handMaterial: MaterialInstance
    private val cuffMaterial: MaterialInstance
    private val outlineMaterial: Material
    private val handMeshes = HandMeshBuilder()
    private val handBody: DynamicMesh
    private val handCuff: DynamicMesh
    private val handOutline: DynamicMesh
    private val outlinePositions: FloatArray

    // Critter: body/feet and mouth rebuilt each frame, eyes and pupils as spheres, hearts pool.
    private val critterMeshes = CritterMeshBuilder()
    private val critterBodyMat: MaterialInstance
    private val critterBody: DynamicMesh
    private val critterOutline: DynamicMesh
    private val critterMouth: DynamicMesh
    private val critterOutlinePos: FloatArray
    private val eyeEntities = IntArray(2)
    private val pupilEntities = IntArray(2)
    private val heartEntities = IntArray(10)
    private val heartInScene = BooleanArray(10)
    private var critterInScene = false
    private var pupilsInScene = false

    // Push buttons (small pool).
    private class ButtonView(val base: Int, val cap: Int, val capMat: MaterialInstance) { var inScene = false }
    private val buttonViews = ArrayList<ButtonView>()
    private val outlineNormals: FloatArray
    private var handInScene = false

    private val mat = FloatArray(16)
    private var timeSec = 0f

    private companion object {
        /** Cartoon outline thickness in world units (≈ 4–6 px on a phone at this framing). */
        const val OUTLINE_WIDTH = 0.0045f
    }

    private class Gpu(val vb: VertexBuffer, val ib: IndexBuffer, val indexCount: Int, val bounds: FloatArray)

    init {
        Filament.init()
        Materials.init()
        engine = Engine.Builder().backend(Engine.Backend.OPENGL).build()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(EntityManager.get().create())

        view.scene = scene
        view.camera = camera
        view.setShadowingEnabled(true)
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = true
            sampleCount = 4
        }
        view.colorGrading = ColorGrading.Builder()
            // PBR Neutral keeps authored colours faithful instead of washing pastels out.
            .toneMapper(ToneMapper.PBRNeutralToneMapper())
            .contrast(1.08f)
            .saturation(1.1f)
            .build(engine)
        camera.setExposure(1.0f)

        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(0.85, 0.82, 0.95, 1.0)
        }

        toyMaterial = Materials.toy(engine)
        gradientMaterial = Materials.gradient(engine)

        setupLights()

        // Backdrop.
        val dome = upload(
            MeshFactory.gradientDome(
                radius = 30f,
                // Dusk: deep indigo overhead, warm rose at the horizon, plum below. Darker than the
                // play objects so they stand out.
                top = floatArrayOf(0.13f, 0.11f, 0.32f),
                horizon = floatArrayOf(0.78f, 0.36f, 0.50f),
                bottom = floatArrayOf(0.20f, 0.10f, 0.30f),
            ),
            withTangents = false,
        )
        val domeMi = gradientMaterial.createInstance().also { materialInstances += it }
        addRenderable(dome, domeMi, castShadows = false, receiveShadows = false, priority = 7)

        // Table: a rounded lavender slab whose top sits at y = 0.
        val tableMesh = upload(
            MeshFactory.roundedBox(
                PlaySpace.TABLE_HALF_X, PlaySpace.TABLE_THICKNESS / 2, PlaySpace.TABLE_HALF_Z, 0.035f,
            ),
        )
        val tableMi = toyInstance(floatArrayOf(0.48f, 0.40f, 0.82f), roughness = 0.6f)
        val table = addRenderable(tableMesh, tableMi, castShadows = false, receiveShadows = true)
        setTransform(table, Mat4.trs(mat, Vec3(0f, -PlaySpace.TABLE_THICKNESS / 2, 0f), Quat.IDENTITY, 1f, 1f, 1f))

        // Floating pastel shapes for a dreamy, abstract space.
        val sphereMesh = upload(MeshFactory.sphere())
        // Below and beyond the table, so they peek out around its edges without covering play space.
        addDecor(sphereMesh, Vec3(-0.72f, -0.45f, -1.05f), 0.20f, floatArrayOf(1.0f, 0.48f, 0.40f))
        addDecor(sphereMesh, Vec3(0.78f, -0.30f, -0.70f), 0.16f, floatArrayOf(1.0f, 0.78f, 0.25f))
        addDecor(sphereMesh, Vec3(0.70f, -0.55f, 0.75f), 0.18f, floatArrayOf(0.20f, 0.68f, 0.74f))
        addDecor(sphereMesh, Vec3(-0.68f, -0.35f, 0.40f), 0.12f, floatArrayOf(0.95f, 0.42f, 0.68f))


        // Hand.
        // Target ring for guided tasks (shown only when the game sets a target).
        val ringMesh = upload(MeshFactory.ring(color = floatArrayOf(1.0f, 0.85f, 0.35f)), withTangents = false)
        val ringMi = gradientMaterial.createInstance().also { materialInstances += it }
        ringEntity = addRenderable(ringMesh, ringMi, castShadows = false, receiveShadows = false, addToScene = false)

        // Hand: smooth meshes rebuilt every frame, plus an inverted-hull outline.
        handMaterial = toyInstance(floatArrayOf(1.0f, 0.95f, 0.89f), roughness = 0.5f)
        cuffMaterial = toyInstance(floatArrayOf(1.0f, 0.55f, 0.66f), roughness = 0.45f)
        outlineMaterial = Materials.outline(engine)
        val outlineMi = outlineMaterial.createInstance().also {
            it.setParameter("color", 0.16f, 0.09f, 0.24f)
            materialInstances += it
        }
        handMeshes.build(game.rig.pose.renderJoints, game.rig.pose.rotation) // fixes the topology
        val body = handMeshes.body; val cuff = handMeshes.cuff
        handBody = DynamicMesh(body.vertexCount, body.indices, handMaterial, castShadows = true)
        handCuff = DynamicMesh(cuff.vertexCount, cuff.indices, cuffMaterial, castShadows = true)
        val outlineIndices = ShortArray(body.indices.size + cuff.indices.size)
        body.indices.copyInto(outlineIndices)
        for (i in cuff.indices.indices) {
            outlineIndices[body.indices.size + i] = ((cuff.indices[i].toInt() and 0xFFFF) + body.vertexCount).toShort()
        }
        val outlineVerts = body.vertexCount + cuff.vertexCount
        outlinePositions = FloatArray(outlineVerts * 3)
        outlineNormals = FloatArray(outlineVerts * 3)
        handOutline = DynamicMesh(outlineVerts, outlineIndices, outlineMi, castShadows = false, withTangents = false)

        // Critter (topology is fixed, so build once from a stand-in to size the buffers).
        critterBodyMat = toyInstance(com.handgame.prototype.game.Critter.DEFAULT_COLOR, roughness = 0.4f)
        critterMeshes.build(com.handgame.prototype.game.Critter(Vec3.ZERO))
        val cb = critterMeshes.body; val cm = critterMeshes.mouth
        critterBody = DynamicMesh(cb.vertexCount, cb.indices, critterBodyMat, castShadows = true)
        critterOutlinePos = FloatArray(cb.positions.size)
        critterOutline = DynamicMesh(cb.vertexCount, cb.indices, outlineMi, castShadows = false, withTangents = false)
        critterMouth = DynamicMesh(cm.vertexCount, cm.indices, toyInstance(floatArrayOf(0.16f, 0.07f, 0.22f), 0.6f), castShadows = false)
        val eyeMat = toyInstance(floatArrayOf(1f, 1f, 1f), roughness = 0.25f)
        val pupilMat = toyInstance(floatArrayOf(0.1f, 0.05f, 0.15f), roughness = 0.2f)
        val heartMat = toyInstance(floatArrayOf(1f, 0.45f, 0.65f), roughness = 0.3f).also { it.setParameter("glow", 1f, 0.4f, 0.6f, 0.4f) }
        for (i in 0 until 2) {
            eyeEntities[i] = addRenderable(sphereMesh, eyeMat, castShadows = false, addToScene = false)
            pupilEntities[i] = addRenderable(sphereMesh, pupilMat, castShadows = false, addToScene = false)
        }
        for (i in heartEntities.indices) heartEntities[i] = addRenderable(sphereMesh, heartMat, castShadows = false, addToScene = false)

        // Buttons: a soft base puck and a round cap that presses down.
        val baseMesh = upload(MeshFactory.lathe(listOf(0f to 0f, 1f to 0f, 1.05f to 0.25f, 1f to 0.5f, 0.8f to 0.55f, 0f to 0.55f)))
        val capMesh = upload(MeshFactory.lathe(listOf(0f to 0f, 0.72f to 0f, 0.75f to 0.4f, 0.65f to 0.85f, 0.35f to 1f, 0f to 1.02f)))
        for (i in 0 until 2) {
            val capMat = toyInstance(floatArrayOf(1f, 0.45f, 0.42f), roughness = 0.3f)
            buttonViews += ButtonView(
                addRenderable(baseMesh, toyInstance(floatArrayOf(0.95f, 0.92f, 1f), 0.5f), addToScene = false),
                addRenderable(capMesh, capMat, addToScene = false), capMat,
            )
        }

        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
    }

    // ------------------------------------------------------------------------------------------
    // Scene setup helpers
    // ------------------------------------------------------------------------------------------

    private fun setupLights() {
        // Soft key light from above-front-left, casting the shadows that sell depth.
        val sun = EntityManager.get().create().also { entities += it }
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.92f)
            .intensity(4.5f)
            .direction(0.35f, -1.0f, -0.45f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = 2048
                shadowFar = 8f
                stable = true
            })
            .build(engine, sun)
        scene.addEntity(sun)

        // Ambient: a soft lavender/peach sky approximated by low-order spherical harmonics.
        val sh = floatArrayOf(
            // Kept low relative to the sun so shading and shadows have contrast.
            0.42f, 0.38f, 0.52f, // L00: cool lavender ambient
            0.08f, 0.07f, 0.12f, // L1-1: a little brighter from above
            0.00f, 0.00f, 0.00f, // L10
            0.06f, 0.03f, 0.00f, // L11: warm bounce from the side
        )
        val ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1.0f).build(engine)
        scene.indirectLight = ibl
    }

    private fun addDecor(mesh: Gpu, pos: Vec3, radius: Float, color: FloatArray) {
        val mi = toyInstance(color, roughness = 0.4f)
        val e = addRenderable(mesh, mi, castShadows = false, receiveShadows = false)
        setTransform(e, Mat4.trs(mat, pos, Quat.IDENTITY, radius, radius, radius))
    }

    private fun toyInstance(color: FloatArray, roughness: Float): MaterialInstance {
        val mi = toyMaterial.createInstance()
        mi.setParameter("baseColor", color[0], color[1], color[2])
        mi.setParameter("roughness", roughness)
        mi.setParameter("glow", 0f, 0f, 0f, 0f)
        materialInstances += mi
        return mi
    }

    private fun upload(mesh: MeshData, withTangents: Boolean = true): Gpu {
        val n = mesh.vertexCount
        val builder = VertexBuffer.Builder().vertexCount(n)
        var bufferIndex = 0
        builder.attribute(VertexBuffer.VertexAttribute.POSITION, bufferIndex++, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        val tangentSlot = if (withTangents) bufferIndex++ else -1
        if (withTangents) {
            builder.attribute(VertexBuffer.VertexAttribute.TANGENTS, tangentSlot, VertexBuffer.AttributeType.FLOAT4, 0, 16)
        }
        val colorSlot = if (mesh.colors != null) bufferIndex++ else -1
        if (mesh.colors != null) {
            builder.attribute(VertexBuffer.VertexAttribute.COLOR, colorSlot, VertexBuffer.AttributeType.FLOAT4, 0, 16)
        }
        val vb = builder.bufferCount(bufferIndex).build(engine)

        vb.setBufferAt(engine, 0, floatBuffer(mesh.positions))
        if (withTangents) {
            val so = SurfaceOrientation.Builder()
                .vertexCount(n)
                .normals(floatBuffer(mesh.normals))
                .build()
            val quats = floatBuffer(FloatArray(n * 4))
            so.getQuatsAsFloat(quats)
            so.destroy()
            quats.rewind()
            vb.setBufferAt(engine, tangentSlot, quats)
        }
        mesh.colors?.let { vb.setBufferAt(engine, colorSlot, floatBuffer(it)) }

        val ib = IndexBuffer.Builder()
            .indexCount(mesh.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib.setBuffer(engine, shortBuffer(mesh.indices))

        vertexBuffers += vb
        indexBuffers += ib
        return Gpu(vb, ib, mesh.indices.size, mesh.bounds())
    }

    private fun addRenderable(
        mesh: Gpu,
        mi: MaterialInstance,
        castShadows: Boolean = true,
        receiveShadows: Boolean = true,
        addToScene: Boolean = true,
        priority: Int = 4,
    ): Int {
        val e = EntityManager.get().create()
        val b = mesh.bounds
        RenderableManager.Builder(1)
            .boundingBox(Box(b[0], b[1], b[2], b[3], b[4], b[5]))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib, 0, mesh.indexCount)
            .material(0, mi)
            .castShadows(castShadows)
            .receiveShadows(receiveShadows)
            .priority(priority)
            .culling(false)
            .build(engine, e)
        val tm = engine.transformManager
        if (!tm.hasComponent(e)) tm.create(e)
        if (addToScene) scene.addEntity(e)
        entities += e
        return e
    }

    private fun setTransform(entity: Int, m: FloatArray) {
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(entity), m)
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); rewind() }

    private fun shortBuffer(a: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(a); rewind() }

    // ------------------------------------------------------------------------------------------
    // Frame loop
    // ------------------------------------------------------------------------------------------

    fun resume() {
        if (running) return
        running = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)

        val dt = if (lastFrameNs == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNs) / 1e9f)
        lastFrameNs = frameTimeNanos
        timeSec += dt
        onFrame(dt, frameTimeNanos)
        syncScene()

        val sc = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun syncScene() {
        // Toys, with a gentle pulse and pop on the one within grab range.
        val pulse = 0.75f + 0.25f * sin(timeSec * 6f)
        val present = game.toys.filter { it.active }.toSet()
        for ((toy, view) in toyViews) if (view.inScene && toy !in present) { scene.removeEntity(view.entity); view.inScene = false }
        for (toy in present) {
            val view = toyViews.getOrPut(toy) { createToyView(toy) }
            if (!view.inScene) { scene.addEntity(view.entity); view.inScene = true }
            val h = if (toy === game.highlightToy) game.highlight else 0f
            view.material.setParameter("glow", 1.0f * h * pulse, 0.85f * h * pulse, 0.45f * h * pulse, 0.6f)
            val sc = 1f + 0.04f * h
            setTransform(view.entity, Mat4.trs(mat, toy.body.position, toy.body.orientation, sc, sc, sc))
        }

        syncCritter()
        syncButtons()

        // Target ring.
        val target = game.target
        if ((target != null) != ringInScene) {
            if (target != null) scene.addEntity(ringEntity) else scene.removeEntity(ringEntity)
            ringInScene = target != null
        }
        if (target != null) {
            val r = target.radius * (1f + 0.04f * sin(timeSec * 3f))
            setTransform(ringEntity, Mat4.trs(mat, Vec3(target.center.x, 0.003f, target.center.z), Quat.IDENTITY, r, 1f, r))
        }

        // Hand.
        val visible = game.handVisible
        if (visible != handInScene) {
            val all = intArrayOf(handBody.entity, handCuff.entity, handOutline.entity)
            if (visible) scene.addEntities(all) else scene.removeEntities(all)
            handInScene = visible
        }
        if (!visible) return

        // Slightly dim the hand during the resume countdown, so it reads as "not live yet".
        val dim = if (game.phase == Phase.RESUMING || game.phase == Phase.CALIBRATING) 0.10f else 0f
        handMaterial.setParameter("glow", 0.6f * dim, 0.7f * dim, 1.0f * dim, 1f)

        val pose = game.rig.pose
        handMeshes.build(pose.renderJoints, pose.rotation)
        val body = handMeshes.body; val cuff = handMeshes.cuff
        handBody.update(body.positions, body.normals)
        handCuff.update(cuff.positions, cuff.normals)

        // Outline = the same surfaces pushed out along their normals, drawn back-faces only.
        val nb = body.positions.size
        for (i in 0 until nb) {
            outlinePositions[i] = body.positions[i] + body.normals[i] * OUTLINE_WIDTH
        }
        for (i in cuff.positions.indices) {
            outlinePositions[nb + i] = cuff.positions[i] + cuff.normals[i] * OUTLINE_WIDTH
        }
        handOutline.update(outlinePositions, null)
    }

    private fun syncButtons() {
        for ((i, view) in buttonViews.withIndex()) {
            val b = game.buttons.getOrNull(i)
            if ((b != null) != view.inScene) {
                if (b != null) scene.addEntities(intArrayOf(view.base, view.cap)) else scene.removeEntities(intArrayOf(view.base, view.cap))
                view.inScene = b != null
            }
            if (b == null) continue
            val baseH = b.height * 0.45f
            setTransform(view.base, Mat4.trs(mat, b.center, Quat.IDENTITY, b.radius, baseH / 0.55f, b.radius))
            val capTravel = b.press * com.handgame.prototype.game.PushButton.TRAVEL
            val capH = b.height - baseH
            setTransform(view.cap, Mat4.trs(mat, b.center + Vec3(0f, baseH - capTravel, 0f), Quat.IDENTITY, b.radius, capH, b.radius))
            val pulse = 0.5f + 0.5f * sin(timeSec * 4f)
            val g = maxOf(b.lit, if (!b.pressed) 0.25f * pulse else 0f)
            view.capMat.setParameter("glow", 1f * g, 0.6f * g, 0.3f * g, 0.8f)
        }
    }

    private fun syncCritter() {
        val c = game.critter
        val all = intArrayOf(critterBody.entity, critterOutline.entity, critterMouth.entity, eyeEntities[0], eyeEntities[1])
        if ((c != null) != critterInScene) {
            if (c != null) scene.addEntities(all) else scene.removeEntities(all)
            critterInScene = c != null
            if (c != null) critterBodyMat.setParameter("baseColor", c.color[0], c.color[1], c.color[2])
        }
        if (c == null) {
            if (pupilsInScene) { scene.removeEntities(pupilEntities); pupilsInScene = false }
            for (i in heartEntities.indices) if (heartInScene[i]) { scene.removeEntity(heartEntities[i]); heartInScene[i] = false }
            return
        }
        critterMeshes.build(c)
        val body = critterMeshes.body
        critterBody.update(body.positions, body.normals)
        for (i in body.positions.indices) critterOutlinePos[i] = body.positions[i] + body.normals[i] * OUTLINE_WIDTH
        critterOutline.update(critterOutlinePos, null)
        critterMouth.update(critterMeshes.mouth.positions, critterMeshes.mouth.normals)

        // Eyes: flattened onto the body, lids closing by squashing them; pupils follow the look.
        val showPupils = c.eyesClosed < 0.6f
        if (showPupils != pupilsInScene) {
            if (showPupils) scene.addEntities(pupilEntities) else scene.removeEntities(pupilEntities)
            pupilsInScene = showPupils
        }
        for ((i, e) in critterMeshes.eyes.withIndex()) {
            val q = Quat.fromBasis((e.up cross e.normal).normalized(), e.up, e.normal)
            val r = 0.017f
            setTransform(eyeEntities[i], Mat4.trs(mat, e.center, q, r, r * (1f - c.eyesClosed * 0.85f), r * 0.7f))
            if (showPupils) {
                val side = (e.up cross e.normal).normalized()
                val p = e.center + e.normal * (r * 0.62f) + side * (c.lookX * 0.006f) + e.up * (c.lookY * 0.006f)
                setTransform(pupilEntities[i], Mat4.trs(mat, p, q, 0.007f, 0.007f, 0.004f))
            }
        }
        // Hearts: little pink bubbles that rise, swell and shrink.
        for (i in heartEntities.indices) {
            val h = c.hearts.getOrNull(i)
            if ((h != null) != heartInScene[i]) {
                if (h != null) scene.addEntity(heartEntities[i]) else scene.removeEntity(heartEntities[i])
                heartInScene[i] = h != null
            }
            if (h != null) {
                val life = h.second / com.handgame.prototype.game.Critter.HEART_LIFE
                val size = 0.012f * sin(life * 3.1416f).coerceAtLeast(0.05f)
                setTransform(heartEntities[i], Mat4.trs(mat, h.first, Quat.IDENTITY, size, size, size))
            }
        }
    }

    /**
     * A renderable whose vertices are rewritten every frame. Uses three rotating CPU buffers so a
     * buffer is never overwritten while the GPU thread may still be reading it.
     */
    private inner class DynamicMesh(
        private val vertexCount: Int,
        indices: ShortArray,
        mi: MaterialInstance,
        castShadows: Boolean,
        private val withTangents: Boolean = true,
    ) {
        val entity: Int = EntityManager.get().create()
        private val vb: VertexBuffer
        private val posBuffers = Array(3) { floatBuffer(FloatArray(vertexCount * 3)) }
        private val quatBuffers = Array(3) { floatBuffer(FloatArray(vertexCount * 4)) }
        private val normalScratch = floatBuffer(FloatArray(vertexCount * 3))
        private var slot = 0

        init {
            val b = VertexBuffer.Builder().vertexCount(vertexCount)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            if (withTangents) b.attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            vb = b.bufferCount(if (withTangents) 2 else 1).build(engine)
            val ib = IndexBuffer.Builder().indexCount(indices.size)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
            ib.setBuffer(engine, shortBuffer(indices))
            vertexBuffers += vb
            indexBuffers += ib
            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 2f, 2f, 2f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, indices.size)
                .material(0, mi)
                .castShadows(castShadows)
                .receiveShadows(true)
                .culling(false)
                .build(engine, entity)
            val tm = engine.transformManager
            if (!tm.hasComponent(entity)) tm.create(entity)
            entities += entity
        }

        fun update(positions: FloatArray, normals: FloatArray?) {
            slot = (slot + 1) % 3
            val pb = posBuffers[slot]
            pb.clear(); pb.put(positions, 0, vertexCount * 3); pb.rewind()
            vb.setBufferAt(engine, 0, pb)
            if (withTangents && normals != null) {
                normalScratch.clear(); normalScratch.put(normals, 0, vertexCount * 3); normalScratch.rewind()
                val so = SurfaceOrientation.Builder().vertexCount(vertexCount).normals(normalScratch).build()
                val qb = quatBuffers[slot]
                qb.clear()
                so.getQuatsAsFloat(qb)
                so.destroy()
                qb.rewind()
                vb.setBufferAt(engine, 1, qb)
            }
        }
    }

    private fun createToyView(toy: com.handgame.prototype.game.Toy): ToyView {
        val h = toy.body.halfExtents
        val mesh = boxMeshes.getOrPut(h) { upload(MeshFactory.roundedBox(h.x, h.y, h.z, minOf(h.x, h.y, h.z) * 0.32f)) }
        val mi = toyInstance(toy.color, roughness = 0.35f)
        val e = addRenderable(mesh, mi, castShadows = true, receiveShadows = true, addToScene = false)
        return ToyView(e, mi)
    }

    fun destroy() {
        pause()
        uiHelper.detach()
        engine.flushAndWait()
        for (e in entities) {
            scene.remove(e)
            engine.destroyEntity(e)
            EntityManager.get().destroy(e)
        }
        scene.indirectLight?.let { engine.destroyIndirectLight(it) }
        materialInstances.forEach { engine.destroyMaterialInstance(it) }
        engine.destroyMaterial(toyMaterial)
        engine.destroyMaterial(gradientMaterial)
        engine.destroyMaterial(outlineMaterial)
        vertexBuffers.forEach { engine.destroyVertexBuffer(it) }
        indexBuffers.forEach { engine.destroyIndexBuffer(it) }
        view.colorGrading?.let { engine.destroyColorGrading(it) }
        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        engine.destroy()
    }

    private fun updateViewport(width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        // The game owns the camera framing so hand mapping and rendering always agree.
        val rig = game.view
        rig.fit(width.toFloat() / height.toFloat())
        camera.setProjection(rig.verticalFovDeg, rig.aspect.toDouble(), 0.05, 60.0, Camera.Fov.VERTICAL)
        val e = rig.eye; val t = rig.target
        camera.lookAt(e.x.toDouble(), e.y.toDouble(), e.z.toDouble(), t.x.toDouble(), t.y.toDouble(), t.z.toDouble(), 0.0, 1.0, 0.0)
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            displayHelper.attach(renderer, surfaceView.display)
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            updateViewport(width, height)
        }
    }
}
