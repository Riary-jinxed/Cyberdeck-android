package dev.netrunner.aska

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState

/** The pictures made together in ComfyUI: the latest render, its prompt and seed, and what to do next. */
@Composable
fun StudioWindow(aska: Aska, pictures: List<Studio.Picture>, onClose: () -> Unit) {
    var shown by remember(pictures) { mutableStateOf(0) }
    val picture = pictures[shown.coerceIn(0, pictures.lastIndex)]
    val bitmap = remember(picture.file) {
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(picture.file.readBytes()).toComposeImageBitmap() }.getOrNull()
    }
    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(size = DpSize(620.dp, 820.dp)),
        title = "As//ka : atelier ComfyUI",
        icon = remember { androidx.compose.ui.graphics.painter.BitmapPainter(sprite("chibi_happy")) },
    ) {
        Column(Modifier.fillMaxSize().background(Cp.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap, "Image générée", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else T("Image illisible : ${picture.file.name}", Cp.Red, 14.sp)
            }
            T(picture.prompt, Cp.Text, 12.sp, MonoFont)
            T("seed ${picture.seed} · ${picture.workflow} · ${picture.file.name}" + if (pictures.size > 1) " · ${shown + 1}/${pictures.size}" else "", Cp.Dim, 12.sp, MonoFont)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pictures.size > 1) StudioButton("SUIVANTE", Cp.Cyan) { shown = (shown + 1) % pictures.size }
                StudioButton("VARIANTE", Cp.Pink) { aska.send("Fais une variante de cette image (même prompt, nouveau seed).") }
                StudioButton("LUI EN PARLER", Cp.Pink) { aska.openChat() }
                StudioButton("DOSSIER", Cp.Cyan) { runCatching { java.awt.Desktop.getDesktop().open(Studio.output) } }
                StudioButton("FERMER", Cp.Dim, onClose)
            }
        }
    }
}

@Composable
private fun StudioButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    T(label, color, 13.sp, TitleFont, modifier = Modifier.hudPanel(color, Cp.Panel, 8.dp).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}
