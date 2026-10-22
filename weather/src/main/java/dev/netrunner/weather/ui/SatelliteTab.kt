package dev.netrunner.weather.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudPanel
import dev.netrunner.weather.data.Satellite
import dev.netrunner.weather.data.WeatherRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SIZE = 720
private const val FRAMES = 8

/** Meteosat pictures around the place: the last few hours, playable as a loop. */
@Composable
fun SatelliteTab(state: WeatherState) {
    val place = state.place
    var product by rememberSaveable { mutableStateOf(Satellite.Product.COLOUR) }
    var area by rememberSaveable { mutableStateOf(Satellite.Area.REGION) }
    var reload by remember { mutableIntStateOf(0) }
    var frames by remember { mutableStateOf<List<Pair<String, Bitmap>>>(emptyList()) }
    var overlay by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }

    val box = place?.let { Satellite.box(it.lat, it.lon, area) }

    LaunchedEffect(box, product, reload) {
        if (box == null) return@LaunchedEffect
        loading = true
        error = null
        playing = false
        try {
            val (latest, step) = runCatching { Satellite.latest(WeatherRepo.get(Satellite.capabilitiesUrl(product))) }.getOrNull()
                ?: (Satellite.guessLatest(Instant.now()) to 10)
            // Every 30 minutes over the last 3.5 hours.
            val times = Satellite.frames(latest, FRAMES, maxOf(step, 30 / step * step))
            val loaded = coroutineScope {
                times.map { t ->
                    async { runCatching { t to decode(WeatherRepo.bytes(Satellite.imageUrl(product, box, SIZE, SIZE, t)))!! }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
            frames = loaded
            index = (loaded.size - 1).coerceAtLeast(0)
            if (loaded.isEmpty()) error = "Images satellite indisponibles pour le moment."
            overlay = runCatching { decode(WeatherRepo.bytes(Satellite.overlayUrl(box, SIZE, SIZE))) }.getOrNull()
                ?: runCatching { decode(WeatherRepo.bytes(Satellite.overlayUrl(box, SIZE, SIZE, styled = false))) }.getOrNull()
        } catch (e: Exception) {
            error = "Réseau indisponible."
        } finally {
            loading = false
        }
    }
    LaunchedEffect(playing, frames) {
        while (playing && frames.size > 1) {
            delay(if (index == frames.size - 1) 1400 else 450)
            index = (index + 1) % frames.size
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        SectionTitle("SATELLITE · METEOSAT", Cp.Yellow)
        Text((place?.name ?: "—").uppercase(Locale.FRANCE), color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp)
        if (place == null) {
            Message("Ouvre d'abord l'onglet MÉTÉO pour choisir la position ou une ville.")
            return@Column
        }
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
            Satellite.Product.entries.forEach { p -> Chip(p.label, if (p == product) Cp.Yellow else Cp.Dim) { product = p } }
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
            Satellite.Area.entries.forEach { a -> Chip(a.label, if (a == area) Cp.Cyan else Cp.Dim) { area = a } }
            Chip(if (loading) "SYNC…" else "ACTUALISER", Cp.Cyan) { reload++ }
        }
        Box(
            Modifier.padding(top = 10.dp).fillMaxWidth().aspectRatio(1f).hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 10.dp)
                .padding(2.dp).background(Cp.Background),
            contentAlignment = Alignment.Center,
        ) {
            val frame = frames.getOrNull(index)
            if (frame != null) {
                Image(frame.second.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                overlay?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                val (x, y) = box!!.locate(place.lat, place.lon)
                val mark = Cp.Red
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width * x, size.height * y)
                    val r = 7.dp.toPx()
                    drawCircle(mark, r, c, style = Stroke(2.dp.toPx()))
                    drawLine(mark, c - Offset(r * 2, 0f), c - Offset(r, 0f), 2.dp.toPx())
                    drawLine(mark, c + Offset(r, 0f), c + Offset(r * 2, 0f), 2.dp.toPx())
                    drawLine(mark, c - Offset(0f, r * 2), c - Offset(0f, r), 2.dp.toPx())
                    drawLine(mark, c + Offset(0f, r), c + Offset(0f, r * 2), 2.dp.toPx())
                }
                Text(
                    local(frame.first), color = Cp.Yellow, fontFamily = MonoFont, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.TopStart).background(Cp.Background.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else {
                Text(
                    error ?: if (loading) "Liaison satellite…" else "", color = if (error != null) Cp.Red else Cp.Dim,
                    fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (frames.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (playing) "❚❚" else "▶ ANIMER", Cp.Yellow) { playing = !playing }
                Slider(
                    value = index.toFloat(), onValueChange = { playing = false; index = it.toInt() },
                    valueRange = 0f..(frames.size - 1).toFloat(), steps = (frames.size - 2).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = Cp.Yellow, activeTrackColor = Cp.Yellow, inactiveTrackColor = Cp.Dim.copy(alpha = 0.35f)),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        }
        Text("> ${product.detail}.", color = Cp.Cyan, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Images Meteosat Third Generation · © EUMETSAT (EUMETView). Une image toutes les 30 min sur les 3,5 dernières heures.",
            color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private suspend fun decode(bytes: ByteArray): Bitmap? = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }

private val clock = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRANCE)

/** "2026-10-22T09:50:00Z" → "22/10 11:50" in the phone's time zone. */
private fun local(utc: String): String = runCatching { clock.format(Instant.parse(utc).atZone(ZoneId.systemDefault())) }.getOrDefault(utc)
