package dev.netrunner.clock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.clock.alarm.Clockwork
import dev.netrunner.clock.data.CountdownTimer
import dev.netrunner.clock.data.TimerStore

@Composable
fun TimerTab() {
    val context = LocalContext.current
    var timers by remember { mutableStateOf(TimerStore.all(context)) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { timers = TimerStore.all(context) }
        TimerStore.listeners += l
        onDispose { TimerStore.listeners -= l }
    }
    val anyRunning = timers.any { it.running }
    val now by rememberNow(if (anyRunning) 250L else 60_000L)
    // Typed digits, read right to left as hh mm ss, like a microwave.
    var digits by remember { mutableStateOf("") }
    val padded = digits.padStart(6, '0')
    val h = padded.take(2).toInt()
    val m = padded.substring(2, 4).toInt()
    val s = padded.takeLast(2).toInt()
    val typedSeconds = h * 3600 + m * 60 + s

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("MINUTEURS", Cp.Yellow)
        Spacer(Modifier.height(10.dp))
        timers.forEach { t -> TimerRow(t, now) }
        if (timers.isNotEmpty()) Spacer(Modifier.height(16.dp))

        SectionTitle("NOUVEAU")
        Text(
            "%02dh %02dm %02ds".format(h, m, s),
            color = if (digits.isEmpty()) Cp.Dim else Cp.Yellow, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 38.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
        )
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫"))
        keys.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    HudButton(k, color = if (k == "⌫") Cp.Red else Cp.Cyan, size = 20.sp, modifier = Modifier.weight(1f)) {
                        digits = when (k) {
                            "⌫" -> digits.dropLast(1)
                            else -> (digits + k).trimStart('0').take(6)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HudButton("▶ LANCER", filled = true, size = 18.sp, modifier = Modifier.fillMaxWidth()) {
            if (typedSeconds > 0) {
                Clockwork.newTimer(context, typedSeconds)
                digits = ""
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionTitle("RACCOURCIS")
        Spacer(Modifier.height(6.dp))
        listOf(listOf(1, 3, 5), listOf(10, 15, 30)).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { min ->
                    HudButton("$min MIN", color = Cp.Yellow, modifier = Modifier.weight(1f)) { Clockwork.newTimer(context, min * 60) }
                }
            }
        }
    }
}

@Composable
private fun TimerRow(t: CountdownTimer, now: Long) {
    val context = LocalContext.current
    val remaining = t.remaining(now)
    val color = if (t.running) Cp.Yellow else Cp.Cyan
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).hudPanel(borderColor = color.copy(alpha = 0.7f)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatDuration(remaining), color = color, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                Text(
                    (t.label.ifEmpty { "Minuteur ${formatDuration(t.totalMs)}" }) + if (!t.running) " · en pause" else "",
                    color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp,
                )
            }
            Box(Modifier.clickable { Clockwork.deleteTimer(context, t.id) }.padding(10.dp)) {
                Text("✕", color = Cp.Red, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (t.running) "❚❚ PAUSE" else "▶ REPRENDRE", color = color, modifier = Modifier.weight(1f)) {
                Clockwork.toggleTimer(context, t.id)
            }
            HudButton("+1 MIN", color = Cp.Cyan) { Clockwork.addMinute(context, t.id) }
            if (!t.running && remaining < t.totalMs) {
                HudButton("↺", color = Cp.Dim) { Clockwork.saveTimer(context, t.reset()) }
            }
        }
    }
}
