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

    private fun onFrame(dt: Float) {
        game.update(dt, tracker.latest.get())
        // Push state to Compose at ~20 Hz; the 3D view itself updates every frame.
        uiTimer += dt
        if (uiTimer >= 0.05f) {
            uiTimer = 0f
            val withDebug = settings.value.debugOverlay
            ui.value = game.uiSnapshot(withDebug)
            if (withDebug) debugFrame.value = tracker.debugFrame.get()
            trackerError.value = tracker.error
        }
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
