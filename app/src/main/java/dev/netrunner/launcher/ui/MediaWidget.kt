package dev.netrunner.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.netrunner.launcher.media.Spectrum
import dev.netrunner.launcher.media.MediaWatcher
import dev.netrunner.launcher.media.NowPlaying
import kotlinx.coroutines.delay

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** "Now playing" deck for Spotify or any other player. */
@Composable
fun MediaWidget(track: NowPlaying, watcher: MediaWatcher) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val active = rememberScreenActive()
    LaunchedEffect(track.playing, active) {
        while (track.playing && active) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val position = track.livePosition(now)
    val accent = if (track.packageName == "com.spotify.music") Cp.Cyan else Cp.Yellow

    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = accent.copy(alpha = 0.7f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(hudShape(8.dp))
                    .background(Cp.Panel)
                    .clickable { watcher.openPlayer() },
                contentAlignment = Alignment.Center,
            ) {
                if (track.art != null) {
                    Image(track.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
                } else {
                    Text("♫", color = accent, fontSize = 24.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable { watcher.openPlayer() }
            ) {
                Text(
                    "// AUDIO ${track.appLabel.uppercase()}",
                    color = accent,
                    fontFamily = HudFont,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    track.title.ifBlank { "—" },
                    color = Cp.Text,
                    fontFamily = HudFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    color = Cp.Text.copy(alpha = 0.7f),
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SpectrumBars(playing = track.playing && active && !dev.netrunner.launcher.system.Ripperdoc.active, accent = accent)
        Spacer(Modifier.height(6.dp))
        if (track.durationMs > 0) {
            // Tap anywhere on the bar to seek.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .pointerInput(track.durationMs) {
                        detectTapGestures { offset ->
                            watcher.seekTo((offset.x / size.width * track.durationMs).toLong())
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Cp.Dim.copy(alpha = 0.4f))
                )
                Box(
                    Modifier
                        .fillMaxWidth(position.toFloat() / track.durationMs)
                        .height(3.dp)
                        .background(accent)
                )
            }
            Row {
                Text(mmss(position), color = Cp.Dim, fontFamily = HudFont, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(mmss(track.durationMs), color = Cp.Dim, fontFamily = HudFont, fontSize = 10.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaButton("◄◄") { watcher.previous() }
            MediaButton(if (track.playing) "❚❚" else "►", big = true) { watcher.playPause() }
            MediaButton("►►") { watcher.next() }
        }
    }
}

/** HUD spectrum analyser: real audio with the microphone permission, an imitation otherwise. */
@Composable
private fun SpectrumBars(playing: Boolean, accent: Color) {
    val context = LocalContext.current
    val spectrum = remember { Spectrum(context.applicationContext) }
    DisposableEffect(playing) {
        if (playing) spectrum.start()
        onDispose { spectrum.stop() }
    }
    // The clock only runs while music plays and the screen is visible.
    val t by androidx.compose.runtime.key(playing) { rememberSlowClock(fps = if (playing) 15 else 1) }
    val levels = spectrum.read(t, playing).copyOf()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
    ) {
        val n = levels.size
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val segment = 4.dp.toPx()
        for (i in 0 until n) {
            val h = size.height * levels[i].coerceIn(0.04f, 1f)
            val x = i * (w + gap)
            // Segmented bars, hotter colour at the top like the game's audio meters.
            var y = size.height
            while (y > size.height - h) {
                val top = maxOf(size.height - h, y - segment + 1.dp.toPx())
                val hot = y < size.height * 0.35f
                drawRect(if (hot) Cp.Red else accent, Offset(x, top), Size(w, y - top))
                y -= segment
            }
        }
    }
}

@Composable
private fun MediaButton(label: String, big: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (big) Cp.Background else Cp.Yellow,
        fontSize = if (big) 18.sp else 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .hudPanel(borderColor = Cp.Yellow, fill = if (big) Cp.Yellow else Cp.Panel, cut = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = if (big) 22.dp else 16.dp, vertical = 6.dp),
    )
}
