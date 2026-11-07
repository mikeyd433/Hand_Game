package com.handgame.prototype.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the camera (front or back) through MediaPipe's Hand Landmarker. The camera image is never
 * displayed; results are published as [TrackingSample]s that the game polls each frame.
 *
 * Frames are rotated upright before detection but never mirrored, so landmark coordinates are
 * always "as the camera sees it". Mirroring for the front camera happens later, in game logic.
 */
class HandTracker(private val context: Context) {

    /** Latest result; the game loop reads it once per frame. */
    val latest = AtomicReference<TrackingSample?>(null)

    /** Small upright, un-mirrored copy of the last frame for the debug overlay (only when enabled). */
    val debugFrame = AtomicReference<Bitmap?>(null)
    @Volatile var debugFrameEnabled = false

    /** Error message for the UI, if tracking failed to start. */
    @Volatile var error: String? = null
        private set

    @Volatile var usingGpu = false
        private set

    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var frontCamera = true
    @Volatile private var frameSize = Size(0, 0)
    private var lastTimestampMs = 0L

    // Tracking rate measurement.
    @Volatile var trackingFps = 0f
        private set
    private var fpsWindowStart = 0L
    private var fpsCount = 0

    fun start(owner: LifecycleOwner, useFrontCamera: Boolean) {
        frontCamera = useFrontCamera
        latest.set(null)
        if (landmarker == null) landmarker = createLandmarker()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            bindCamera(provider, owner)
        }, ContextCompat.getMainExecutor(context))
    }

    /** Switches cameras without recreating the landmarker. */
    fun switchCamera(owner: LifecycleOwner, useFrontCamera: Boolean) {
        frontCamera = useFrontCamera
        latest.set(null)
        cameraProvider?.let { bindCamera(it, owner) } ?: start(owner, useFrontCamera)
    }

    fun stop() {
        cameraProvider?.unbindAll()
    }

    fun close() {
        stop()
        analysisExecutor.execute {
            landmarker?.close()
            landmarker = null
        }
        analysisExecutor.shutdown()
    }

    private fun bindCamera(provider: ProcessCameraProvider, owner: LifecycleOwner) {
        val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) {
            error = "This device has no ${if (frontCamera) "front" else "back"} camera."
            return
        }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analysisExecutor, ::analyze)
        provider.unbindAll()
        try {
            provider.bindToLifecycle(owner, selector, analysis)
            error = null
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            error = "Camera failed to start: ${e.message}"
        }
    }

    /** Capture time (System.nanoTime) of each frame sent to MediaPipe, keyed by its timestamp. */
    private val captureTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    private fun analyze(proxy: ImageProxy) {
        val captureNs = System.nanoTime()
        val lm = landmarker
        if (lm == null) { proxy.close(); return }
        val upright: Bitmap
        try {
            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
            } else raw
        } catch (e: Exception) {
            Log.w(TAG, "Frame conversion failed", e)
            proxy.close()
            return
        } finally {
            // toBitmap() copies the pixels, so the camera buffer can go back right away.
            runCatching { proxy.close() }
        }

        frameSize = Size(upright.width, upright.height)
        if (debugFrameEnabled) {
            val w = 160
            val h = (upright.height * w / upright.width.toFloat()).toInt().coerceAtLeast(1)
            debugFrame.set(Bitmap.createScaledBitmap(upright, w, h, true))
        }

        // MediaPipe requires strictly increasing timestamps.
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestampMs) ts = lastTimestampMs + 1
        lastTimestampMs = ts
        captureTimes[ts] = captureNs
        if (captureTimes.size > 32) captureTimes.keys.filter { it < ts - 2000 }.forEach { captureTimes.remove(it) }
        val image: MPImage = BitmapImageBuilder(upright).build()
        try {
            lm.detectAsync(image, ts)
        } catch (e: Exception) {
            Log.w(TAG, "detectAsync failed", e)
        }
    }

    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        val now = SystemClock.uptimeMillis()
        val inferenceMs = (now - result.timestampMs()).toFloat()
        val size = frameSize

        var hand: RawHand? = null
        if (result.landmarks().isNotEmpty() && result.worldLandmarks().isNotEmpty()) {
            val img = result.landmarks()[0]
            val world = result.worldLandmarks()[0]
            if (img.size == Lm.COUNT && world.size == Lm.COUNT) {
                val imageArr = FloatArray(Lm.COUNT * 2)
                val worldArr = FloatArray(Lm.COUNT * 3)
                for (i in 0 until Lm.COUNT) {
                    imageArr[i * 2] = img[i].x(); imageArr[i * 2 + 1] = img[i].y()
                    worldArr[i * 3] = world[i].x(); worldArr[i * 3 + 1] = world[i].y(); worldArr[i * 3 + 2] = world[i].z()
                }
                val category = result.handedness().firstOrNull()?.firstOrNull()
                hand = RawHand(imageArr, worldArr, category?.categoryName(), category?.score() ?: 0f)
            }
        }
        latest.set(
            TrackingSample(
                // When the camera frame was taken (not when the result arrived), so the game can
                // pace the hand evenly even though results arrive unevenly.
                timestampNs = captureTimes.remove(result.timestampMs()) ?: System.nanoTime(),
                hand = hand,
                imageWidth = size.width,
                imageHeight = size.height,
                frontCamera = frontCamera,
                inferenceMs = inferenceMs,
            ),
        )

        fpsCount++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1000) {
            trackingFps = fpsCount * 1000f / (now - fpsWindowStart)
            fpsCount = 0
            fpsWindowStart = now
        }
    }

    private fun createLandmarker(): HandLandmarker? {
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val base = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(delegate)
                    .build()
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener(::onResult)
                    .setErrorListener { e -> Log.e(TAG, "MediaPipe error", e) }
                    .build()
                val lm = HandLandmarker.createFromOptions(context, options)
                usingGpu = delegate == Delegate.GPU
                Log.i(TAG, "Hand Landmarker running on $delegate")
                return lm
            } catch (e: Throwable) {
                Log.w(TAG, "Hand Landmarker init failed on $delegate", e)
            }
        }
        error = "Could not start hand tracking."
        return null
    }

    companion object {
        private const val TAG = "HandTracker"
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}
