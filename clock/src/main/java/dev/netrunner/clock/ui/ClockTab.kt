package dev.netrunner.clock.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.clock.system.Reliability
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val SecondsFormat = DateTimeFormatter.ofPattern("ss")
private val DateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)

@Composable
fun ClockTab(onAlarms: () -> Unit) {
    val context = LocalContext.current
    val now by rememberNow(1000L)
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
    val alarms = rememberAlarms()
    val next = alarms.mapNotNull { a -> a.nextTrigger(now)?.let { a to it } }.minByOrNull { it.second }
    val problems = remember(now / 60_000) { Reliability.checks(context).count { it.ok == false } }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SYS.CHRONO // NIGHT CITY", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(time.format(TimeFormat), color = Cp.Yellow, fontFamily = DisplayFont, fontWeight = FontWeight.Black, fontSize = 84.sp)
            Text(
                time.format(SecondsFormat), color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 26.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 20.dp),
            )
        }
        Text(time.format(DateFormat).uppercase(), color = Cp.Cyan, fontFamily = HudFont, fontSize = 16.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(36.dp))

        Column(
            Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f)).clickable(onClick = onAlarms).padding(16.dp)
        ) {
            SectionTitle("PROCHAINE ALARME", Cp.Yellow)
            Spacer(Modifier.height(6.dp))
            if (next == null) {
                Text("Aucune. Tu dors sans filet.", color = Cp.Text, fontFamily = HudFont, fontSize = 18.sp)
            } else {
                val (alarm, at) = next
                val atTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
                val day = when (atTime.toLocalDate()) {
                    LocalDate.now() -> "Aujourd'hui"
                    LocalDate.now().plusDays(1) -> "Demain"
                    else -> atTime.format(DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH)).replaceFirstChar { it.uppercase() }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(atTime.format(TimeFormat), color = Cp.Text, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                    Text(
                        "  $day · ${untilLabel(at - now)}", color = Cp.Cyan, fontFamily = HudFont, fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (alarm.snoozedUntil > now) Text("En répit", color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp)
                else if (alarm.label.isNotEmpty()) Text("« ${alarm.label} »", color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
            }
        }
        if (problems > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "⚠ $problems réglage(s) peuvent empêcher une alarme de sonner : voir CONFIG",
                color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp,
            )
        }
    }
}
