package dev.netrunner.gallery.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.scanlines
import dev.netrunner.gallery.data.Holo
import dev.netrunner.gallery.data.MediaRepo

/** The holo colour filter in the faction's secondary colour. */
fun holoFilter(): ColorFilter {
    val c = Cp.Cyan
    return ColorFilter.colorMatrix(ColorMatrix(Holo.matrix(c.red, c.green, c.blue)))
}

@Composable
fun Thumb(media: MediaRepo.Media, holo: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, media.id) {
        value = MediaRepo.thumbnail(context, media, 320)?.asImageBitmap()
    }
    Box(modifier.background(Cp.Panel)) {
        bitmap?.let {
            Image(
                it, null, contentScale = ContentScale.Crop, colorFilter = if (holo) holoFilter() else null,
                modifier = Modifier.fillMaxSize().then(if (holo) Modifier.scanlines(Color.Black.copy(alpha = 0.3f), 2.dp) else Modifier),
            )
        }
        if (media.isVideo) {
            Text(
                "▶ " + duration(media.duration), color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Cp.Background.copy(alpha = 0.6f)).padding(horizontal = 4.dp),
            )
        }
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
