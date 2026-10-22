package dev.netrunner.music.ui

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.hudShape
import dev.netrunner.music.data.Library
import dev.netrunner.music.data.MusicPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun NowPlayingTab(player: PlayerState, style: MusicPrefs.Style, glow: Boolean, onLibrary: () -> Unit) {
    val item = player.item
    if (item == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("SILENCE RADIO", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 3.sp)
            Text(
                "Rien en lecture. Choisis un titre dans la bibliothèque.",
                color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(vertical = 14.dp),
            )
            Text(
                "BIBLIOTHÈQUE ►", color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onLibrary).padding(vertical = 8.dp),
            )
        }
        return
    }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val meta = item.mediaMetadata

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(item.mediaId.toLongOrNull(), Modifier.size(84.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    meta.title?.toString() ?: "Sans titre", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold,
                    fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(meta.artist?.toString() ?: "", color = Cp.Cyan, fontFamily = HudFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta.albumTitle?.toString() ?: "", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp)) {
            if (showQueue) Queue(player) else Visualizer(player.playing, style, glow, Modifier.fillMaxSize())
        }
        Progress(player)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Key("⤨", if (player.shuffle) Cp.Yellow else Cp.Dim) { player.setShuffle(!player.shuffle) }
            Key("⏮", Cp.Cyan) { player.previous() }
            Key(if (player.playing) "❚❚" else "▶", Cp.Yellow, big = true) { player.toggle() }
            Key("⏭", Cp.Cyan) { player.next() }
            Key(
                when (player.repeat) { Player.REPEAT_MODE_ONE -> "↻1"; else -> "↻" },
                if (player.repeat == Player.REPEAT_MODE_OFF) Cp.Dim else Cp.Yellow,
            ) { player.cycleRepeat() }
        }
        Text(
            if (showQueue) "◄ VISUALISEUR" else "FILE D'ATTENTE · ${player.index + 1}/${player.count} ►",
            color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).clickable { showQueue = !showQueue }.padding(8.dp),
        )
    }
}

@Composable
private fun Progress(player: PlayerState) {
    var position by remember { mutableLongStateOf(player.position) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player.item, player.playing) {
        while (true) {
            position = player.position
            if (!player.playing) break
            delay(250)
        }
    }
    val length = player.duration.coerceAtLeast(1L)
    Slider(
        value = dragging ?: (position.toFloat() / length).coerceIn(0f, 1f),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { val to = (it * length).toLong(); player.seek(to); position = to }
            dragging = null
        },
        colors = SliderDefaults.colors(thumbColor = Cp.Yellow, activeTrackColor = Cp.Yellow, inactiveTrackColor = Cp.Dim.copy(alpha = 0.35f)),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(Library.time(dragging?.let { (it * length).toLong() } ?: position), color = Cp.Yellow, fontFamily = MonoFont, fontSize = 12.sp)
        Box(Modifier.weight(1f))
        Text(Library.time(player.duration), color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
    }
}

@Composable
private fun Queue(player: PlayerState) {
    val items = remember(player.queueVersion, player.controller) { player.queue() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = player.index.coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        itemsIndexed(items) { i, it ->
            val current = i == player.index
            Row(
                Modifier.fillMaxWidth().clickable { player.jump(i) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (current) "▶" else "%02d".format(i + 1), color = if (current) Cp.Yellow else Cp.Dim,
                    fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.padding(end = 10.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        it.mediaMetadata.title?.toString() ?: "", color = if (current) Cp.Yellow else Cp.Text, fontFamily = HudFont,
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(it.mediaMetadata.artist?.toString() ?: "", color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, maxLines = 1)
                }
                if (!current) Text("✕", color = Cp.Dim, fontSize = 15.sp, modifier = Modifier.clickable { player.remove(i) }.padding(8.dp))
            }
        }
    }
}

/** The album cover Android extracted from the file, or a neon note when there is none. */
@Composable
fun Cover(trackId: Long?, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, trackId) {
        value = trackId?.let { id ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                }.getOrNull()
            }
        }
    }
    Box(modifier.clip(hudShape(8.dp)).hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("♫", color = Cp.Red, fontSize = 30.sp)
    }
}

@Composable
private fun Key(text: String, color: Color, big: Boolean = false, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = if (big) 34.sp else 22.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
