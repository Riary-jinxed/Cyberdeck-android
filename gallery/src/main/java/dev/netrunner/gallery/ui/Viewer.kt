package dev.netrunner.gallery.ui

import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import dev.netrunner.deck.scanlines
import dev.netrunner.gallery.data.Holo
import dev.netrunner.gallery.data.MediaRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen viewer: swipe between photos, pinch or double-tap to zoom, holo filter, share, export, delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(state: GalleryState, items: List<MediaRepo.Media>, start: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var holo by remember { mutableStateOf(state.holo) }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val current = items.getOrNull(pager.currentPage)

    val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Supprimé", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }

    fun delete(m: MediaRepo.Media) {
        // Android asks the user itself: deleting other apps' media needs their confirmation.
        if (Build.VERSION.SDK_INT >= 30) {
            val sender = MediaStore.createDeleteRequest(context.contentResolver, listOf(m.uri)).intentSender
            deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            try {
                context.contentResolver.delete(m.uri, null, null)
                onClose()
            } catch (e: RecoverableSecurityException) {
                deleteRequest.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            BackHandler(enabled = info) { info = false }
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { items[it].id.let { id -> if (id >= 0) id else it.toLong() } }) { page ->
                Page(items[page], holo && !items[page].isVideo, active = page == pager.currentPage, onZoom = { zoomed = it }, onTap = { chrome = !chrome })
            }

            if (chrome && current != null) {
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))).systemBarsPadding().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✕", color = Cp.Text, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onClose).padding(end = 14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.name, color = Cp.Text, fontFamily = MonoFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${pager.currentPage + 1}/${items.size} · ${dateText(current.taken)}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp,
                            )
                        }
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .systemBarsPadding().horizontalScroll(rememberScrollState()).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (current.isVideo) {
                        Chip("▶ LIRE", Cp.Yellow) { openExternal(context, current, Intent.ACTION_VIEW) }
                    } else {
                        Chip(if (holo) "HOLO ✓" else "HOLO", if (holo) Cp.Yellow else Cp.Cyan) { holo = !holo }
                    }
                    Chip("PARTAGER", Cp.Cyan) {
                        val send = Intent(Intent.ACTION_SEND).setType(current.mime).putExtra(Intent.EXTRA_STREAM, current.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Partager"))
                    }
                    if (!current.isVideo) {
                        Chip("EXPORT HOLO", Cp.Cyan) {
                            scope.launch {
                                val c = Cp.Cyan
                                val uri = withContext(Dispatchers.Default) {
                                    val src = MediaRepo.picture(context, current.uri, 2400) ?: return@withContext null
                                    val out = Holo.render(src, c.red, c.green, c.blue)
                                    MediaRepo.saveCopy(context, out, "holo_" + current.name.substringBeforeLast('.') + ".jpg")
                                }
                                Toast.makeText(context, if (uri != null) "Copie holo dans Images/Netrunner" else "Export impossible", Toast.LENGTH_SHORT).show()
                            }
                        }
                        Chip("MODIFIER", Cp.Cyan) { openExternal(context, current, Intent.ACTION_EDIT) }
                    }
                    Chip("INFOS", Cp.Cyan) { info = !info }
                    if (current.id >= 0) Chip("SUPPR.", Cp.Red) { delete(current) }
                }
            }

            if (info && current != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp).background(Cp.Panel.copy(alpha = 0.95f)).padding(16.dp),
                ) {
                    Text("// FICHE", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
                    InfoLine("Nom", current.name)
                    InfoLine("Album", current.bucket)
                    InfoLine("Date", dateText(current.taken))
                    if (current.width > 0) InfoLine("Taille", "${current.width} × ${current.height} px")
                    if (current.size > 0) InfoLine("Poids", "%.1f Mo".format(Locale.FRANCE, current.size / 1_048_576.0))
                    if (current.isVideo) InfoLine("Durée", duration(current.duration))
                    InfoLine("Type", current.mime)
                    Text("OK", color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp).clickable { info = false })
                }
            }
        }
    }
}

@Composable
private fun Page(media: MediaRepo.Media, holo: Boolean, active: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current
    val maxSide = LocalConfiguration.current.let { maxOf(it.screenWidthDp, it.screenHeightDp) } * 3
    // Thumbnail first, then the full picture.
    val thumb by produceState<ImageBitmap?>(null, media.id) {
        if (media.id >= 0) value = MediaRepo.thumbnail(context, media, 320)?.asImageBitmap()
    }
    val full by produceState<ImageBitmap?>(null, media.uri, active) {
        if (active && !media.isVideo && value == null) value = MediaRepo.picture(context, media.uri, maxSide)?.asImageBitmap()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + pan * scale else Offset.Zero
        onZoom(scale > 1.01f)
    }
    val image = full ?: thumb
    Box(
        Modifier.fillMaxSize()
            .pointerInput(media.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (media.isVideo) return@detectTapGestures
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                        onZoom(scale > 1f)
                    },
                )
            }
            .transformable(transform, canPan = { scale > 1.01f }),
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(
                it, null, contentScale = ContentScale.Fit, colorFilter = if (holo) holoFilter() else null,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .then(if (holo) Modifier.scanlines(Color.Black.copy(alpha = 0.28f), 3.dp).vignette() else Modifier),
            )
        }
        if (media.isVideo) Text("▶", color = Cp.Yellow, fontSize = 64.sp)
    }
}

private fun Modifier.vignette(): Modifier = drawWithContent {
    drawContent()
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.6f)), center, size.maxDimension * 0.75f))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Text(label, color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.weight(0.3f))
        Text(value, color = Cp.Text, fontFamily = MonoFont, fontSize = 14.sp, modifier = Modifier.weight(0.7f))
    }
}

private fun openExternal(context: android.content.Context, m: MediaRepo.Media, action: String) {
    val intent = Intent(action).setDataAndType(m.uri, m.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, m.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Aucune appli disponible", Toast.LENGTH_SHORT).show()
    }
}

private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

fun dateText(ms: Long): String = dateFormat.format(Date(ms))
