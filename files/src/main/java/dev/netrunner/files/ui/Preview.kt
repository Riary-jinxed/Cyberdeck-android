package dev.netrunner.files.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.scanlines
import dev.netrunner.files.data.FileOps
import dev.netrunner.files.data.Zip
import dev.netrunner.files.system.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the built-in viewer can show; anything else goes straight to another app. */
enum class PreviewType { IMAGE, TEXT, ZIP }

private const val TEXT_LIMIT = 256 * 1024

/** Reads a few bytes when needed: a file with no NUL byte at its start is taken for text. */
fun previewType(file: File): PreviewType? {
    if (!file.isFile || !file.canRead()) return null
    if (Zip.isZip(file)) return PreviewType.ZIP
    val ext = file.extension.lowercase()
    if (FileOps.kind(file) == FileOps.Kind.IMAGE && ext != "svg" && ext != "dng") return PreviewType.IMAGE
    if (FileOps.kind(file) in setOf(FileOps.Kind.VIDEO, FileOps.Kind.AUDIO, FileOps.Kind.APK, FileOps.Kind.ARCHIVE)) return null
    if (ext in setOf("pdf", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "epub")) return null
    val head = runCatching { file.inputStream().use { s -> ByteArray(4096).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } } }.getOrNull() ?: return null
    return if (head.none { it == 0.toByte() }) PreviewType.TEXT else null
}

/** Full-screen preview of an image, a text file or the content of a zip, with the usual actions. */
@Composable
fun PreviewDialog(file: File, type: PreviewType, onDismiss: () -> Unit, onExtract: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Cp.Background).systemBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✕", color = Cp.Text, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = Cp.Yellow, fontFamily = MonoFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${FileOps.size(file.length())} · ${FileOps.date(file)}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp).hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f), cut = 8.dp)) {
                when (type) {
                    PreviewType.IMAGE -> ImagePreview(file)
                    PreviewType.TEXT -> TextPreview(file)
                    PreviewType.ZIP -> ZipPreview(file)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (type == PreviewType.ZIP) Chip("EXTRAIRE ICI", Cp.Yellow, onExtract)
                Chip("OUVRIR AVEC", Cp.Cyan) { Storage.open(context, file) }
                Chip("PARTAGER", Cp.Cyan) { Storage.share(context, listOf(file)) }
            }
        }
    }
}

@Composable
private fun ImagePreview(file: File) {
    val bitmap by produceState<Bitmap?>(null, file) { value = withContext(Dispatchers.IO) { decode(file, 2048) } }
    Box(Modifier.fillMaxSize().scanlines(Cp.Background.copy(alpha = 0.15f), 3.dp), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Message("Chargement…")
    }
}

/** Scaled down while decoding, so a 50 Mpx photo doesn't fill the memory. */
private fun decode(file: File, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@Composable
private fun TextPreview(file: File) {
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.inputStream().use { s -> ByteArray(TEXT_LIMIT).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
                val t = String(bytes, Charsets.UTF_8)
                if (file.length() > TEXT_LIMIT) "$t\n\n… (aperçu limité à ${FileOps.size(TEXT_LIMIT.toLong())})" else t
            }.getOrElse { "Lecture impossible : ${it.message}" }
        }
    }
    val t = text
    if (t == null) {
        Message("Chargement…")
    } else {
        SelectionContainer {
            Text(
                t, color = Cp.Text, fontFamily = MonoFont, fontSize = 13.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
            )
        }
    }
}

@Composable
private fun ZipPreview(file: File) {
    val entries by produceState<List<Zip.Entry>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { Zip.entries(file) }.getOrDefault(emptyList()) }
    }
    val list = entries
    when {
        list == null -> Message("Lecture de l'archive…")
        list.isEmpty() -> Message("Archive vide ou illisible.")
        else -> LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
            item {
                Text(
                    "${list.count { !it.isDirectory }} fichiers · ${FileOps.size(list.sumOf { it.size.coerceAtLeast(0) })} une fois extraits",
                    color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            items(list) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        e.name, color = if (e.isDirectory) Cp.Cyan else Cp.Text, fontFamily = MonoFont, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (!e.isDirectory && e.size >= 0) Text(FileOps.size(e.size), color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
                }
            }
        }
    }
}
