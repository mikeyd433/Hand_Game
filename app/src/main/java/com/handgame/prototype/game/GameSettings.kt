package com.handgame.prototype.game

import android.content.Context
import com.handgame.prototype.input.Calibration

enum class HandednessSetting { AUTO, LEFT, RIGHT }

/** Player and developer settings. Everything here is adjustable at runtime from the settings panel. */
data class GameSettings(
    val frontCamera: Boolean = true,
    val handedness: HandednessSetting = HandednessSetting.AUTO,
    /** >1 = smaller hand motion covers the whole table. */
    val sensitivity: Float = 1.0f,
    /** >1 = less change in hand distance needed to lift. */
    val depthSensitivity: Float = 1.0f,
    /** Flips which way moving toward/away from the camera lifts. */
    val invertLift: Boolean = false,
    val vibration: Boolean = true,
    /** Size of the on-screen hand (1 = original prototype size). */
    val handScale: Float = DEFAULT_HAND_SCALE,
    // One Euro filter parameters (position in world units, depth in 0..1 lift units).
    val posMinCutoff: Float = 0.45f,
    val posBeta: Float = 1.5f,
    // Finger smoothing now filters joint angles (radians), not positions.
    val shapeMinCutoff: Float = 0.8f,
    val shapeBeta: Float = 0.15f,
    val depthMinCutoff: Float = 0.5f,
    val depthBeta: Float = 1.0f,
    val debugOverlay: Boolean = false,
) {
    companion object {
        const val DEFAULT_HAND_SCALE = 1.35f
    }
}

private const val SETTINGS_VERSION = 2 // (v3 migration applied separately in loadSettings)

/** Saves settings and calibration in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("hand_game", Context.MODE_PRIVATE)

    fun loadSettings(): GameSettings {
        val d = GameSettings()
        // Older versions saved smoothing values tuned for a different pipeline; drop them once.
        if (prefs.getInt("settingsVersion", 1) < SETTINGS_VERSION) {
            prefs.edit()
                .remove("posMinCutoff").remove("posBeta")
                .remove("shapeMinCutoff").remove("shapeBeta")
                .remove("depthMinCutoff").remove("depthBeta")
                .putInt("settingsVersion", SETTINGS_VERSION)
                .apply()
        }
        // v3: retuned position smoothing. Only replace values the player never changed.
        if (prefs.getInt("settingsVersion", 1) < 3) {
            val e = prefs.edit()
            if (prefs.getFloat("posMinCutoff", 0.6f) == 0.6f && prefs.getFloat("posBeta", 2f) == 2f) {
                e.remove("posMinCutoff").remove("posBeta")
            }
            e.putInt("settingsVersion", 3).apply()
        }
        return GameSettings(
            frontCamera = prefs.getBoolean("frontCamera", d.frontCamera),
            handedness = runCatching {
                HandednessSetting.valueOf(prefs.getString("handedness", d.handedness.name)!!)
            }.getOrDefault(d.handedness),
            sensitivity = prefs.getFloat("sensitivity", d.sensitivity),
            depthSensitivity = prefs.getFloat("depthSensitivity", d.depthSensitivity),
            invertLift = prefs.getBoolean("invertLift", d.invertLift),
            vibration = prefs.getBoolean("vibration", d.vibration),
            handScale = prefs.getFloat("handScale", d.handScale),
            posMinCutoff = prefs.getFloat("posMinCutoff", d.posMinCutoff),
            posBeta = prefs.getFloat("posBeta", d.posBeta),
            shapeMinCutoff = prefs.getFloat("shapeMinCutoff", d.shapeMinCutoff),
            shapeBeta = prefs.getFloat("shapeBeta", d.shapeBeta),
            depthMinCutoff = prefs.getFloat("depthMinCutoff", d.depthMinCutoff),
            depthBeta = prefs.getFloat("depthBeta", d.depthBeta),
            debugOverlay = prefs.getBoolean("debugOverlay", d.debugOverlay),
        )
    }

    fun saveSettings(s: GameSettings) {
        prefs.edit()
            .putBoolean("frontCamera", s.frontCamera)
            .putString("handedness", s.handedness.name)
            .putFloat("sensitivity", s.sensitivity)
            .putFloat("depthSensitivity", s.depthSensitivity)
            .putBoolean("invertLift", s.invertLift)
            .putBoolean("vibration", s.vibration)
            .putFloat("handScale", s.handScale)
            .putFloat("posMinCutoff", s.posMinCutoff)
            .putFloat("posBeta", s.posBeta)
            .putFloat("shapeMinCutoff", s.shapeMinCutoff)
            .putFloat("shapeBeta", s.shapeBeta)
            .putFloat("depthMinCutoff", s.depthMinCutoff)
            .putFloat("depthBeta", s.depthBeta)
            .putBoolean("debugOverlay", s.debugOverlay)
            .apply()
    }

    /** Calibration is stored per camera, since the two setups differ. */
    fun loadCalibration(frontCamera: Boolean): Calibration? {
        val k = if (frontCamera) "cal_front_" else "cal_back_"
        if (!prefs.contains(k + "baseSize")) return null
        return Calibration(
            baseSize = prefs.getFloat(k + "baseSize", 0.2f),
            centerU = prefs.getFloat(k + "centerU", 0.5f),
            centerV = prefs.getFloat(k + "centerV", 0.5f),
            rangeU = prefs.getFloat(k + "rangeU", Calibration.DEFAULT_RANGE_U),
            rangeV = prefs.getFloat(k + "rangeV", Calibration.DEFAULT_RANGE_V),
            worldPalmLength = prefs.getFloat(k + "worldPalm", 0.085f),
            extents = if (prefs.contains(k + "extL")) listOf("extL", "extR", "extU", "extD").map { prefs.getFloat(k + it, 0.1f) } else null,
        )
    }

    fun saveCalibration(frontCamera: Boolean, c: Calibration) {
        val k = if (frontCamera) "cal_front_" else "cal_back_"
        prefs.edit()
            .putFloat(k + "baseSize", c.baseSize)
            .putFloat(k + "centerU", c.centerU)
            .putFloat(k + "centerV", c.centerV)
            .putFloat(k + "rangeU", c.rangeU)
            .putFloat(k + "rangeV", c.rangeV)
            .putFloat(k + "worldPalm", c.worldPalmLength)
            .apply {
                val e = c.extents
                if (e != null) {
                    putFloat(k + "extL", e[0]); putFloat(k + "extR", e[1]); putFloat(k + "extU", e[2]); putFloat(k + "extD", e[3])
                } else listOf("extL", "extR", "extU", "extD").forEach { remove(k + it) }
            }
            .apply()
    }
}
