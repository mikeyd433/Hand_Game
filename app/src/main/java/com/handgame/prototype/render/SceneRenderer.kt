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
    private val onFrame: (dt: Float) -> Unit,
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
    private val cubeEntity: Int
    private val cubeMaterial: MaterialInstance
    private val handMaterial: MaterialInstance
    private val jointEntities = IntArray(Lm.COUNT)
    private val boneEntities = IntArray(Lm.BONES.size)
    private val palmEntity: Int
    private var handInScene = false

    private val mat = FloatArray(16)
    private var timeSec = 0f

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
            .toneMapper(ToneMapper.Filmic())
            .saturation(1.05f)
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
                top = floatArrayOf(0.55f, 0.66f, 0.95f),
                horizon = floatArrayOf(0.98f, 0.80f, 0.86f),
                bottom = floatArrayOf(0.80f, 0.74f, 0.95f),
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
        val tableMi = toyInstance(floatArrayOf(0.74f, 0.70f, 0.93f), roughness = 0.55f)
        val table = addRenderable(tableMesh, tableMi, castShadows = false, receiveShadows = true)
        setTransform(table, Mat4.trs(mat, Vec3(0f, -PlaySpace.TABLE_THICKNESS / 2, 0f), Quat.IDENTITY, 1f, 1f, 1f))

        // Floating pastel shapes for a dreamy, abstract space.
        val sphereMesh = upload(MeshFactory.sphere())
        addDecor(sphereMesh, Vec3(-0.9f, 0.35f, -0.9f), 0.16f, floatArrayOf(1.0f, 0.78f, 0.62f))
        addDecor(sphereMesh, Vec3(0.95f, 0.6f, -1.2f), 0.22f, floatArrayOf(0.62f, 0.86f, 0.80f))
        addDecor(sphereMesh, Vec3(0.75f, -0.45f, -0.4f), 0.12f, floatArrayOf(0.98f, 0.70f, 0.82f))
        addDecor(sphereMesh, Vec3(-0.85f, -0.35f, 0.15f), 0.09f, floatArrayOf(0.70f, 0.80f, 1.0f))

        // Cube.
        val cubeMesh = upload(
            MeshFactory.roundedBox(PlaySpace.CUBE_HALF, PlaySpace.CUBE_HALF, PlaySpace.CUBE_HALF, 0.022f),
        )
        cubeMaterial = toyInstance(floatArrayOf(0.55f, 0.88f, 0.76f), roughness = 0.35f)
        cubeEntity = addRenderable(cubeMesh, cubeMaterial, castShadows = true, receiveShadows = true)

        // Hand.
        val cylMesh = upload(MeshFactory.cylinder())
        handMaterial = toyInstance(floatArrayOf(1.0f, 0.90f, 0.82f), roughness = 0.45f)
        for (i in 0 until Lm.COUNT) jointEntities[i] = addRenderable(sphereMesh, handMaterial, addToScene = false)
        for (i in Lm.BONES.indices) boneEntities[i] = addRenderable(cylMesh, handMaterial, addToScene = false)
        palmEntity = addRenderable(sphereMesh, handMaterial, addToScene = false)

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
            .intensity(3.0f)
            .direction(0.25f, -1.0f, -0.35f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = 2048
                shadowFar = 6f
                stable = true
            })
            .build(engine, sun)
        scene.addEntity(sun)

        // Ambient: a soft lavender/peach sky approximated by low-order spherical harmonics.
        val sh = floatArrayOf(
            0.80f, 0.78f, 0.88f, // L00: overall ambient tint
            0.10f, 0.12f, 0.18f, // L1-1: brighter from above
            0.00f, 0.00f, 0.00f, // L10
            0.04f, 0.02f, 0.00f, // L11
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
        onFrame(dt)
        syncScene()

        val sc = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun syncScene() {
        // Cube, with a gentle pulse and pop when it's within grab range.
        val cube = game.cube
        val h = game.highlight
        val pulse = 0.75f + 0.25f * sin(timeSec * 6f)
        cubeMaterial.setParameter("glow", 1.0f * h * pulse, 0.85f * h * pulse, 0.45f * h * pulse, 0.6f)
        val s = 1f + 0.04f * h
        setTransform(cubeEntity, Mat4.trs(mat, cube.position, cube.orientation, s, s, s))

        // Hand.
        val visible = game.handVisible
        if (visible != handInScene) {
            val all = jointEntities + boneEntities + palmEntity
            if (visible) scene.addEntities(all) else scene.removeEntities(all)
            handInScene = visible
        }
        if (!visible) return

        // Slightly dim the hand during the resume countdown, so it reads as "not live yet".
        val dim = if (game.phase == Phase.RESUMING || game.phase == Phase.CALIBRATING) 0.12f else 0f
        handMaterial.setParameter("glow", 0.6f * dim, 0.7f * dim, 1.0f * dim, 1f)

        val pose = game.rig.pose
        val j = pose.joints
        for (i in 0 until Lm.COUNT) {
            val r = jointRadius(i)
            setTransform(jointEntities[i], Mat4.trs(mat, j[i], Quat.IDENTITY, r, r, r))
        }
        for ((k, bone) in Lm.BONES.withIndex()) {
            val r = minOf(jointRadius(bone[0]), jointRadius(bone[1])) * if (k >= 16) 1.15f else 1f
            setTransform(boneEntities[k], Mat4.segment(mat, j[bone[0]], j[bone[1]], r))
        }
        // Palm: a flattened ellipsoid filling the space between the knuckles and wrist.
        val width = j[Lm.INDEX_MCP].distance(j[Lm.PINKY_MCP]) * 0.62f + 0.02f
        val length = j[Lm.WRIST].distance(j[Lm.MIDDLE_MCP]) * 0.55f + 0.01f
        val center = (j[Lm.WRIST] + j[Lm.INDEX_MCP] + j[Lm.PINKY_MCP] + j[Lm.MIDDLE_MCP]) * 0.25f
        setTransform(palmEntity, Mat4.trs(mat, center, pose.rotation, width, 0.034f, length))
    }

    private fun jointRadius(i: Int): Float = when (i) {
        Lm.WRIST -> 0.034f
        Lm.THUMB_CMC, Lm.THUMB_MCP -> 0.026f
        Lm.INDEX_MCP, Lm.MIDDLE_MCP, Lm.RING_MCP, Lm.PINKY_MCP -> 0.026f
        Lm.THUMB_TIP, Lm.INDEX_TIP, Lm.MIDDLE_TIP, Lm.RING_TIP -> 0.024f
        Lm.PINKY_TIP, Lm.PINKY_DIP, Lm.PINKY_PIP -> 0.019f
        else -> PlaySpace.FINGER_RADIUS
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
        val aspect = width.toDouble() / height.toDouble()
        // Fit the table's width in portrait; angled 3/4 tabletop view.
        camera.setProjection(52.0, aspect, 0.05, 60.0, Camera.Fov.HORIZONTAL)
        camera.lookAt(0.0, 1.55, 1.15, 0.0, 0.04, 0.02, 0.0, 1.0, 0.0)
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
