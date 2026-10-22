package dev.netrunner.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import dev.netrunner.music.data.Library

/** Titles, albums and artists; a tap plays from there, a long press queues. */
@Composable
fun LibraryTab(tracks: List<Library.Track>?, player: PlayerState, onPlayed: () -> Unit) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openKey != null) { openKey = null }

    if (tracks == null) return Message("Lecture de la bibliothèque…")
    if (tracks.isEmpty()) return Message("Aucune musique trouvée. Copie des fichiers audio (MP3, FLAC, M4A…) dans le dossier Music du téléphone.")

    val found = remember(tracks, query) { Library.search(tracks, query) }
    val groups = remember(found, view) {
        when (view) {
            1 -> Library.albums(found)
            2 -> Library.artists(found)
            else -> emptyList()
        }
    }
    val open = openKey?.let { k -> groups.firstOrNull { it.key == k } }

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        TextField(
            query, { query = it }, singleLine = true, colors = hudFieldColors(),
            placeholder = { Text("> titre, artiste, album", fontFamily = MonoFont, color = Cp.Dim) },
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("TITRES", "ALBUMS", "ARTISTES").forEachIndexed { i, label ->
                Chip(label, if (view == i) Cp.Yellow else Cp.Dim) { view = i; openKey = null }
            }
            Text(
                "${found.size} titre${if (found.size > 1) "s" else ""}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        when {
            view == 0 -> TrackList(found, player, onPlayed, header = null)
            open != null -> TrackList(open.tracks, player, onPlayed, header = open.title to { openKey = null })
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(groups, key = { it.key }) { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable { openKey = g.key }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (view == 1) Cover(g.tracks.first().id, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(start = if (view == 1) 10.dp else 0.dp)) {
                            Text(g.title, color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(g.subtitle, color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("▶", color = Cp.Cyan, fontSize = 18.sp, modifier = Modifier.clickable { player.play(g.tracks, 0); onPlayed() }.padding(10.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackList(tracks: List<Library.Track>, player: PlayerState, onPlayed: () -> Unit, header: Pair<String, () -> Unit>?) {
    var menu by remember { mutableStateOf<Library.Track?>(null) }
    val current = player.item?.mediaId
    LazyColumn(Modifier.fillMaxSize()) {
        header?.let { (title, back) ->
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("◄ ", color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.clickable(onClick = back).padding(4.dp))
                    Text(title.uppercase(), color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Chip("TOUT ▶", Cp.Yellow) { player.play(tracks, 0); onPlayed() }
                    Chip("⤨", Cp.Cyan) { player.play(tracks.shuffled(), 0); onPlayed() }
                }
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            val playing = t.id.toString() == current
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { player.play(tracks, i); onPlayed() }, onLongClick = { menu = t })
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (playing) "▶ " else "") + t.title, color = if (playing) Cp.Yellow else Cp.Text, fontFamily = HudFont,
                        fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${t.artist} · ${t.album}", color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(Library.time(t.duration), color = Cp.Cyan, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                DropdownMenu(expanded = menu == t, onDismissRequest = { menu = null }, containerColor = Cp.Panel) {
                    DropdownMenuItem(text = { MenuText("Lire ensuite") }, onClick = { player.playNext(t); menu = null })
                    DropdownMenuItem(text = { MenuText("Ajouter à la file") }, onClick = { player.enqueue(t); menu = null })
                }
            }
        }
    }
}

@Composable
private fun MenuText(text: String) = Text(text, color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp)

@Composable
fun Chip(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, maxLines = 1,
        modifier = Modifier.padding(end = 6.dp).hudPanel(borderColor = color.copy(alpha = 0.6f), cut = 5.dp).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun Message(text: String) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
}
