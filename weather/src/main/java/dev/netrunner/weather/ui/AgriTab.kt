package dev.netrunner.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudPanel
import dev.netrunner.weather.data.Agri
import dev.netrunner.weather.data.Sky
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Farm report: soil, water balance, frost, degree days and spraying windows. */
@Composable
fun AgriTab(state: WeatherState) {
    val scope = rememberCoroutineScope()
    val place = state.place
    LaunchedEffect(place) { if (place != null) state.refreshAgri() }
    val a = state.agri

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("BULLETIN AGRICOLE", Cp.Yellow)
                Text((place?.name ?: "—").uppercase(Locale.FRANCE), color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp)
            }
            if (place != null) Chip(if (state.agriLoading) "SYNC…" else "ACTUALISER", Cp.Cyan) { scope.launch { state.refreshAgri() } }
        }
        state.agriError?.let { Text(it, color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
        when {
            place == null -> Message("Ouvre d'abord l'onglet MÉTÉO pour choisir la position ou une ville.")
            a == null -> if (state.agriError == null) Message("Analyse des sols…")
            else -> Report(a)
        }
    }
}

@Composable
private fun Report(a: Agri) {
    Text("> ${Agri.advice(a)}", color = Cp.Cyan, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))

    SectionTitle("SOL", Cp.Cyan)
    Panel {
        Stat("TEMP. SURFACE", Sky.temp(a.now.soil0), "TEMP. 6 CM", Sky.temp(a.now.soil6))
        Stat("TEMP. 18 CM", Sky.temp(a.now.soil18), "DÉFICIT VAP.", kpa(a.now.vpd))
        Spacer(Modifier.height(6.dp))
        Moisture("0-1 cm", a.now.moist1)
        Moisture("1-3 cm", a.now.moist3)
        Moisture("3-9 cm", a.now.moist9)
        Moisture("9-27 cm", a.now.moist27)
        Text("> ${Agri.vpdLabel(a.now.vpd)}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }

    Spacer(Modifier.height(14.dp))
    SectionTitle("EAU", Cp.Cyan)
    Panel {
        Stat("PLUIE 7 J PASSÉS", mm(a.rainPast), "PLUIE 7 J À VENIR", mm(a.rainNext))
        Stat("BILAN PASSÉ", signed(a.balancePast), "BILAN À VENIR", signed(a.balanceNext))
        Stat("ÉVAPOTRANSP. 7 J", mm(a.et0Next), "", "")
        Text(
            "Bilan = pluie − évapotranspiration de référence (ET0). Négatif : le sol perd de l'eau.",
            color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    WaterChart(a.days)

    Spacer(Modifier.height(14.dp))
    SectionTitle("CULTURES", Cp.Cyan)
    Panel {
        Stat("DEGRÉS-JOURS 7 J", "%.0f °C·j".format(Locale.FRANCE, a.degreeDays()), "BASE", "10 °C")
        val frost = a.frostDays
        val cold = a.coldDays
        Text(
            when {
                frost.isNotEmpty() -> "GEL : " + frost.joinToString(" · ") { "${dayName(it.date)} ${Sky.temp(it.min)}" }
                cold.isNotEmpty() -> "GELÉE BLANCHE POSSIBLE : " + cold.joinToString(" · ") { "${dayName(it.date)} ${Sky.temp(it.min)}" }
                else -> "Pas de gel prévu sur 7 jours."
            },
            color = if (frost.isNotEmpty()) Cp.Red else if (cold.isNotEmpty()) Cp.Yellow else Cp.Text,
            fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
        )
        val sun = a.days.firstOrNull { !it.past }
        if (sun != null) Text("Ensoleillement aujourd'hui : %.1f h".format(Locale.FRANCE, sun.sunHours), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }

    Spacer(Modifier.height(14.dp))
    SectionTitle("TRAITEMENTS · 24 H", Cp.Cyan)
    Panel {
        val window = a.firstSprayWindow()
        Text(
            window?.let { "Prochaine fenêtre : ${it.first} → ${it.second}" } ?: "Aucune fenêtre favorable dans les 24 h.",
            color = if (window != null) Cp.Yellow else Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        )
        SprayStrip(a)
        Text(
            "Vert : vent < 19 km/h, pas de pluie pendant 3 h, entre 5 et 25 °C.",
            color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        "Données Open-Meteo (modèles météo, humidité du sol estimée). Les seuils sont indicatifs : ils dépendent du sol et de la culture.",
        color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 8.dp).padding(12.dp)) { content() }
}

@Composable
private fun Stat(k1: String, v1: String, k2: String, v2: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        listOf(k1 to v1, k2 to v2).forEach { (k, v) ->
            Column(Modifier.weight(1f)) {
                if (k.isNotEmpty()) {
                    Text(k, color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(v, color = Cp.Text, fontFamily = MonoFont, fontSize = 16.sp)
                }
            }
        }
    }
}

/** One soil layer: a bar filled to its water content (0..0.5 m³/m³) and a word for it. */
@Composable
private fun Moisture(depth: String, v: Double) {
    val bar = Cp.Cyan
    val track = Cp.Panel
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(depth, color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            drawRect(track)
            if (!v.isNaN()) drawRect(bar, size = Size(size.width * (v / 0.5).toFloat().coerceIn(0f, 1f), size.height))
        }
        Text(
            if (v.isNaN()) "—" else "${(v * 100).roundToInt()} % · ${Agri.moistureLabel(v)}",
            color = Cp.Text, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).width(118.dp),
        )
    }
}

/** Rain (cyan, up) and evapotranspiration (red, down) per day, past week then the coming one. */
@Composable
private fun WaterChart(days: List<Agri.Day>) {
    if (days.isEmpty()) return
    val rain = Cp.Cyan
    val et = Cp.Red
    val axis = Cp.Dim
    val today = Cp.Yellow
    val top = maxOf(days.maxOf { it.rain }, days.maxOf { it.et0 }, 5.0)
    Column(Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.25f), cut = 8.dp).padding(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val mid = size.height * 0.62f
            val step = size.width / days.size
            val w = step * 0.55f
            days.forEachIndexed { i, d ->
                val x = i * step + (step - w) / 2
                val up = (d.rain / top).toFloat() * (mid - 4f)
                val down = (d.et0 / top).toFloat() * (size.height - mid - 4f)
                drawRect(rain.copy(alpha = if (d.past) 0.55f else 1f), Offset(x, mid - up), Size(w, up))
                drawRect(et.copy(alpha = if (d.past) 0.45f else 0.85f), Offset(x, mid), Size(w, down))
            }
            drawLine(axis, Offset(0f, mid), Offset(size.width, mid), 1f)
            val firstNext = days.indexOfFirst { !it.past }
            if (firstNext > 0) drawLine(today, Offset(firstNext * step, 0f), Offset(firstNext * step, size.height), 2f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Legend("PLUIE", rain)
            Legend("ÉVAPOTRANSP.", et)
            Legend("| AUJOURD'HUI", today)
        }
    }
}

@Composable
private fun Legend(text: String, color: Color) = Text(text, color = color, fontFamily = MonoFont, fontSize = 11.sp)

/** The next 24 hours, green where spraying fits. */
@Composable
private fun SprayStrip(a: Agri) {
    val ok = a.sprayHours().map { it.time }.toSet()
    val good = Color(0xFF39FF14)
    val bad = Cp.Panel
    Canvas(Modifier.fillMaxWidth().height(14.dp).padding(top = 6.dp)) {
        val step = size.width / a.hours.size.coerceAtLeast(1)
        a.hours.forEachIndexed { i, h -> drawRect(if (h.time in ok) good else bad, Offset(i * step + 1f, 0f), Size(step - 2f, size.height)) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, 6, 12, 18, 23).mapNotNull { a.hours.getOrNull(it) }.forEach {
            Text(Sky.clock(it.time).take(2) + "h", color = Cp.Dim, fontFamily = MonoFont, fontSize = 10.sp)
        }
    }
}

private fun mm(v: Double) = "%.1f mm".format(Locale.FRANCE, v)
private fun signed(v: Double) = (if (v >= 0) "+" else "−") + "%.1f mm".format(Locale.FRANCE, abs(v))
private fun kpa(v: Double) = if (v.isNaN()) "—" else "%.2f kPa".format(Locale.FRANCE, v)

private fun dayName(date: String): String = runCatching {
    LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRANCE).trimEnd('.')
}.getOrDefault(date)
