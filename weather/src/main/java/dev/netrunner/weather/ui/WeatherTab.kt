package dev.netrunner.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.DisplayFont
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.scanlines
import dev.netrunner.weather.data.Forecast
import dev.netrunner.weather.R
import dev.netrunner.weather.data.Sky
import dev.netrunner.weather.data.WeatherRepo
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The "mission report": now, the next 24 hours and the week. */
@Composable
fun WeatherTab(state: WeatherState, onAskLocation: () -> Unit) {
    val scope = rememberCoroutineScope()
    val f = state.forecast
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("RAPPORT DE MISSION", Cp.Yellow)
                Text(
                    (f?.place ?: state.selected?.name ?: "—").uppercase(Locale.FRANCE), color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold,
                    fontSize = 22.sp, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Chip(if (state.loading) "SYNC…" else "ACTUALISER", Cp.Cyan) { scope.launch { state.refresh() } }
        }
        state.error?.let { err ->
            Text(err, color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            if (state.selected == null && !state.hasLocation()) Chip("AUTORISER LA POSITION", Cp.Yellow, onAskLocation)
        }
        if (f == null) {
            if (state.error == null) Message(if (state.loading) "Connexion aux satellites météo…" else "Aucune donnée.")
        } else {
            Current(f)
            Spacer(Modifier.height(16.dp))
            SectionTitle("24 PROCHAINES HEURES", Cp.Cyan)
            Hours(f.hours)
            Spacer(Modifier.height(16.dp))
            SectionTitle("7 JOURS", Cp.Cyan)
            Days(f.days)
            Text(
                "Données Open-Meteo · mise à jour ${java.text.SimpleDateFormat("HH:mm", Locale.FRANCE).format(java.util.Date(f.fetched))}",
                color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun Current(f: Forecast) {
    val c = f.current
    val (label, glyph) = Sky.describe(c.code, c.isDay)
    val today = f.days.firstOrNull()
    val context = LocalContext.current
    val aska = remember { WeatherRepo.showAska(context) }
    val mood = Sky.mood(c)
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.6f), cut = 12.dp).scanlines(Cp.Background.copy(alpha = 0.2f), 3.dp).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Sky.temp(c.temp), color = Cp.Yellow, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 72.sp)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(glyph, color = Cp.Cyan, fontSize = 34.sp)
                Text(label, color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (today != null) Text("${Sky.temp(today.min)} / ${Sky.temp(today.max)}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 14.sp)
            }
            if (aska) Image(painterResource(chibi(mood)), "As//ka", Modifier.height(120.dp))
        }
        if (aska) {
            Text("AS//KA > ${Sky.quip(mood)}", color = Cp.Yellow.copy(alpha = 0.9f), fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (today != null) {
            Text("> ${Sky.advice(today, c)}", color = Cp.Cyan, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        val stats = listOfNotNull(
            "RESSENTI" to Sky.temp(c.feels),
            "HUMIDITÉ" to "${c.humidity} %",
            "VENT" to "${c.wind.roundToInt()} km/h ${Sky.compass(c.windDir)}",
            "RAFALES" to "${c.gusts.roundToInt()} km/h",
            "PRESSION" to "${c.pressure.roundToInt()} hPa",
            today?.let { "UV" to "%.0f".format(Locale.FRANCE, it.uv) },
            today?.let { "LEVER" to Sky.clock(it.sunrise) },
            today?.let { "COUCHER" to Sky.clock(it.sunset) },
        )
        stats.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                row.forEach { (k, v) ->
                    Row(Modifier.weight(1f)) {
                        Text(k, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.width(80.dp))
                        Text(v, color = Cp.Text, fontFamily = MonoFont, fontSize = 14.sp)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Hour cards over a temperature curve. */
@Composable
private fun Hours(hours: List<Forecast.Hour>) {
    if (hours.isEmpty()) return
    val cell = 58.dp
    val yellow = Cp.Yellow
    val temps = hours.map { it.temp }
    val lo = temps.minOrNull() ?: 0.0
    val hi = (temps.maxOrNull() ?: 1.0).let { if (it - lo < 1) lo + 1 else it }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
        Column {
            Canvas(Modifier.width(cell * hours.size).height(46.dp)) {
                val step = size.width / hours.size
                val path = Path()
                hours.forEachIndexed { i, h ->
                    val x = step * i + step / 2
                    val y = size.height - 6f - ((h.temp - lo) / (hi - lo)).toFloat() * (size.height - 12f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(yellow, 3.dp.toPx(), Offset(x, y))
                }
                drawPath(path, yellow.copy(alpha = 0.7f), style = Stroke(2.dp.toPx()))
            }
            Row {
                hours.forEach { h ->
                    Column(Modifier.width(cell).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Sky.temp(h.temp), color = Cp.Text, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(Sky.describe(h.code, h.isDay).second, color = Cp.Cyan, fontSize = 20.sp)
                        Text(if (h.rainChance > 0) "${h.rainChance}%" else " ", color = Cp.Cyan.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 11.sp)
                        Text(Sky.clock(h.time).take(2) + "h", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** One line per day, with the min-max range drawn against the week's range. */
@Composable
private fun Days(days: List<Forecast.Day>) {
    if (days.isEmpty()) return
    val lo = days.minOf { it.min }
    val hi = days.maxOf { it.max }.let { if (it - lo < 1) lo + 1 else it }
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEachIndexed { i, d ->
            val date = runCatching { LocalDate.parse(d.date) }.getOrNull()
            val name = when (i) {
                0 -> "AUJ."
                1 -> "DEMAIN"
                else -> date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.FRANCE)?.uppercase(Locale.FRANCE)?.trimEnd('.') ?: d.date
            }
            Row(
                Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.25f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, color = if (i == 0) Cp.Yellow else Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(64.dp))
                Text(Sky.describe(d.code).second, color = Cp.Cyan, fontSize = 18.sp, modifier = Modifier.width(30.dp))
                Text(if (d.rainChance > 0) "${d.rainChance}%" else "", color = Cp.Cyan.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                Text(Sky.temp(d.min), color = Cp.Dim, fontFamily = MonoFont, fontSize = 14.sp, modifier = Modifier.width(36.dp))
                Box(Modifier.weight(1f).height(6.dp).background(Cp.Panel)) {
                    val start = ((d.min - lo) / (hi - lo)).toFloat()
                    val end = ((d.max - lo) / (hi - lo)).toFloat()
                    Row(Modifier.fillMaxSize()) {
                        if (start > 0f) Spacer(Modifier.weight(start))
                        Box(Modifier.weight((end - start).coerceAtLeast(0.02f)).fillMaxHeight().background(Cp.Yellow))
                        if (end < 1f) Spacer(Modifier.weight(1f - end))
                    }
                }
                Text(Sky.temp(d.max), color = Cp.Text, fontFamily = MonoFont, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).width(36.dp))
            }
        }
    }
}

/** One pose of As//ka per [Sky.mood]. */
private fun chibi(mood: String): Int = when (mood) {
    "storm" -> R.drawable.aska_weather_storm
    "snow" -> R.drawable.aska_weather_snow
    "rain" -> R.drawable.aska_weather_rain
    "fog" -> R.drawable.aska_weather_fog
    "wind" -> R.drawable.aska_weather_wind
    "heat" -> R.drawable.aska_weather_heat
    "cold" -> R.drawable.aska_weather_cold
    "night" -> R.drawable.aska_weather_night
    "cloudy" -> R.drawable.aska_weather_cloudy
    else -> R.drawable.aska_weather_sun
}
