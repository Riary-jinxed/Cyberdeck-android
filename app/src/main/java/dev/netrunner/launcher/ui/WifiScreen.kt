package dev.netrunner.launcher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netrunner.launcher.wifi.AccessPoint
import dev.netrunner.launcher.wifi.WifiScanner
import kotlinx.coroutines.delay
import java.util.Locale

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasPermissions(context: Context) = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun locationEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

@Composable
fun WifiScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scanner = remember { WifiScanner(context) }
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var locationOn by remember { mutableStateOf(locationEnabled(context)) }
    var tracked by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(context)
    }
    LifecycleResumeEffect(Unit) {
        granted = hasPermissions(context)
        locationOn = locationEnabled(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionRequest.launch(requiredPermissions())
    }
    DisposableEffect(granted) {
        if (granted) scanner.start(scope)
        onDispose { scanner.stop() }
    }

    BackHandler(enabled = tracked != null) { tracked = null }

    val points by scanner.points.collectAsStateWithLifecycle()
    val throttled by scanner.throttled.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        PanelHeader(
            title = if (tracked == null) "// SCANNER WI-FI : ${points.size} BORNES" else "// TRAQUE DE BORNE",
            accent = Cp.Cyan,
            onClose = onClose,
        )
        Spacer(Modifier.height(8.dp))

        when {
            !granted -> Notice(
                "Android ne donne la liste des bornes qu'aux applis autorisées à la localisation. Rien n'est envoyé nulle part.",
                "[ AUTORISER ]",
            ) { permissionRequest.launch(requiredPermissions()) }

            !locationOn -> Notice(
                "La localisation du téléphone est coupée : sans elle, Android renvoie une liste vide.",
                "[ ACTIVER LA LOCALISATION ]",
            ) {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            !scanner.wifiEnabled -> Notice("Le Wi-Fi est désactivé. Active-le pour scanner les bornes.", "[ RÉGLAGES WI-FI ]") {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            tracked != null -> {
                val ap = points.firstOrNull { it.bssid == tracked }
                Tracker(ap = ap, bssid = tracked!!, history = scanner.historyOf(tracked!!), throttled = throttled)
            }

            else -> {
                if (throttled) {
                    Text(
                        "⚠ Android limite les scans (4 / 2 min). Pour un suivi en direct : Options développeur › désactive « Limitation de la recherche Wi-Fi ».",
                        color = Cp.Yellow,
                        fontFamily = HudFont,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                FilterField(filter) { filter = it }
                Spacer(Modifier.height(8.dp))
                val shown = points.filter {
                    filter.isBlank() || it.ssid.contains(filter, true) || it.bssid.contains(filter, true)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.bssid }) { ap ->
                        AccessPointRow(ap) { tracked = ap.bssid }
                    }
                }
            }
        }
    }
}

@Composable
fun PanelHeader(title: String, accent: Color, onClose: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = accent,
            fontFamily = HudFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
        Text(
            "[ X ]",
            color = Cp.Red,
            fontFamily = HudFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(onClick = onClose)
                .padding(8.dp),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(accent.copy(alpha = 0.6f))
    )
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Yellow)
            .padding(14.dp)
    ) {
        Text(text, color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            action,
            color = Cp.Yellow,
            fontFamily = HudFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .hudPanel(borderColor = Cp.Yellow, fill = Cp.Yellow.copy(alpha = 0.12f))
                .clickable(onClick = onAction)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun FilterField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Cp.Cyan, fontFamily = HudFont, fontSize = 14.sp),
        cursorBrush = SolidColor(Cp.Yellow),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.7f))
                    .padding(10.dp)
            ) {
                Text("> ", color = Cp.Yellow, fontFamily = HudFont, fontSize = 14.sp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("FILTRER SSID / BSSID_", color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
                    inner()
                }
            }
        },
    )
}

private fun signalColor(rssi: Int): Color = when {
    rssi >= -60 -> Cp.Cyan
    rssi >= -75 -> Cp.Yellow
    else -> Cp.Red
}

private fun formatDistance(m: Double): String = when {
    m < 0 -> "?"
    m < 10 -> String.format(Locale.FRANCE, "~%.1f m", m)
    else -> String.format(Locale.FRANCE, "~%.0f m", m)
}

@Composable
private fun AccessPointRow(ap: AccessPoint, onTrack: () -> Unit) {
    val color = signalColor(ap.rssi)
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = if (ap.connected) Cp.Yellow else color.copy(alpha = 0.5f), cut = 8.dp)
            .clickable(onClick = onTrack)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalBars(WifiScanner.bars(ap.rssi), color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (ap.connected) "● " else "") + ap.ssid,
                color = if (ap.ssid == WifiScanner.HIDDEN) Cp.Dim else Cp.Text,
                fontFamily = HudFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${ap.bssid}  ch${ap.channel} ${ap.band}",
                color = Cp.Dim,
                fontFamily = HudFont,
                fontSize = 10.sp,
            )
            Text(
                listOf(ap.security, ap.standard).filter { it.isNotBlank() }.joinToString(" · ") +
                    if (ap.ageSeconds > 30) " · vu il y a ${ap.ageSeconds} s" else "",
                color = Cp.Dim,
                fontFamily = HudFont,
                fontSize = 10.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${ap.rssi} dBm", color = color, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(formatDistance(ap.distanceM), color = Cp.Text, fontFamily = HudFont, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SignalBars(bars: Int, color: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { i ->
            Box(
                Modifier
                    .size(width = 4.dp, height = (6 + i * 4).dp)
                    .background(if (i < bars) color else Cp.Dim.copy(alpha = 0.3f))
            )
        }
    }
}

/** "Hot / cold" view for walking towards one access point. */
@Composable
private fun Tracker(ap: AccessPoint?, bssid: String, history: List<Int>, throttled: Boolean) {
    var sound by remember { mutableStateOf(true) }
    val rssi = ap?.rssi ?: -100
    val color = signalColor(rssi)
    // Read by the beep loop without restarting it on every new reading.
    val currentRssi by rememberUpdatedState(rssi)

    // Geiger-style beep: the closer the access point, the faster it ticks.
    LaunchedEffect(sound, ap != null) {
        if (!sound || ap == null) return@LaunchedEffect
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() ?: return@LaunchedEffect
        try {
            while (true) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                val strength = ((currentRssi + 90).coerceIn(0, 60)) / 60f
                delay((1400 - strength * 1250).toLong())
            }
        } finally {
            tone.release()
        }
    }

    val recent = history.takeLast(5)
    val older = history.dropLast(5).takeLast(10)
    val trend = if (recent.isNotEmpty() && older.isNotEmpty()) recent.average() - older.average() else 0.0

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        Text(ap?.ssid ?: "Borne hors de portée", color = Cp.Text, fontFamily = HudFont, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(bssid, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Radar(strength = ((rssi + 95).coerceIn(0, 65)) / 65f, color = color)
        Spacer(Modifier.height(12.dp))
        Text("$rssi dBm", color = color, fontFamily = HudFont, fontSize = 44.sp, fontWeight = FontWeight.Black)
        Text(
            "distance estimée ${ap?.let { formatDistance(it.distanceM) } ?: "?"}",
            color = Cp.Text,
            fontFamily = HudFont,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                trend > 2 -> "▲ TU T'EN RAPPROCHES"
                trend < -2 -> "▼ TU T'EN ÉLOIGNES"
                else -> "■ SIGNAL STABLE"
            },
            color = when {
                trend > 2 -> Cp.Cyan
                trend < -2 -> Cp.Red
                else -> Cp.Yellow
            },
            fontFamily = HudFont,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        Sparkline(history, color)
        Spacer(Modifier.height(10.dp))
        ap?.let {
            Text(
                "ch${it.channel} · ${it.band} · ${it.security}" + if (it.standard.isNotBlank()) " · ${it.standard}" else "",
                color = Cp.Dim,
                fontFamily = HudFont,
                fontSize = 12.sp,
            )
        }
        if (throttled && ap?.connected != true) {
            Text(
                "Mise à jour lente : désactive « Limitation de la recherche Wi-Fi » dans les options développeur.",
                color = Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (sound) "[ SON : ON ]" else "[ SON : OFF ]",
            color = Cp.Cyan,
            fontFamily = HudFont,
            fontSize = 13.sp,
            modifier = Modifier
                .hudPanel(borderColor = Cp.Cyan)
                .clickable { sound = !sound }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Text(
            "Marche lentement et tourne-toi : le signal monte en approchant de la borne. L'estimation de distance ignore les murs, la borne est souvent plus proche.",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Radar(strength: Float, color: Color) {
    Canvas(Modifier.size(150.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        for (i in 1..4) {
            drawCircle(Cp.Dim.copy(alpha = 0.5f), radius = r * i / 4, center = c, style = Stroke(1f))
        }
        drawLine(Cp.Dim.copy(alpha = 0.4f), Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(Cp.Dim.copy(alpha = 0.4f), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        // The stronger the signal, the closer the blip gets to the centre (you).
        val distance = r * (1f - strength).coerceIn(0.05f, 0.95f)
        drawCircle(color.copy(alpha = 0.25f), radius = r * 0.16f, center = Offset(c.x, c.y - distance))
        drawCircle(color, radius = r * 0.07f, center = Offset(c.x, c.y - distance))
        drawCircle(Cp.Yellow, radius = r * 0.05f, center = c)
    }
}

@Composable
private fun Sparkline(values: List<Int>, color: Color) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .hudPanel(borderColor = Cp.Dim.copy(alpha = 0.5f), fill = Color.Transparent)
    ) {
        if (values.size < 2) return@Canvas
        val min = -100f
        val max = -30f
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val y = size.height * (1 - ((v.coerceIn(-100, -30) - min) / (max - min)))
            if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
