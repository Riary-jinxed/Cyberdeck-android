package dev.netrunner.notes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import dev.netrunner.notes.data.Note

/** The notes as cards, pinned first, with a search over titles and content. */
@Composable
fun NotesTab(state: NotesState) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(state.notes, query) { Note.filter(state.notes, query) }

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("runner@deck", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 15.sp)
            Text(":~/notes$ ", color = Cp.Dim, fontFamily = MonoFont, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Chip("+ NOTE", Cp.Yellow) { state.new() }
            Chip("+ LISTE", Cp.Cyan) { state.new(checklist = true) }
        }
        TextField(
            query, { query = it }, singleLine = true, colors = hudFieldColors(),
            placeholder = { Text("grep…", fontFamily = MonoFont, color = Cp.Dim) },
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        when {
            state.notes.isEmpty() -> Message("Aucune note. « + NOTE » pour écrire, « + LISTE » pour une liste à cocher.")
            shown.isEmpty() -> Message("Rien ne correspond à « $query ».")
            else -> LazyVerticalStaggeredGrid(
                StaggeredGridCells.Fixed(2), Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp,
            ) {
                items(shown, key = { it.id }) { note -> NoteCard(note, onOpen = { state.editing = note }, onPin = { state.togglePin(note) }) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(note: Note, onOpen: () -> Unit, onPin: () -> Unit) {
    val color = noteColor(note.color)
    Column(
        Modifier.fillMaxWidth().hudPanel(borderColor = color.copy(alpha = 0.7f), cut = 8.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onPin).padding(10.dp),
    ) {
        if (note.title.isNotBlank()) {
            Text(note.title, color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (note.checklist) {
            val open = note.items.filter { !it.done && it.text.isNotBlank() }
            val done = note.items.count { it.done }
            open.take(6).forEach {
                Text("☐ ${it.text}", color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (open.size > 6) Text("+ ${open.size - 6}", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
            if (done > 0) {
                Text(
                    "☑ $done fait${if (done > 1) "s" else ""}", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp,
                    textDecoration = TextDecoration.LineThrough,
                )
            }
        } else if (note.body.isNotBlank()) {
            Text(note.body, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, maxLines = 9, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Note.age(note.updated), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (note.source == "aska") Text("AS//KA ", color = Cp.Dim, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            if (note.pinned) Text("▲ ÉPINGLÉE", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}
