package dev.netrunner.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.netrunner.launcher.system.BatteryLog
import dev.netrunner.launcher.system.DataUsage
import dev.netrunner.launcher.system.Ice
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import dev.netrunner.launcher.system.SystemInfo
import dev.netrunner.launcher.system.UiSound
import dev.netrunner.launcher.system.UsageReport
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class StatsData(val usage: UsageReport.Report?, val data: DataUsage.Report?, val battery: List<BatteryLog.Day>)

private fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
}

/** Usage statistics, HUD style: screen time, unlocks, most used apps and battery per day. */
@Composable
fun StatsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var week by remember { mutableStateOf(false) }
    // Bumped on resume, so the numbers refresh after granting access or using other apps.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val data by produceState<StatsData?>(null, tick) {
        value = withContext(Dispatchers.IO) {
            val usage = UsageReport.load(context)
            val dates = usage?.days?.map { it.date }
                ?: (UsageReport.DAYS - 1 downTo 0).map { java.time.LocalDate.now().minusDays(it.toLong()) }
            StatsData(usage, DataUsage.load(context), BatteryLog.days(context, dates))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelHeader(title = "// STATS DU DECK", accent = Cp.Yellow, onClose = onClose)
        val stats = data
        when {
            stats == null -> Text("> analyse des journaux…", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
            stats.usage == null -> AccessCard()
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AUJOURD'HUI" to false, "7 JOURS" to true).forEach { (label, value) ->
                        val on = week == value
                        Text(
                            label,
                            color = if (on) Cp.Background else Cp.Cyan,
                            fontFamily = HudFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.7f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 6.dp)
                                .clickable {
                                    UiSound.play(context, UiSound.Cue.Tap)
                                    week = value
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                UsageSection(stats.usage, week)
                stats.data?.let { DataSection(it, week) }
            }
        }
        if (stats != null) BatterySection(stats.battery)
    }
}

@Composable
private fun AccessCard() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Red.copy(alpha = 0.8f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ACCÈS REFUSÉ", color = Cp.Red, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "Le temps d'écran vient de l'historique d'usage d'Android. Active Netrunner dans « Accès aux données d'utilisation ». Tout reste sur le téléphone.",
            color = Cp.Text,
            fontFamily = HudFont,
            fontSize = 12.sp,
        )
        Text(
            "[ AUTORISER ]",
            color = Cp.Yellow,
            fontFamily = HudFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { UsageReport.openAccessSettings(context) }.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun UsageSection(report: UsageReport.Report, week: Boolean) {
    val days = report.days
    val screen = if (week) days.sumOf { it.screenMs } / days.size else report.today.screenMs
    val unlocks = if (week) days.sumOf { it.unlocks } / days.size else report.today.unlocks
    val opens = if (week) days.sumOf { it.opens } / days.size else report.today.opens
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(if (week) "ÉCRAN / JOUR" else "TEMPS D'ÉCRAN", duration(screen), Cp.Yellow, Modifier.weight(1.3f))
        StatTile(if (week) "DÉVERR. / JOUR" else "DÉVERROUILL.", unlocks.toString(), Cp.Cyan, Modifier.weight(1f))
        StatTile(if (week) "OUVERT. / JOUR" else "OUVERTURES", opens.toString(), Cp.Cyan, Modifier.weight(1f))
    }

    Label("TEMPS D'ÉCRAN, 7 JOURS")
    DayBars(
        values = days.map { it.screenMs.toFloat() },
        labels = days.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.FRENCH) },
        captions = days.map { duration(it.screenMs).replace(" min", "m").replace(" h ", "h") },
        color = Cp.Yellow,
    )

    val apps = (if (week) report.weekApps else report.today.apps).take(8)
    Label(if (week) "APPLIS LES PLUS UTILISÉES, 7 JOURS" else "APPLIS LES PLUS UTILISÉES, AUJOURD'HUI")
    if (apps.isEmpty()) {
        Text("> aucune activité enregistrée", color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
    }
    val max = apps.maxOfOrNull { it.foregroundMs }?.coerceAtLeast(1L) ?: 1L
    // Today's time per app, to colour the ICE badge once a limit is used up.
    val todayMs = report.today.apps.associate { it.packageName to it.foregroundMs }
    apps.forEach { AppRow(it, it.foregroundMs.toFloat() / max, todayMs[it.packageName] ?: 0L) }
    Text(
        "> touche une appli pour lui poser une limite ICE (temps max par jour)",
        color = Cp.Dim,
        fontFamily = HudFont,
        fontSize = 11.sp,
    )
}

@Composable
private fun StatTile(title: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .hudPanel(borderColor = color.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(title, color = Cp.Dim, fontFamily = HudFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(value, color = color, fontFamily = HudFont, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

/** Seven vertical bars, today (the last one) highlighted, with the value above and the day below. */
@Composable
private fun DayBars(values: List<Float>, labels: List<String>, captions: List<String>, color: Color) {
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val dim = Cp.Dim
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = color.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            captions.forEachIndexed { i, c ->
                Text(
                    c,
                    color = if (i == captions.lastIndex) color else dim,
                    fontFamily = HudFont,
                    fontSize = 9.sp,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(70.dp)
                .padding(vertical = 4.dp)
        ) {
            val n = values.size
            val slot = size.width / n
            val w = slot * 0.55f
            val segment = 4.dp.toPx()
            values.forEachIndexed { i, v ->
                val h = size.height * (v / max).coerceIn(0.02f, 1f)
                val x = i * slot + (slot - w) / 2
                val c = if (i == n - 1) color else color.copy(alpha = 0.45f)
                // Segmented bar, like the in-game gauges.
                var y = size.height
                while (y > size.height - h) {
                    val top = maxOf(size.height - h, y - segment + 1.dp.toPx())
                    drawRect(c, Offset(x, top), Size(w, y - top))
                    y -= segment
                }
            }
            drawLine(dim, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l.uppercase(),
                    color = if (i == labels.lastIndex) color else dim,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: UsageReport.AppUsage, share: Float, todayMs: Long) {
    val context = LocalContext.current
    remember { Ice.load(context.applicationContext) }
    var menu by remember { mutableStateOf(false) }
    val limit = Ice.limits[app.packageName]
    val icon = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    val bar = Cp.Yellow
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = if (limit != null) Cp.Red.copy(alpha = 0.6f) else Cp.Cyan.copy(alpha = 0.4f), cut = 8.dp)
                .clickable { menu = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            } else {
                Box(Modifier.size(26.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label.uppercase(),
                        color = Cp.Text,
                        fontFamily = HudFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(duration(app.foregroundMs), color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                    ) {
                        drawRect(bar.copy(alpha = 0.15f))
                        drawRect(bar, size = Size(size.width * share.coerceIn(0.02f, 1f), size.height))
                    }
                    Text("  ×${app.opens}", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
                    if (limit != null) {
                        val over = todayMs >= limit * 60_000L
                        Text(
                            "  ICE ${limit}m",
                            color = if (over) Cp.Red else Cp.Red.copy(alpha = 0.7f),
                            fontFamily = HudFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                "LIMITE ICE / JOUR",
                color = Cp.Dim,
                fontFamily = HudFont,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            (listOf<Int?>(null) + Ice.choices).forEach { minutes ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (minutes == null) "AUCUNE" else duration(minutes * 60_000L).uppercase(),
                            color = if (minutes == limit) Cp.Yellow else Cp.Cyan,
                            fontFamily = HudFont,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        Ice.setLimit(context, app.packageName, minutes)
                        UiSound.play(context, UiSound.Cue.Confirm)
                        menu = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DataSection(report: DataUsage.Report, week: Boolean) {
    val days = report.days
    val today = days.last()
    val wifi = if (week) days.sumOf { it.wifi } else today.wifi
    val mobile = if (week) days.sumOf { it.mobile } else today.mobile
    Label(if (week) "DATA CONSOMMÉE, 7 JOURS" else "DATA CONSOMMÉE, AUJOURD'HUI")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("MOBILE", DataUsage.format(mobile), Cp.Yellow, Modifier.weight(1f))
        StatTile("WI-FI", DataUsage.format(wifi), Cp.Cyan, Modifier.weight(1f))
    }
    DayBars(
        values = days.map { it.mobile.toFloat() },
        labels = days.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.FRENCH) },
        captions = days.map { DataUsage.format(it.mobile).replace(" ", "") },
        color = Cp.Yellow,
    )
    Text("> barres : données mobiles par jour", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
    val apps = (if (week) report.week else report.today).take(6)
    val max = apps.maxOfOrNull { it.total }?.coerceAtLeast(1L) ?: 1L
    apps.forEach { app ->
        Row(
            Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 8.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label.uppercase(),
                        color = Cp.Text,
                        fontFamily = HudFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(DataUsage.format(app.total), color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                val share = app.total.toFloat() / max
                val mobileShare = if (app.total > 0) app.mobile.toFloat() / app.total else 0f
                val yellow = Cp.Yellow
                val cyan = Cp.Cyan
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                ) {
                    drawRect(cyan.copy(alpha = 0.15f))
                    val w = size.width * share.coerceIn(0.02f, 1f)
                    drawRect(yellow, size = Size(w * mobileShare, size.height))
                    drawRect(cyan, topLeft = Offset(w * mobileShare, 0f), size = Size(w * (1 - mobileShare), size.height))
                }
                Text(
                    "mobile ${DataUsage.format(app.mobile)} · wi-fi ${DataUsage.format(app.wifi)}",
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun BatterySection(days: List<BatteryLog.Day>) {
    val context = LocalContext.current
    val now = remember { SystemInfo.battery(context) }
    val today = days.last()
    Label("BATTERIE")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("NIVEAU", if (now.level in 0..100) "${now.level} %" else "?", if (now.level in 0..19) Cp.Red else Cp.Yellow, Modifier.weight(1f))
        StatTile("CONSOMMÉ AUJ.", "−${today.drained} %", Cp.Red, Modifier.weight(1f))
        StatTile("RECHARGÉ AUJ.", "+${today.charged} %", Cp.Cyan, Modifier.weight(1f))
    }
    if (days.all { it.drained == 0 && it.charged == 0 }) {
        Text(
            "> historique en cours de collecte (il se remplit au fil des jours, avec l'accès aux notifications actif)",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
    } else {
        Label("BATTERIE CONSOMMÉE, 7 JOURS")
        DayBars(
            values = days.map { it.drained.toFloat() },
            labels = days.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.FRENCH) },
            captions = days.map { "${it.drained}%" },
            color = Cp.Red,
        )
    }
}
