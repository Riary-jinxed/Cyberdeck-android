package dev.netrunner.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.gallery.data.MediaRepo

/** One tile per folder (Camera, Screenshots, WhatsApp…). */
@Composable
fun AlbumsTab(state: GalleryState, onOpen: (String) -> Unit) {
    val all = state.media
    when {
        all == null -> Message("Lecture de la galerie…")
        all.isEmpty() -> Message("Aucun album.")
        else -> {
            val albums = remember(all) { MediaRepo.albums(all) }
            LazyVerticalGrid(
                GridCells.Fixed(2), Modifier.fillMaxSize().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(albums, key = { it.name }) { a ->
                    Column(Modifier.hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 8.dp).clickable { onOpen(a.name) }.padding(6.dp)) {
                        Thumb(a.cover, state.holo, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(
                            a.name, color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                        )
                        Text("${a.count} élément${if (a.count > 1) "s" else ""}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
