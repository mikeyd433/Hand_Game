package com.handgame.prototype

import com.handgame.prototype.tuning.Recording
import com.handgame.prototype.tuning.TuningAnalyzer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Replays real recordings from the repo's recordings/ folder through the current game code and
 * prints a report for each. Used to tune the game against a player's actual hand data:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*' -i
 */
class RecordingReplayTest {
    @Test fun replayRecordings() {
        val dir = File("../recordings")
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl.gz") || f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no recordings in ${dir.absolutePath}", files.isNotEmpty())
        for (f in files) {
            val text = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()).bufferedReader().readText() else f.readText()
            val report = TuningAnalyzer().analyze(Recording.fromJsonLines(text))
            println("===== ${f.name}\n${report.text}\n")
        }
    }
}
