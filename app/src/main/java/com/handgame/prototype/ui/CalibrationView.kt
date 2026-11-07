package com.handgame.prototype.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handgame.prototype.game.GameUi
import com.handgame.prototype.input.CalibrationSession
import com.handgame.prototype.tracking.Lm
import kotlin.math.hypot

/**
 * Calibration over the live camera view (the only time the camera is shown): frame your hand
 * inside the guide, then pinch the targets that pop up to set your play area.
 * The camera image is shown mirrored for the front camera, so it behaves like a mirror.
 */
@Composable
fun CalibrationView(ui: GameUi, frame: Bitmap?, onSkip: () -> Unit, onPullIn: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    val mirror = ui.frontCamera
    val holding = ui.calibrationStep == CalibrationSession.Step.HOLD
    val aspect = frame?.let { it.width.toFloat() / it.height } ?: (3f / 4f)

    Box(Modifier.fillMaxSize().background(Color(0xFF15111F))) {
        Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(56.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(aspect)) {
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Camera view",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirror) -1f else 1f },
                    )
                } else {
                    Text("Starting camera…", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                Canvas(Modifier.fillMaxSize()) {
                    fun pt(u: Float, v: Float) = Offset(u * size.width, v * size.height)
                    // Hand skeleton, mirrored to match the image.
                    ui.calibrationLandmarks?.let { lm ->
                        fun lu(i: Int) = if (mirror) 1f - lm[i * 2] else lm[i * 2]
                        for (b in Lm.BONES) drawLine(Color(0xCCFFFFFF), pt(lu(b[0]), lm[b[0] * 2 + 1]), pt(lu(b[1]), lm[b[1] * 2 + 1]), strokeWidth = 4f)
                        for (i in 0 until Lm.COUNT) drawCircle(Color(0xFF7FFFD4), 6f, pt(lu(i), lm[i * 2 + 1]))
                    }
                    if (holding) {
                        // Guide box: where a comfortably-sized open hand should sit.
                        val ok = ui.calibrationHint == CalibrationSession.Hint.NONE && ui.calibrationLandmarks != null
                        val color = if (ok) Color(0xFF6BE3A4) else Color(0xFFFFC46B)
                        val w = size.width * 0.5f; val h = size.height * 0.6f
                        drawRoundRect(
                            color, topLeft = Offset((size.width - w) / 2, size.height * 0.15f), size = Size(w, h),
                            cornerRadius = CornerRadius(40f, 40f),
                            style = Stroke(width = 6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 20f))),
                        )
                    } else {
                        val target = ui.calibrationTarget
                        val cursor = ui.calibrationCursor
                        if (target != null) {
                            val r = CalibrationSession.TARGET_RADIUS * size.width
                            val inside = cursor != null && hypot(cursor.first - target.first, cursor.second - target.second) < CalibrationSession.TARGET_RADIUS
                            val c = pt(target.first, target.second)
                            drawCircle(Color(0x55FFD25A), r * pulse, c)
                            drawCircle(if (inside) Color(0xFF6BE3A4) else Color(0xFFFFD25A), r, c, style = Stroke(8f))
                        }
                        if (cursor != null) {
                            drawCircle(Color.White, 18f, pt(cursor.first, cursor.second), style = Stroke(6f))
                            drawCircle(Color(0xFFFF7FA8), 6f, pt(cursor.first, cursor.second))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            CalibrationPanel(ui, holding, onSkip, onPullIn)
        }
    }
}

@Composable
private fun CalibrationPanel(ui: GameUi, holding: Boolean, onSkip: () -> Unit, onPullIn: () -> Unit) {
    Column(
        Modifier.padding(16.dp).fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (holding) {
            Text("Frame your hand", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (ui.frontCamera) "Hold your open hand up, palm toward the screen, fingers up, so it sits inside the box. Keep it still."
                else "Reach behind the phone and hold your open hand up, palm away from you, inside the box. Keep it still.",
                color = Ink, textAlign = TextAlign.Center,
            )
            val hint = when (ui.calibrationHint) {
                CalibrationSession.Hint.NEAR_EDGE -> "Part of your hand is near the edge. Move it toward the middle."
                CalibrationSession.Hint.TOO_CLOSE -> "A little farther from the camera, please. It gives you more room to move."
                CalibrationSession.Hint.TOO_FAR -> "A little closer to the camera."
                CalibrationSession.Hint.NONE -> if (ui.calibrationLandmarks == null) "Show your hand to the camera." else null
            }
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(hint, color = Color(0xFFB0406A), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            }
        } else {
            val first = ui.calibrationTargetIndex == 0
            Text(
                if (first) "Pinch the target" else "Target ${ui.calibrationTargetIndex + 1} of ${ui.calibrationTargetCount}",
                color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (first) "Move the white ring (your pinch point) onto the glowing target and pinch thumb and index together."
                else "Reach this corner comfortably and pinch. These corners become the edges of your play area.",
                color = Ink, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { ui.calibrationProgress }, modifier = Modifier.fillMaxWidth(), color = Accent)
        if (!holding) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (ui.calibrationTargetIndex > 0) OutlinedButton(onClick = onPullIn) { Text("Can't reach") }
                TextButton(onClick = onSkip) { Text("Skip, use default area") }
            }
        }
    }
}
