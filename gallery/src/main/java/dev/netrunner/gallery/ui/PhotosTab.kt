package dev.netrunner.gallery.ui

import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.hudShape
import dev.netrunner.gallery.data.MediaRepo
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** All photos (or one album, or the favourites), newest first, under a header per month. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PhotosTab(state: GalleryState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    val all = state.media
    var picking by remember { mutableStateOf(false) }
    val selecting = state.selection.isNotEmpty()

    val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Supprimé", Toast.LENGTH_SHORT).show()
            state.selection = emptySet()
        }
    }

    BackHandler(enabled = selecting) { state.selection = emptySet() }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.favoritesOnly -> "FAVORIS"
                    else -> state.album?.uppercase() ?: "TOUTES LES PRISES"
                },
                color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (state.album != null) Chip("✕ ALBUM", Cp.Dim) { state.album = null }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Chip(if (state.favoritesOnly) "★ FAVORIS ✓" else "★ FAVORIS", if (state.favoritesOnly) Cp.Yellow else Cp.Dim) {
                state.favoritesOnly = !state.favoritesOnly
            }
            Chip("DATE", Cp.Cyan) { picking = true }
            Chip(if (state.holo) "HOLO ✓" else "HOLO", if (state.holo) Cp.Yellow else Cp.Dim) { state.toggleHolo() }
            Chip("▦ ${state.columns}", Cp.Cyan) { state.cycleColumns() }
        }
        when {
            all == null -> Message("Lecture de la galerie…")
            all.isEmpty() -> Message("Aucune photo ni vidéo sur le téléphone.")
            else -> {
                val shown = remember(all, state.album, state.favoritesOnly, state.favorites) {
                    all.filter { (state.album == null || it.bucket == state.album) && (!state.favoritesOnly || it.id in state.favorites) }
                }
                val months = remember(shown) { shown.groupBy { monthKey(it.taken) } }
                // Grid position of each month header, to jump to a date.
                val headerIndex = remember(months) {
                    var i = 0
                    months.map { (month, items) -> (month to i).also { i += 1 + items.size } }.toMap()
                }
                if (shown.isEmpty()) {
                    Message(if (state.favoritesOnly) "Aucun favori : appui long sur des photos, puis ★." else "Album vide.")
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(state.columns), Modifier.weight(1f).fillMaxWidth(), state = grid,
                        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        months.forEach { (month, items) ->
                            item(key = "m${month.first}", span = { GridItemSpan(maxLineSpan) }) {
                                SectionTitle("${month.second} · ${items.size}", Cp.Cyan, Modifier.padding(top = 10.dp, bottom = 4.dp))
                            }
                            items(items, key = { it.id }) { m ->
                                Thumb(
                                    m, state.holo,
                                    Modifier.aspectRatio(1f).combinedClickable(
                                        onClick = { if (selecting) state.toggleSelected(m.id) else state.viewer = shown to shown.indexOf(m) },
                                        onLongClick = { state.toggleSelected(m.id) },
                                    ),
                                    favorite = m.id in state.favorites,
                                    selected = m.id in state.selection,
                                )
                            }
                        }
                    }
                }

                if (selecting) {
                    val picked = all.filter { it.id in state.selection }
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f), cut = 8.dp).padding(10.dp)) {
                        Text(
                            "${picked.size} SÉLECTIONNÉ${if (picked.size > 1) "S" else ""}", color = Cp.Yellow, fontFamily = HudFont,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp,
                        )
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                            Chip("TOUT", Cp.Cyan) { state.selection = shown.mapTo(HashSet()) { it.id } }
                            Chip("PARTAGER", Cp.Cyan) { share(context, picked) }
                            Chip(if (picked.all { it.id in state.favorites }) "☆ RETIRER" else "★ FAVORI", Cp.Yellow) {
                                state.toggleFavorite(picked.map { it.id })
                                state.selection = emptySet()
                            }
                            Chip("SUPPR.", Cp.Red) {
                                if (Build.VERSION.SDK_INT >= 30) {
                                    // Android asks once for the whole batch.
                                    val sender = MediaStore.createDeleteRequest(context.contentResolver, picked.map { it.uri }).intentSender
                                    deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
                                } else {
                                    scope.launch {
                                        for (m in picked) {
                                            try {
                                                context.contentResolver.delete(m.uri, null, null)
                                            } catch (e: RecoverableSecurityException) {
                                                deleteRequest.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                                                break
                                            }
                                        }
                                        state.selection = emptySet()
                                    }
                                }
                            }
                            Chip("ANNULER", Cp.Dim) { state.selection = emptySet() }
                        }
                    }
                }

                if (picking) {
                    AlertDialog(
                        onDismissRequest = { picking = false },
                        containerColor = Cp.Panel,
                        shape = hudShape(14.dp),
                        title = { Text("ALLER À…", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                months.keys.groupBy { it.first / 12 }.forEach { (year, list) ->
                                    SectionTitle("$year", Cp.Cyan, Modifier.padding(top = 8.dp, bottom = 4.dp))
                                    FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        list.forEach { month ->
                                            Chip(month.second.substringBefore(' '), Cp.Text) {
                                                picking = false
                                                headerIndex[month]?.let { scope.launch { grid.animateScrollToItem(it) } }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { HudButton("FERMER", color = Cp.Dim) { picking = false } },
                    )
                }
            }
        }
    }
}

/** Several photos at once: one share sheet with all of them. */
private fun share(context: Context, items: List<MediaRepo.Media>) {
    if (items.isEmpty()) return
    val uris = items.map { it.uri }
    val types = items.map { it.mime }.distinct()
    val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = type
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Partager"))
}

private val FR = Locale.FRANCE

/** (sortable key, "OCTOBRE 2026") */
private fun monthKey(ms: Long): Pair<Int, String> {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return (d.year * 12 + d.monthValue - 1) to "${d.month.getDisplayName(TextStyle.FULL, FR)} ${d.year}".uppercase(FR)
}
