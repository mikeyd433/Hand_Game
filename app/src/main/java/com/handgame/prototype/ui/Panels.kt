package com.handgame.prototype.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handgame.prototype.game.DebugInfo
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.game.HandednessSetting
import com.handgame.prototype.tracking.Lm
import java.util.Locale

@Composable
fun SettingsPanel(
    settings: GameSettings,
    showDeveloper: Boolean,
    onChange: (GameSettings) -> Unit,
    onRecalibrate: () -> Unit,
    onResetCube: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0x66302840)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .background(Cream, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("Settings", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Label("Camera")
            Segmented(
                options = listOf("Front", "Back"),
                selected = if (settings.frontCamera) 0 else 1,
            ) { onChange(settings.copy(frontCamera = it == 0)) }
            Hint("Changing camera starts a new calibration.")

            Label("Playing hand")
            Segmented(
                options = listOf("Auto", "Left", "Right"),
                selected = settings.handedness.ordinal,
            ) { onChange(settings.copy(handedness = HandednessSetting.entries[it])) }

            LabeledSlider("Movement sensitivity", settings.sensitivity, 0.5f..2f) {
                onChange(settings.copy(sensitivity = it))
            }
            Hint("Higher: smaller hand movements cover the whole table.")
            LabeledSlider("Lift sensitivity", settings.depthSensitivity, 0.4f..2.5f) {
                onChange(settings.copy(depthSensitivity = it))
            }
            Hint("Higher: a smaller change in hand distance lifts things.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Reverse lift direction", color = Ink, modifier = Modifier.weight(1f))
                Switch(checked = settings.invertLift, onCheckedChange = { onChange(settings.copy(invertLift = it)) })
            }
            Hint(
                if (settings.invertLift == settings.frontCamera) "Now: move your hand toward the camera to lift."
                else "Now: move your hand away from the camera to lift.",
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Vibrate on grab", color = Ink, modifier = Modifier.weight(1f))
                Switch(checked = settings.vibration, onCheckedChange = { onChange(settings.copy(vibration = it)) })
            }

            if (showDeveloper) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Developer: smoothing (One Euro)", color = Ink, fontWeight = FontWeight.SemiBold)
                LabeledSlider("Position min cutoff (Hz)", settings.posMinCutoff, 0.1f..5f) {
                    onChange(settings.copy(posMinCutoff = it))
                }
                LabeledSlider("Position beta", settings.posBeta, 0f..30f) { onChange(settings.copy(posBeta = it)) }
                LabeledSlider("Finger min cutoff (Hz)", settings.shapeMinCutoff, 0.1f..8f) {
                    onChange(settings.copy(shapeMinCutoff = it))
                }
                LabeledSlider("Finger beta", settings.shapeBeta, 0f..40f) { onChange(settings.copy(shapeBeta = it)) }
                LabeledSlider("Depth min cutoff (Hz)", settings.depthMinCutoff, 0.05f..3f) {
                    onChange(settings.copy(depthMinCutoff = it))
                }
                LabeledSlider("Depth beta", settings.depthBeta, 0f..10f) { onChange(settings.copy(depthBeta = it)) }
                OutlinedButton(onClick = {
                    val d = GameSettings()
                    onChange(
                        settings.copy(
                            posMinCutoff = d.posMinCutoff, posBeta = d.posBeta,
                            shapeMinCutoff = d.shapeMinCutoff, shapeBeta = d.shapeBeta,
                            depthMinCutoff = d.depthMinCutoff, depthBeta = d.depthBeta,
                        ),
                    )
                }) { Text("Reset smoothing") }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRecalibrate) { Text("Recalibrate") }
                OutlinedButton(onClick = onResetCube) { Text("Reset cube") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, color = Ink, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Hint(text: String) = Text(text, color = Ink.copy(alpha = 0.6f), fontSize = 12.sp)

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Ink, modifier = Modifier.weight(1f))
        Text(String.format(Locale.US, "%.2f", value), color = Ink, fontFamily = FontFamily.Monospace)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

/** Developer-only overlay: camera feed with landmark dots plus live tracking numbers. */
@Composable
fun DebugOverlay(info: DebugInfo, frame: Bitmap?, frontCamera: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(12.dp)
            .background(Color(0xCC1E1A2A), RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        if (frame != null) {
            // Shown mirrored for the front camera so it behaves like a mirror, matching the game.
            val mirror = if (frontCamera) -1f else 1f
            Box(
                Modifier
                    .width(140.dp)
                    .aspectRatio(frame.width.toFloat() / frame.height)
                    .graphicsLayer { scaleX = mirror },
            ) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                info.landmarks?.let { lm ->
                    Canvas(Modifier.fillMaxSize()) {
                        for (bone in Lm.BONES) {
                            drawLine(
                                Color(0xAAFFFFFF),
                                Offset(lm[bone[0] * 2] * size.width, lm[bone[0] * 2 + 1] * size.height),
                                Offset(lm[bone[1] * 2] * size.width, lm[bone[1] * 2 + 1] * size.height),
                                strokeWidth = 2f,
                            )
                        }
                        for (i in 0 until Lm.COUNT) {
                            val tip = i in Lm.FINGER_TIPS
                            drawCircle(
                                if (tip) Color(0xFFFF7FA8) else Color(0xFF7FFFD4),
                                radius = if (tip) 4f else 3f,
                                center = Offset(lm[i * 2] * size.width, lm[i * 2 + 1] * size.height),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        val lines = listOf(
            "render  %.0f fps".format(Locale.US, info.renderFps),
            "track   %.0f fps  (%.0f ms)".format(Locale.US, info.trackingFps, info.inferenceMs),
            "hand    %s  %s".format(Locale.US, if (info.handPresent) "seen" else "lost", info.handedness.name.lowercase()),
            "conf    %.2f".format(Locale.US, info.confidence),
            "gesture %s%s".format(Locale.US, info.gesture.name, if (info.holding) " (holding)" else ""),
            "pinch   %.2f  curl %.2f".format(Locale.US, info.pinchDistance, info.maxCurl),
            "depth   %.2f  (size ×%.2f)".format(Locale.US, info.lift, info.sizeRatio),
            "cube    %s".format(Locale.US, if (info.cubeSleeping) "asleep" else "awake"),
        )
        for (l in lines) Text(l, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
