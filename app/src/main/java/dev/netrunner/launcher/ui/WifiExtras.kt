package dev.netrunner.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.terminal.LanInfo
import dev.netrunner.launcher.terminal.NetTools
import dev.netrunner.launcher.wifi.AccessPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ---------------------------------------------------------------------------------- channels

/** How much an access point weighs on a channel: stronger and closer ones count more. */
private fun weight(ap: AccessPoint) = ((ap.rssi + 100).coerceIn(0, 70)) / 70f

/** 2.4 GHz channels overlap over ±4 channels (20 MHz wide, 5 MHz apart). */
private fun load24(points: List<AccessPoint>, channel: Int): Float =
    points.filter { it.band == "2.4 GHz" && abs(it.channel - channel) <= 4 }
        .sumOf { (weight(it) * (1f - abs(it.channel - channel) / 5f)).toDouble() }.toFloat()

private fun loadOn(points: List<AccessPoint>, band: String, channel: Int): Float =
    points.filter { it.band == band && it.channel == channel }.sumOf { weight(it).toDouble() }.toFloat()

private val Channels24 = (1..13).toList()
private val Channels5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161, 165)
private val Dfs = 52..144

/**
 * Channel occupancy from the passive scan (beacons the phone already hears), with a suggested
 * channel per band. Nothing is sent: it only reads the list Android already has.
 */
@Composable
internal fun ChannelView(points: List<AccessPoint>) {
    val best24 = listOf(1, 6, 11).minBy { load24(points, it) }
    val best5 = Channels5.filter { it !in Dfs }.minBy { loadOn(points, "5 GHz", it) }
    val best5Dfs = Channels5.minBy { loadOn(points, "5 GHz", it) }
    val six = points.filter { it.band == "6 GHz" }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            BandChart(
                title = "2,4 GHZ · ${points.count { it.band == "2.4 GHz" }} BORNES",
                channels = Channels24,
                load = { load24(points, it) },
                best = best24,
            )
        }
        item {
            Text(
                "> conseil 2,4 GHz : canal $best24 (seuls 1, 6 et 11 ne se chevauchent pas)",
                color = Cp.Yellow,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        item {
            BandChart(
                title = "5 GHZ · ${points.count { it.band == "5 GHz" }} BORNES",
                channels = Channels5,
                load = { loadOn(points, "5 GHz", it) },
                best = best5,
                dfs = Dfs,
            )
        }
        item {
            Text(
                "> conseil 5 GHz : canal $best5 (sans DFS)" + if (best5Dfs != best5 && loadOn(points, "5 GHz", best5Dfs) < loadOn(points, "5 GHz", best5)) ", ou $best5Dfs en DFS" else "",
                color = Cp.Yellow,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        if (six.isNotEmpty()) {
            item {
                Text(
                    "6 GHZ : " + six.groupBy { it.channel }.toSortedMap().entries.joinToString("  ") { "ch${it.key}×${it.value.size}" },
                    color = Cp.Cyan,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }
        item {
            Text(
                "Barres : nombre et force des bornes entendues sur chaque canal (zone rouge = DFS, radar). Les scans sont limités par Android : attends quelques passes pour une vue complète.",
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun BandChart(title: String, channels: List<Int>, load: (Int) -> Float, best: Int, dfs: IntRange? = null) {
    val loads = channels.associateWith(load)
    val max = (loads.values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
            .padding(10.dp)
    ) {
        Text(title, color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().height(80.dp).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            channels.forEach { ch ->
                val v = loads[ch] ?: 0f
                val color = when {
                    ch == best -> Cp.Yellow
                    dfs != null && ch in dfs -> Cp.Red.copy(alpha = 0.7f)
                    else -> Cp.Cyan
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((v / max).coerceIn(0.03f, 1f))
                            .background(color.copy(alpha = if (v > 0f || ch == best) 0.85f else 0.25f))
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            channels.forEach { ch ->
                Text(
                    ch.toString(),
                    color = if (ch == best) Cp.Yellow else Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = if (channels.size > 16) 7.sp else 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------- connection

/** The phone's own Wi-Fi link and the addresses its DHCP gave it. Read locally, refreshed every 2 s. */
@Composable
internal fun ConnectionView(points: List<AccessPoint>) {
    val context = LocalContext.current
    var lan by remember { mutableStateOf<LanInfo?>(null) }
    var speed by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val active = rememberScreenActive()
    LaunchedEffect(active) {
        while (active) {
            lan = withContext(Dispatchers.IO) { NetTools.lanInfo(context) }
            speed = NetTools.wifiSignal(context)
            delay(2000)
        }
    }
    val ap = points.firstOrNull { it.connected }
    val rows = buildList {
        if (ap != null) {
            add("SSID" to ap.ssid.ifBlank { "(masqué)" })
            add("BSSID" to ap.bssid)
            add("Canal" to "${ap.channel} · ${ap.band} · ${ap.frequencyMhz} MHz")
            add("Norme / sécurité" to listOf(ap.standard, ap.security).filter { it.isNotBlank() }.joinToString(" · "))
        }
        speed?.let { (rssi, link) ->
            add("Signal" to "$rssi dBm · ${quality(rssi)}")
            add("Débit de liaison" to "$link Mbit/s")
        }
        lan?.let { l ->
            add("Interface" to l.interfaceName + if (l.onWifi) " (Wi-Fi)" else " (mobile)")
            add("Adresse IP" to "${l.address}/${l.prefixLength}")
            add("Passerelle" to (l.gateway ?: "?"))
            add("DNS" to l.dns.joinToString(", ").ifBlank { "?" })
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (rows.isEmpty()) {
            item { Text("> aucune connexion active", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp) }
        }
        rows.forEach { (k, v) ->
            item(key = k) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f), cut = 4.dp)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(k.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, modifier = Modifier.width(120.dp))
                    Text(v, color = Cp.Cyan, fontFamily = MonoFont, fontSize = 12.sp)
                }
            }
        }
        item {
            Text(
                "Informations lues sur le téléphone lui-même : aucune requête n'est envoyée sur le réseau.",
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun quality(rssi: Int) = when {
    rssi >= -55 -> "excellent"
    rssi >= -67 -> "bon (VoIP OK)"
    rssi >= -75 -> "moyen"
    else -> "faible"
}
