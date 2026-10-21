package dev.netrunner.notes.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudShape
import dev.netrunner.notes.data.Note

/** Full-screen editor. Closing (✕ or back) saves; an emptied note is dropped. */
@Composable
fun EditorDialog(state: NotesState, original: Note) {
    val context = LocalContext.current
    var note by remember(original.id) { mutableStateOf(original) }
    var confirmDelete by remember { mutableStateOf(false) }
    val color = noteColor(note.color)
    val close = { state.commit(note); state.editing = null }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Cp.Background).systemBarsPadding().imePadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✕", color = Cp.Text, fontSize = 22.sp, modifier = Modifier.clickable(onClick = close).padding(end = 10.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (note.pinned) "▲ ÉPINGLÉE" else "ÉPINGLER", if (note.pinned) Cp.Yellow else Cp.Dim) { note = note.copy(pinned = !note.pinned) }
                    Chip(if (note.checklist) "TEXTE" else "LISTE", Cp.Cyan) { note = note.toggleChecklist() }
                    Chip("PARTAGER", Cp.Cyan) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, note.shareText())
                        if (note.title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, note.title)
                        context.startActivity(Intent.createChooser(send, "Partager la note"))
                    }
                    Chip("SUPPR.", Cp.Red) { confirmDelete = true }
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                noteColors.indices.forEach { i ->
                    val c = noteColor(i)
                    Box(
                        Modifier.padding(end = 8.dp).size(if (i == note.color) 26.dp else 20.dp).background(c, hudShape(4.dp))
                            .clickable { note = note.copy(color = i) },
                    )
                }
            }
            TextField(
                note.title, { note = note.copy(title = it) }, colors = plainField(), singleLine = true,
                placeholder = { Text("Titre", fontFamily = HudFont, fontSize = 20.sp, color = Cp.Dim) },
                textStyle = TextStyle(fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (note.checklist) {
                Checklist(note.items) { note = note.copy(items = it) }
            } else {
                TextField(
                    note.body, { note = note.copy(body = it) }, colors = plainField(),
                    placeholder = { Text("> écris ici", fontFamily = MonoFont, color = Cp.Dim) },
                    textStyle = TextStyle(fontFamily = HudFont, fontSize = 17.sp, color = Cp.Text),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            Text(
                "Modifiée ${Note.age(note.updated)}" + if (note.source == "aska") " · écrite par As//ka" else "",
                color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Cp.Panel,
            shape = hudShape(14.dp),
            title = { Text("SUPPRIMER ?", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { Text("Cette note sera effacée.", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp) },
            confirmButton = {
                HudButton("SUPPRIMER", color = Cp.Red, filled = true) {
                    confirmDelete = false
                    state.delete(note)
                    state.editing = null
                }
            },
            dismissButton = { HudButton("ANNULER", color = Cp.Dim) { confirmDelete = false } },
        )
    }
}

/** Unticked items first, then the ticked ones; "Entrée" adds an item below. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Checklist(items: List<Note.Item>, onChange: (List<Note.Item>) -> Unit) {
    val order = items.indices.sortedBy { items[it].done }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        itemsIndexed(order, key = { _, i -> i }) { _, i ->
            val item = items[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.done) "☑" else "☐", color = if (item.done) Cp.Dim else Cp.Yellow, fontSize = 22.sp,
                    modifier = Modifier.clickable { onChange(items.toMutableList().also { it[i] = item.copy(done = !item.done) }) }.padding(6.dp),
                )
                TextField(
                    item.text, { t -> onChange(items.toMutableList().also { it[i] = item.copy(text = t.replace("\n", "")) }) },
                    colors = plainField(), singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = HudFont, fontSize = 17.sp, color = if (item.done) Cp.Dim else Cp.Text,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { onChange(items.toMutableList().also { it.add(i + 1, Note.Item("")) }) }),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕", color = Cp.Dim, fontSize = 16.sp,
                    modifier = Modifier.clickable { onChange(items.toMutableList().also { it.removeAt(i) }) }.padding(8.dp),
                )
            }
        }
        item {
            Text(
                "+ ÉLÉMENT", color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.clickable { onChange(items + Note.Item("")) }.padding(10.dp),
            )
        }
    }
}

@Composable
private fun plainField() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
    cursorColor = Cp.Yellow,
)
