@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.netrunner.launcher.ui

import android.Manifest
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.data.Agenda
import dev.netrunner.launcher.data.Contract
import dev.netrunner.launcher.data.Weather
import dev.netrunner.launcher.data.HourForecast
import dev.netrunner.launcher.data.ZoneConditions
import dev.netrunner.launcher.system.DeckLinks
import dev.netrunner.launcher.system.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Weather as "zone conditions" and the next calendar events as "contracts". */
@Composable
fun ZoneWidget() {
    val context = LocalContext.current
    val active = rememberScreenActive()
    var refresh by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    val zone by produceState<ZoneConditions?>(null, active, refresh) {
        while (active) {
            value = withContext(Dispatchers.IO) { Weather.current(context) }
            delay(10 * 60_000L)
        }
    }
    val contracts by produceState(emptyList<Contract>(), active, refresh) {
        while (active) {
            value = withContext(Dispatchers.IO) { Agenda.upcoming(context) }
            delay(5 * 60_000L)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.6f), cut = 10.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Tapping the conditions opens the Netrunner weather app.
        Column(
            Modifier.fillMaxWidth().clickable {
                UiSound.play(context, UiSound.Cue.Tap)
                DeckLinks.openWeather(context)
            },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Text("// CONDITIONS DE ZONE", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        val z = zone
        when {
            z != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(z.icon, color = Cp.Cyan, fontSize = 28.sp, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text("%.0f°C  %s".format(z.tempC, z.label), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "ressenti %.0f° · vent %.0f km/h · humidité %d%%".format(z.feelsC, z.windKmh, z.humidity),
                        color = Cp.Dim,
                        fontFamily = HudFont,
                        fontSize = 11.sp,
                    )
                }
            }
            !Weather.granted(context) -> SmallHudButton("[ AUTORISER LA LOCALISATION ]", Cp.Cyan) {
                ask.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            else -> Text("> en attente d'une position…", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
        }
        zone?.hours?.takeIf { it.isNotEmpty() }?.let { HourStrip(it) }
        }

        Spacer(Modifier.padding(top = 2.dp))
        // Contracts: tap one to open it, "+" or a long press on the title for a new one.
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { DeckLinks.newEvent(context) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("// CONTRATS", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (Agenda.granted(context)) {
                Text(
                    "+ NOUVEAU",
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        UiSound.play(context, UiSound.Cue.Tap)
                        DeckLinks.newEvent(context)
                    },
                )
            }
        }
        when {
            !Agenda.granted(context) -> SmallHudButton("[ RELIER L'AGENDA ]", Cp.Cyan) {
                ask.launch(arrayOf(Manifest.permission.READ_CALENDAR))
            }
            contracts.isEmpty() -> Text("> aucun contrat dans les 48 h", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
            else -> contracts.forEach { c -> ContractRow(c, onClick = { DeckLinks.openEvent(context, c.id) }, onLongClick = { DeckLinks.newEvent(context) }) }
        }
    }
}

@Composable
private fun ContractRow(contract: Contract, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val live = contract.begin <= now
    val whenText = when {
        live -> "EN COURS"
        contract.allDay -> "JOURNÉE"
        else -> DateUtils.formatDateTime(
            context, contract.begin,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        ).uppercase()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (live) "◉" else "◇", color = if (live) Cp.Red else Cp.Cyan, fontSize = 12.sp, modifier = Modifier.width(18.dp))
        Text(
            contract.title,
            color = Cp.Text,
            fontFamily = HudFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(whenText, color = if (live) Cp.Red else Cp.Dim, fontFamily = HudFont, fontSize = 10.sp)
    }
}

/** The next hours under the current conditions: "21H ☁ 12°". */
@Composable
private fun HourStrip(hours: List<HourForecast>) {
    Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        hours.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%02dH ".format(h.hour), color = Cp.Dim, fontFamily = HudFont, fontSize = 10.sp)
                Text(h.icon, color = Cp.Cyan, fontSize = 12.sp)
                Text(" %.0f°".format(h.tempC), color = Cp.Text, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
