package com.handgame.prototype.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handgame.prototype.game.TutorialUi

/** The tutorial's lesson card: which move, what to do, progress dots, and a "well done" moment. */
@Composable
fun TutorialOverlay(t: TutorialUi, onSkip: () -> Unit, onLeave: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (t.finished) {
                Text("You're ready!", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(t.prompt, color = Ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onLeave) { Text("Start playing") }
                return@Column
            }
            // Progress dots for the six lessons.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 1..t.count) {
                    val c = when {
                        i < t.number || (i == t.number && t.celebrating) -> Accent
                        i == t.number -> Color(0xFFFFC46B)
                        else -> Color(0x33302840)
                    }
                    Box(Modifier.size(10.dp).background(c, CircleShape))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Lesson ${t.number} of ${t.count}", color = Ink.copy(alpha = 0.6f), fontSize = 12.sp)
            Text(if (t.celebrating) "✓ ${t.title}: well done!" else t.title,
                color = if (t.celebrating) Color(0xFF2E9E6E) else Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            AnimatedVisibility(!t.celebrating, enter = fadeIn(), exit = fadeOut()) {
                Text(t.prompt, color = Ink, textAlign = TextAlign.Center)
            }
            if (t.progress > 0f && !t.celebrating) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSkip) { Text("Skip this one") }
                TextButton(onClick = onLeave) { Text("Leave tutorial") }
            }
        }
    }
}
