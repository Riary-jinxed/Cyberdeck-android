package dev.netrunner.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.gallery.data.MediaRepo
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** All photos (or one album), newest first, under a header per month. */
@Composable
fun PhotosTab(state: GalleryState) {
    val all = state.media
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.album?.uppercase() ?: "TOUTES LES PRISES", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (state.album != null) Chip("✕ ALBUM", Cp.Dim) { state.album = null }
            Chip(if (state.holo) "HOLO ✓" else "HOLO", if (state.holo) Cp.Yellow else Cp.Dim) { state.toggleHolo() }
            Chip("▦ ${state.columns}", Cp.Cyan) { state.cycleColumns() }
        }
        when {
            all == null -> Message("Lecture de la galerie…")
            all.isEmpty() -> Message("Aucune photo ni vidéo sur le téléphone.")
            else -> {
                val shown = remember(all, state.album) { if (state.album == null) all else all.filter { it.bucket == state.album } }
                val months = remember(shown) { shown.groupBy { monthKey(it.taken) } }
                LazyVerticalGrid(
                    GridCells.Fixed(state.columns), Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    months.forEach { (month, items) ->
                        item(key = "m$month", span = { GridItemSpan(maxLineSpan) }) {
                            SectionTitle("${month.second} · ${items.size}", Cp.Cyan, Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        items(items, key = { it.id }) { m ->
                            Thumb(m, state.holo, Modifier.aspectRatio(1f).clickable { state.viewer = shown to shown.indexOf(m) })
                        }
                    }
                }
            }
        }
    }
}

private val FR = Locale.FRANCE

/** (sortable key, "OCTOBRE 2026") */
private fun monthKey(ms: Long): Pair<Int, String> {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return (d.year * 12 + d.monthValue) to "${d.month.getDisplayName(TextStyle.FULL, FR)} ${d.year}".uppercase(FR)
}
