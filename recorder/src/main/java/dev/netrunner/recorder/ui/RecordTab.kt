package dev.netrunner.recorder.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.DisplayFont
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.scanlines
import dev.netrunner.recorder.data.Wave
import dev.netrunner.recorder.system.Recorder

/** The recording screen: live waveform, timer and the REC / pause / stop keys. */
@Composable
fun RecordTab(onSaved: () -> Unit) {
    val context = LocalContext.current
    val status = Recorder.status
    val live = Recorder.recent
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (status) {
                Recorder.Status.IDLE -> "// PRÊT"
                Recorder.Status.RECORDING -> "● ENREGISTREMENT"
                Recorder.Status.PAUSED -> "❚❚ EN PAUSE"
            },
            color = if (status == Recorder.Status.RECORDING) Cp.Red else Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold,
            fontSize = 16.sp, letterSpacing = 3.sp, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Text(Wave.time(Recorder.elapsed), color = Cp.Text, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 64.sp)
        Spacer(Modifier.height(24.dp))
        // Newest level on the right; the strip fills from the right while it is short.
        val bars = IntArray(Recorder.LIVE_BARS) { i -> live.getOrElse(i - (Recorder.LIVE_BARS - live.size)) { 0 } }
        WaveBars(
            bars,
            Modifier.fillMaxWidth().height(160.dp).hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 10.dp)
                .scanlines(Cp.Background.copy(alpha = 0.25f), 3.dp).padding(12.dp),
            color = if (status == Recorder.Status.PAUSED) Cp.Dim else Cp.Cyan,
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (status) {
                Recorder.Status.IDLE -> HudButton("● REC", color = Cp.Red, filled = true, size = 22.sp, modifier = Modifier.weight(1f)) {
                    try {
                        Recorder.start(context)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Micro indisponible : ${e.message ?: "erreur"}", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {
                    HudButton(if (status == Recorder.Status.PAUSED) "▶ REPRENDRE" else "❚❚ PAUSE", color = Cp.Yellow, modifier = Modifier.weight(1f)) {
                        if (status == Recorder.Status.PAUSED) Recorder.resume() else Recorder.pause()
                    }
                    HudButton("■ STOP", color = Cp.Cyan, filled = true, modifier = Modifier.weight(1f)) {
                        val f = Recorder.stop(context)
                        Toast.makeText(context, if (f != null) "Enregistré : ${f.nameWithoutExtension}" else "Rien n'a été enregistré", Toast.LENGTH_SHORT).show()
                        if (f != null) onSaved()
                    }
                }
            }
        }
        if (status != Recorder.Status.IDLE) {
            Text(
                "JETER", color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp).clickable { Recorder.discard(context) },
            )
        }
        Text(
            "L'enregistrement continue écran éteint ; une notification permet de l'arrêter.",
            color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}
