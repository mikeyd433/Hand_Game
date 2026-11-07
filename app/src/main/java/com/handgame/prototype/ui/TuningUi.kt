package com.handgame.prototype.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class TuningMode { IDLE, GUIDED, FREE, RESULTS }

/** UI snapshot of the tuning recorder. */
data class TuningUi(
    val mode: TuningMode = TuningMode.IDLE,
    val stepTitle: String = "",
    val prompt: String = "",
    val cue: String = "",
    val stepProgress: Float = 0f,
    val stepNumber: Int = 0,
    val stepCount: Int = 0,
    val elapsed: Float = 0f,
    val marks: Int = 0,
    val objectiveDone: Boolean = false,
    val summary: String = "",
)

/** Issue categories for the free-play "something felt wrong" button. */
val ISSUE_KINDS = listOf(
    "jitter" to "Jittery",
    "missed_grab" to "Missed grab",
    "false_grab" to "Grabbed by itself",
    "dropped" to "Dropped the cube",
    "lost_hand" to "Lost my hand",
    "lag" to "Laggy",
    "depth" to "Lift felt wrong",
    "shape" to "Hand looked wrong",
    "other" to "Other",
)

@Composable
fun TuningOverlay(
    state: TuningUi,
    onStop: () -> Unit,
    onMark: (String) -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    when (state.mode) {
        TuningMode.IDLE -> Unit
        TuningMode.GUIDED -> GuidedCard(state, onStop)
        TuningMode.FREE -> FreeRecordingBar(state, onStop, onMark)
        TuningMode.RESULTS -> ResultsPanel(state, onSave, onCopy, onClose)
    }
}

@Composable
private fun GuidedCard(s: TuningUi, onStop: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tuning test · step ${s.stepNumber} of ${s.stepCount}", color = Ink.copy(alpha = 0.6f), fontSize = 12.sp)
            Text(if (s.objectiveDone) "✓ ${s.stepTitle}" else s.stepTitle, color = if (s.objectiveDone) Color(0xFF2E9E6E) else Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(s.prompt, color = Ink, textAlign = TextAlign.Center)
            if (s.cue.isNotEmpty() && s.stepTitle in listOf("Pinch", "Fist", "Lift")) {
                Spacer(Modifier.height(6.dp))
                Text(
                    s.cue.ifEmpty { "…" },
                    color = if (s.cue == "OPEN") Ink else Accent,
                    fontSize = 40.sp, fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { s.stepProgress }, modifier = Modifier.fillMaxWidth(), color = Accent)
            TextButton(onClick = onStop) { Text("Stop test") }
        }
    }
}

@Composable
private fun FreeRecordingBar(s: TuningUi, onStop: () -> Unit, onMark: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (choosing) {
                Column(
                    Modifier.background(Panel, RoundedCornerShape(20.dp)).padding(10.dp)
                        .heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text("What felt wrong just now?", color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(6.dp))
                    for ((kind, label) in ISSUE_KINDS) {
                        TextButton(onClick = { onMark(kind); choosing = false; flash = label }) { Text(label, color = Ink) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(
                Modifier.background(Panel, RoundedCornerShape(28.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("● REC %d:%02d".format(s.elapsed.toInt() / 60, s.elapsed.toInt() % 60), color = Color(0xFFD0405E), fontWeight = FontWeight.Bold)
                Button(onClick = { choosing = !choosing }) { Text("⚑ Mark issue") }
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
            val note = if (flash.isNotEmpty()) "Marked: $flash (${s.marks} total)" else "Play normally. Tap ⚑ right after anything feels off."
            Text(note, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ResultsPanel(s: TuningUi, onSave: () -> Unit, onCopy: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99302840)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp).background(Cream, RoundedCornerShape(24.dp)).padding(16.dp)) {
            Text("Tuning results", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Copy the summary into the chat, or save the recording and upload the file for a full replay.",
                color = Ink.copy(alpha = 0.7f), fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(max = 380.dp).background(Color(0xFFF3ECF8), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp),
            ) {
                Text(s.summary, color = Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy) { Text("Copy summary") }
                OutlinedButton(onClick = onSave) { Text("Save recording…") }
            }
            TextButton(onClick = onClose, modifier = Modifier.width(120.dp)) { Text("Close") }
        }
    }
}
