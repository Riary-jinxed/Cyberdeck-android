package dev.netrunner.clock.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.clock.data.Stopwatch
import kotlinx.coroutines.delay

/** 01:02:03.45 or 02:03.45 */
private fun chrono(ms: Long): String {
    val cs = ms / 10 % 100
    val s = ms / 1000 % 60
    val m = ms / 60_000 % 60
    val h = ms / 3_600_000
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs) else "%02d:%02d.%02d".format(m, s, cs)
}

@Composable
fun StopwatchTab() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(Stopwatch.load(context)) }
    fun update(s: Stopwatch.State) {
        state = s
        Stopwatch.save(context, s)
    }
    val active = rememberScreenActive()
    // ~30 fps only while running and visible.
    val now by produceState(SystemClock.elapsedRealtime(), state.running, active) {
        value = SystemClock.elapsedRealtime()
        while (state.running && active) {
            delay(33)
            value = SystemClock.elapsedRealtime()
        }
    }
    val elapsed = state.elapsed(now)

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionTitle("CHRONO", Cp.Yellow, Modifier.fillMaxWidth())
        Spacer(Modifier.height(40.dp))
        Text(chrono(elapsed), color = if (state.running) Cp.Yellow else Cp.Text, fontFamily = DisplayFont, fontWeight = FontWeight.Black, fontSize = 52.sp)
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.running) {
                HudButton("TOUR", color = Cp.Cyan, size = 18.sp, modifier = Modifier.weight(1f)) { update(Stopwatch.lap(state)) }
            } else {
                HudButton("RÉINIT.", color = Cp.Red, size = 18.sp, modifier = Modifier.weight(1f)) { update(Stopwatch.reset()) }
            }
            HudButton(if (state.running) "❚❚ PAUSE" else "▶ GO", filled = true, size = 18.sp, modifier = Modifier.weight(1f)) {
                update(Stopwatch.toggle(state))
            }
        }
        Spacer(Modifier.height(20.dp))
        val laps = state.laps
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(laps.reversed()) { i, total ->
                val index = laps.size - i
                val split = total - laps.getOrElse(index - 2) { 0L }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("TOUR %02d".format(index), color = Cp.Cyan, fontFamily = MonoFont, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("+" + chrono(split), color = Cp.Dim, fontFamily = MonoFont, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(chrono(total), color = Cp.Text, fontFamily = MonoFont, fontSize = 16.sp)
                }
            }
        }
    }
}
