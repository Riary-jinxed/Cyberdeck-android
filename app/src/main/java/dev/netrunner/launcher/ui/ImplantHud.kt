package dev.netrunner.launcher.ui

import android.net.TrafficStats
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.system.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Vitals(
    val tempC: Float?,
    val ram: Float,
    val storage: Float,
    val mobileMb: Long,
    val wifiMb: Long,
)

/** The phone's health shown as the player's implants, refreshed every 15 s while visible. */
@Composable
fun ImplantHud() {
    val context = LocalContext.current
    val active = rememberScreenActive()
    val vitals by produceState<Vitals?>(null, active) {
        while (active) {
            value = withContext(Dispatchers.Default) {
                val mem = SystemInfo.memory(context)
                val disk = SystemInfo.storage()
                val mobile = (TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()).coerceAtLeast(0)
                val total = (TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()).coerceAtLeast(0)
                Vitals(
                    tempC = SystemInfo.battery(context).temperatureC,
                    ram = 1f - mem.availBytes.toFloat() / mem.totalBytes.coerceAtLeast(1),
                    storage = 1f - disk.availBytes.toFloat() / disk.totalBytes.coerceAtLeast(1),
                    mobileMb = mobile / 1_000_000,
                    wifiMb = (total - mobile).coerceAtLeast(0) / 1_000_000,
                )
            }
            delay(15_000)
        }
    }
    val v = vitals ?: return
    val hot = (v.tempC ?: 0f) >= 42f
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = if (hot) Cp.Red else Cp.Cyan.copy(alpha = 0.5f), cut = 10.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("// BIOMONITEUR", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (hot) "SURCHAUFFE" else "NOMINAL",
                color = if (hot) Cp.Red else Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        v.tempC?.let { t ->
            Implant("CŒUR THERMIQUE", "%.0f°C".format(t), ((t - 20f) / 30f).coerceIn(0f, 1f), alertAbove = 0.73f)
        }
        Implant("NEUROPROCESSEUR", "RAM ${(v.ram * 100).toInt()}%", v.ram, alertAbove = 0.9f)
        Implant("BANQUE MÉMOIRE", "${(v.storage * 100).toInt()}%", v.storage, alertAbove = 0.92f)
        Text(
            "LIAISON  données ${v.mobileMb} Mo · wi-fi ${v.wifiMb} Mo (depuis démarrage)",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun Implant(name: String, value: String, level: Float, alertAbove: Float) {
    val color: Color = if (level >= alertAbove) Cp.Red else Cp.Cyan
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Cp.Text, fontFamily = HudFont, fontSize = 11.sp, modifier = Modifier.width(130.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val segments = 16
            val filled = (level * segments).toInt().coerceIn(0, segments)
            repeat(segments) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(if (i < filled) color else color.copy(alpha = 0.15f))
                )
            }
        }
        Text(value, color = color, fontFamily = HudFont, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp).width(64.dp))
    }
}
