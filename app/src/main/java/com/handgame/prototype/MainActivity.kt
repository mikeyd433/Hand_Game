package com.handgame.prototype

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.GameUi
import com.handgame.prototype.game.HapticEvent
import com.handgame.prototype.game.SettingsStore
import com.handgame.prototype.render.SceneRenderer
import com.handgame.prototype.tracking.HandTracker
import com.handgame.prototype.ui.GameScreen
import com.handgame.prototype.ui.PermissionScreen
import com.handgame.prototype.ui.TuningMode
import com.handgame.prototype.ui.TuningUi
import com.handgame.prototype.tuning.Recording
import com.handgame.prototype.tuning.TuningAnalyzer
import com.handgame.prototype.tuning.TuningRecorder
import com.handgame.prototype.game.Phase
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private lateinit var tracker: HandTracker
    private lateinit var game: GameController
    private var renderer: SceneRenderer? = null

    private val settings = mutableStateOf(GameSettings())
    private val ui = mutableStateOf(GameUi())
    private val debugFrame = mutableStateOf<Bitmap?>(null)
    private val hasPermission = mutableStateOf(false)
    private val trackerError = mutableStateOf<String?>(null)
    private var uiTimer = 0f

    // Tuning recorder.
    private val recorder = TuningRecorder()
    private val tuning = mutableStateOf(TuningUi())
    private var lastRecording: Recording? = null
    private var markCount = 0
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val rec = lastRecording ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)!!.use { os ->
                GZIPOutputStream(os).use { it.write(rec.toJsonLines().toByteArray()) }
            }
        }.onSuccess { Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission.value = granted
            if (granted) startTracking()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        store = SettingsStore(this)
        val initial = store.loadSettings().let { if (BuildConfig.DEBUG) it else it.copy(debugOverlay = false) }
        settings.value = initial
        tracker = HandTracker(this)
        tracker.debugFrameEnabled = initial.debugOverlay
        game = GameController(
            initialSettings = initial,
            initialCalibration = store.loadCalibration(initial.frontCamera),
            onHaptic = ::haptic,
            onCalibrated = { store.saveCalibration(settings.value.frontCamera, it) },
        )
        game.trackingFpsProvider = { tracker.trackingFps }

        hasPermission.value = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

        setContent {
            if (!hasPermission.value) {
                PermissionScreen(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
            } else {
                GameScreen(
                    ui = ui.value,
                    settings = settings.value,
                    debugFrame = debugFrame.value,
                    error = trackerError.value,
                    frontCamera = settings.value.frontCamera,
                    showDebugToggle = BuildConfig.DEBUG,
                    createSurface = ::createSurface,
                    onSettingsChange = ::applySettings,
                    onRecalibrate = { game.recalibrate() },
                    onSkipRange = { game.skipRangeCalibration() },
                    onResetCube = { game.respawnCube() },
                    tuning = tuning.value,
                    canTune = ui.value.phase != Phase.CALIBRATING,
                    onStartTuning = ::startTuning,
                    onStopTuning = ::finishTuning,
                    onMark = { recorder.mark(it); markCount++ },
                    onSaveRecording = ::saveRecording,
                    onCopySummary = ::copySummary,
                    onCloseResults = { tuning.value = TuningUi() },
                )
            }
        }

        if (hasPermission.value) startTracking() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun createSurface(view: SurfaceView) {
        renderer?.destroy()
        renderer = SceneRenderer(view, game, ::onFrame).also { it.resume() }
    }

    private fun startTracking() {
        tracker.start(this, settings.value.frontCamera)
    }

    private fun onFrame(dt: Float, frameTimeNanos: Long) {
        val sample = tracker.latest.get()
        game.update(dt, sample, frameTimeNanos)
        if (recorder.active) {
            recorder.onFrame(dt, sample, game, frameTimeNanos)
            if (recorder.finished) finishTuning()
        }
        // Push state to Compose at ~20 Hz; the 3D view itself updates every frame.
        uiTimer += dt
        if (uiTimer >= 0.05f) {
            uiTimer = 0f
            val withDebug = settings.value.debugOverlay
            ui.value = game.uiSnapshot(withDebug)
            if (withDebug) debugFrame.value = tracker.debugFrame.get()
            trackerError.value = tracker.error
            if (recorder.active) {
                val step = recorder.currentStep
                tuning.value = tuning.value.copy(
                    stepTitle = step?.title ?: "",
                    prompt = step?.prompt ?: "",
                    cue = recorder.currentCue,
                    stepProgress = recorder.stepProgress,
                    stepNumber = recorder.stepNumber,
                    stepCount = recorder.guidedSteps?.size ?: 0,
                    elapsed = recorder.time.toFloat(),
                    marks = markCount,
                )
            }
        }
    }

    private fun startTuning(guided: Boolean) {
        markCount = 0
        val info = linkedMapOf<String, Any?>(
            "created" to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.SDK_INT,
            "app" to BuildConfig.VERSION_NAME,
            "gpu" to tracker.usingGpu,
        )
        recorder.start(guided, info, settings.value.frontCamera, System.nanoTime())
        tuning.value = TuningUi(mode = if (guided) TuningMode.GUIDED else TuningMode.FREE)
    }

    private fun finishTuning() {
        if (!recorder.active) return
        val rec = recorder.stop()
        lastRecording = rec
        val summary = runCatching { TuningAnalyzer().analyze(rec).text }
            .getOrElse { "Analysis failed: ${it.message}" }
        // The replay can change the global hand scale; restore the live setting.
        com.handgame.prototype.game.HandLook.scale = settings.value.handScale
        tuning.value = TuningUi(mode = TuningMode.RESULTS, summary = summary)
    }

    private fun saveRecording() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val kind = if (lastRecording?.guided == true) "guided" else "play"
        saveLauncher.launch("hand-tuning-$kind-$stamp.jsonl.gz")
    }

    private fun copySummary() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Hand tuning summary", tuning.value.summary))
        Toast.makeText(this, "Summary copied", Toast.LENGTH_SHORT).show()
    }

    private fun applySettings(new: GameSettings) {
        val old = settings.value
        settings.value = new
        store.saveSettings(new)
        tracker.debugFrameEnabled = new.debugOverlay
        game.updateSettings(new)
        if (new.frontCamera != old.frontCamera && hasPermission.value) {
            tracker.switchCamera(this, new.frontCamera)
        }
    }

    private fun haptic(event: HapticEvent) {
        if (!settings.value.vibration) return
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        val ms = if (event == HapticEvent.GRAB) 18L else 10L
        val amplitude = if (event == HapticEvent.GRAB) 120 else 60
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms, amplitude)) }
    }

    override fun onResume() {
        super.onResume()
        renderer?.resume()
    }

    override fun onPause() {
        super.onPause()
        renderer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.destroy()
        renderer = null
        tracker.close()
    }
}
