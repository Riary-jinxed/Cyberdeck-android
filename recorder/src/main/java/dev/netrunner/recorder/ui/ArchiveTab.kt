package dev.netrunner.recorder.ui

import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.hudShape
import dev.netrunner.recorder.data.Recordings
import dev.netrunner.recorder.data.Wave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved recordings, newest first; the one opened plays inline. */
@Composable
fun ArchiveTab(version: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val items by produceState<List<Recordings.Item>?>(null, version) { value = withContext(Dispatchers.IO) { Recordings.all(context) } }
    var open by remember { mutableStateOf<java.io.File?>(null) }
    var renaming by remember { mutableStateOf<Recordings.Item?>(null) }
    var deleting by remember { mutableStateOf<Recordings.Item?>(null) }

    val list = items
    when {
        list == null -> Message("Lecture des archives…")
        list.isEmpty() -> Message("Aucun enregistrement. Onglet REC pour commencer.")
        else -> LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "${list.size} ENREGISTREMENT${if (list.size > 1) "S" else ""} · ${Wave.time(list.sumOf { it.duration })}",
                    color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp,
                )
            }
            items(list, key = { it.file.path }) { item ->
                val isOpen = open == item.file
                Column(
                    Modifier.fillMaxWidth().hudPanel(borderColor = (if (isOpen) Cp.Yellow else Cp.Cyan).copy(alpha = if (isOpen) 0.8f else 0.35f), cut = 8.dp)
                        .clickable { open = if (isOpen) null else item.file }.padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = if (isOpen) Cp.Yellow else Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${dateFormat.format(Date(item.date))} · ${size(item.size)}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
                        }
                        Text(Wave.time(item.duration), color = Cp.Cyan, fontFamily = MonoFont, fontSize = 15.sp)
                    }
                    if (isOpen) {
                        Player(item)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                            Chip("PARTAGER", Cp.Cyan) {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", item.file)
                                val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, item.name))
                            }
                            Chip("RENOMMER", Cp.Cyan) { renaming = item }
                            Chip("SUPPR.", Cp.Red) { deleting = item }
                        }
                    } else {
                        val bars = remember(item.file) { Wave.bars(Recordings.levels(item.file), 60) }
                        WaveBars(bars, Modifier.fillMaxWidth().height(28.dp).padding(top = 6.dp), color = Cp.Dim)
                    }
                }
            }
        }
    }

    renaming?.let { item ->
        var name by remember(item) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = Cp.Panel, shape = hudShape(14.dp),
            title = { Text("RENOMMER", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { TextField(name, { name = it }, singleLine = true, colors = hudFieldColors(), textStyle = TextStyle(fontFamily = MonoFont, fontSize = 17.sp)) },
            confirmButton = {
                HudButton("RENOMMER", filled = true) {
                    try {
                        val f = Recordings.rename(item, name)
                        if (open == item.file) open = f
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                    }
                    renaming = null
                    onChanged()
                }
            },
            dismissButton = { HudButton("ANNULER", color = Cp.Dim) { renaming = null } },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Cp.Panel, shape = hudShape(14.dp),
            title = { Text("SUPPRIMER ?", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { Text("« ${item.name} » sera effacé.", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp) },
            confirmButton = {
                HudButton("SUPPRIMER", color = Cp.Red, filled = true) {
                    if (open == item.file) open = null
                    Recordings.delete(item)
                    deleting = null
                    onChanged()
                }
            },
            dismissButton = { HudButton("ANNULER", color = Cp.Dim) { deleting = null } },
        )
    }
}

/** Plays one recording: waveform to tap for seeking, play/pause, ±10 s and speed. */
@Composable
private fun Player(item: Recordings.Item) {
    val player = remember(item.file) { runCatching { MediaPlayer().apply { setDataSource(item.file.path); prepare() } }.getOrNull() }
    var playing by remember(item.file) { mutableStateOf(false) }
    var position by remember(item.file) { mutableIntStateOf(0) }
    var speed by remember(item.file) { mutableFloatStateOf(1f) }
    val length = player?.duration?.coerceAtLeast(1) ?: 1
    val bars = remember(item.file) { Wave.bars(Recordings.levels(item.file), 80) }

    DisposableEffect(player) {
        player?.setOnCompletionListener { playing = false; position = 0; it.seekTo(0) }
        onDispose { player?.release() }
    }
    LaunchedEffect(player, playing) {
        while (playing && player != null) {
            position = player.currentPosition
            delay(100)
        }
    }
    if (player == null) {
        Text("Lecture impossible.", color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        return
    }
    fun seek(ms: Int) {
        val to = ms.coerceIn(0, length)
        player.seekTo(to)
        position = to
    }
    WaveBars(
        bars,
        Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp).pointerInput(player) {
            detectTapGestures { o -> seek((o.x / size.width * length).toInt()) }
        },
        progress = position.toFloat() / length,
    )
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Wave.time(position.toLong()), color = Cp.Yellow, fontFamily = MonoFont, fontSize = 13.sp)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Key("« 10") { seek(position - 10_000) }
            Key(if (playing) "❚❚" else "▶", Cp.Yellow, big = true) {
                if (playing) player.pause() else player.start()
                playing = player.isPlaying
            }
            Key("10 »") { seek(position + 10_000) }
            Key("×" + (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()).replace('.', ',')) {
                val next = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
                val was = player.isPlaying
                runCatching { player.playbackParams = player.playbackParams.setSpeed(next) }
                if (!was) player.pause()
                playing = player.isPlaying
                speed = next
            }
        }
        Text(Wave.time(length.toLong()), color = Cp.Dim, fontFamily = MonoFont, fontSize = 13.sp)
    }
}

private val speeds = listOf(1f, 1.5f, 2f, 0.75f)

@Composable
private fun Key(text: String, color: androidx.compose.ui.graphics.Color = Cp.Cyan, big: Boolean = false, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = if (big) 24.sp else 15.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private val dateFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.FRANCE)

private fun size(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
    else -> "%.1f Mo".format(Locale.FRANCE, bytes / 1_048_576.0)
}
