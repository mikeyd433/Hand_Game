package com.handgame.prototype.ui

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.GameUi
import com.handgame.prototype.game.Phase
import com.handgame.prototype.input.CalibrationSession

val Ink = Color(0xFF4A4060)
val Cream = Color(0xFFFFF8F0)
val Panel = Color(0xEEFFF8F0)
val Accent = Color(0xFF8C7BD8)

private val colors = lightColorScheme(primary = Accent, onPrimary = Color.White, surface = Cream, onSurface = Ink)

@Composable
fun GameScreen(
    ui: GameUi,
    settings: GameSettings,
    debugFrame: Bitmap?,
    error: String?,
    frontCamera: Boolean,
    showDebugToggle: Boolean,
    createSurface: (SurfaceView) -> Unit,
    onSettingsChange: (GameSettings) -> Unit,
    onRecalibrate: () -> Unit,
    onSkipRange: () -> Unit,
    onResetCube: () -> Unit,
    tuning: TuningUi = TuningUi(),
    canTune: Boolean = false,
    onStartTuning: (guided: Boolean) -> Unit = {},
    onStopTuning: () -> Unit = {},
    onMark: (String) -> Unit = {},
    onSaveRecording: () -> Unit = {},
    onCopySummary: () -> Unit = {},
    onCloseResults: () -> Unit = {},
    onPullTargetIn: () -> Unit = {},
    onSkipLesson: () -> Unit = {},
    onLeaveTutorial: () -> Unit = {},
    onReplayTutorial: () -> Unit = {},
) {
    var showSettings by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> SurfaceView(ctx).also(createSurface) },
            )

            EdgeGlow(ui.edgeWarning, ui.edgeX, ui.edgeY)

            if (ui.phase == Phase.CALIBRATING) CalibrationView(ui, debugFrame, onSkipRange, onPullTargetIn)

            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                // Top bar.
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showDebugToggle) {
                        RoundIconButton(if (settings.debugOverlay) "DBG✓" else "DBG") {
                            onSettingsChange(settings.copy(debugOverlay = !settings.debugOverlay))
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    RoundIconButton("⚙") { showSettings = true }
                }

                if (settings.debugOverlay) {
                    ui.debug?.let { DebugOverlay(it, debugFrame, frontCamera, Modifier.align(Alignment.TopStart)) }
                }

                // Tutorial lesson card (hidden while calibrating or paused).
                val tut = ui.tutorial
                if (tut != null && ui.phase == Phase.PLAYING && tuning.mode == TuningMode.IDLE) {
                    TutorialOverlay(tut, onSkipLesson, onLeaveTutorial)
                }

                // While a guided test runs, its card replaces the pause/resume prompts at the bottom.
                if (tuning.mode != TuningMode.IDLE) {
                    TuningOverlay(tuning, onStopTuning, onMark, onSaveRecording, onCopySummary, onCloseResults)
                }

                if (tuning.mode != TuningMode.GUIDED) when (ui.phase) {
                    Phase.CALIBRATING -> Unit // full-screen camera view, drawn below
                    Phase.PAUSED -> PausedOverlay(frontCamera, Modifier.align(Alignment.Center))
                    Phase.RESUMING -> ResumeOverlay(ui, Modifier.align(Alignment.Center))
                    Phase.PLAYING -> Unit
                }

                error?.let {
                    Text(
                        it,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                            .background(Color(0xCCB04060), RoundedCornerShape(12.dp)).padding(12.dp),
                    )
                }

                AnimatedVisibility(showSettings, enter = fadeIn(), exit = fadeOut()) {
                    SettingsPanel(
                        settings = settings,
                        showDeveloper = showDebugToggle,
                        onChange = onSettingsChange,
                        onRecalibrate = { onRecalibrate(); showSettings = false },
                        onResetCube = { onResetCube(); showSettings = false },
                        onClose = { showSettings = false },
                        canTune = canTune && tuning.mode == TuningMode.IDLE && ui.tutorial == null,
                        onReplayTutorial = { onReplayTutorial(); showSettings = false },
                        onStartTuning = { guided -> onStartTuning(guided); showSettings = false },
                    )
                }
            }
        }
    }
}

/** Soft glow on the screen edge the hand is about to leave the camera's view through. */
@Composable
private fun EdgeGlow(strength: Float, edgeX: Int, edgeY: Int) {
    if (strength <= 0.01f) return
    val glow = Color(0xFFFFC46B).copy(alpha = 0.75f * strength)
    val clear = Color.Transparent
    Box(Modifier.fillMaxSize()) {
        val thickness = 90.dp
        when {
            edgeX < 0 -> Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(thickness)
                .background(Brush.horizontalGradient(listOf(glow, clear))))
            edgeX > 0 -> Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(thickness)
                .background(Brush.horizontalGradient(listOf(clear, glow))))
            edgeY < 0 -> Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(thickness)
                .background(Brush.verticalGradient(listOf(glow, clear))))
            edgeY > 0 -> Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(thickness)
                .background(Brush.verticalGradient(listOf(clear, glow))))
        }
        if (strength > 0.6f) {
            Text(
                "Edge of the camera's view",
                color = Ink,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(top = 260.dp)
                    .background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RoundIconButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.clip(CircleShape).background(Panel),
        colors = ButtonDefaults.textButtonColors(contentColor = Ink),
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(24.dp)
            .background(Panel, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun PausedOverlay(frontCamera: Boolean, modifier: Modifier) {
    Card(modifier) {
        Text("Paused", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (frontCamera) "Show your hand to the front camera to keep playing."
            else "Reach behind the phone so the back camera can see your hand.",
            color = Ink, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResumeOverlay(ui: GameUi, modifier: Modifier) {
    Card(modifier) {
        Text(if (ui.handFoundFlash) "Hand found!" else "Ready", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${ui.countdown}", color = Accent, fontSize = 48.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize().background(Color(0xFFF3E6F5)), contentAlignment = Alignment.Center) {
            Card {
                Text("Camera needed", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your hand is the controller. The camera is used only to track your hand, and the picture is never shown or stored.",
                    color = Ink, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRequest) { Text("Allow camera") }
            }
        }
    }
}
