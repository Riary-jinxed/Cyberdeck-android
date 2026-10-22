package dev.netrunner.osint.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import dev.netrunner.osint.data.Case
import dev.netrunner.osint.data.Casebook

/** The case notebook: saved results and free notes, exported to Notes or shared as text. */
@Composable
fun CaseTab(state: OsintState) {
    val context = LocalContext.current
    val case = state.case
    var note by rememberSaveable { mutableStateOf("") }
    var confirmNew by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionTitle("ENQUÊTE", Cp.Yellow, Modifier.padding(top = 12.dp))
            TextField(
                case.name, { state.rename(it) }, singleLine = true, colors = hudFieldColors(),
                placeholder = { Text("Nom de l'enquête…", fontFamily = HudFont, color = Cp.Dim) },
                textStyle = TextStyle(fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("→ NOTES", Cp.Yellow) {
                    when {
                        case.entries.isEmpty() -> Toast.makeText(context, "Le carnet est vide.", Toast.LENGTH_SHORT).show()
                        !Casebook.hasNotes(context) -> Toast.makeText(context, "Installe Notes Netrunner pour y exporter le carnet.", Toast.LENGTH_LONG).show()
                        else -> Casebook.toNotes(context, case)?.let { Casebook.openNote(context, it) }
                            ?: Toast.makeText(context, "Notes a refusé l'export (clé de signature différente ?).", Toast.LENGTH_LONG).show()
                    }
                }
                Chip("PARTAGER", Cp.Cyan) { if (case.entries.isNotEmpty()) shareText(context, "Enquête ${case.name}".trim(), case.asText()) }
                Chip("NOUVELLE", Cp.Red) { confirmNew = true }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    note, { note = it }, colors = hudFieldColors(), maxLines = 4,
                    placeholder = { Text("Note libre, piste, hypothèse…", fontFamily = MonoFont, color = Cp.Dim) },
                    textStyle = TextStyle(fontFamily = MonoFont, fontSize = 15.sp),
                    modifier = Modifier.weight(1f),
                )
                Chip("+", Cp.Yellow, Modifier.padding(start = 6.dp)) { state.addNote(note); note = "" }
            }
        }
        if (case.entries.isEmpty()) {
            item { Message("Carnet vide. « + CARNET » sur un résultat l'ajoute ici, avec la date.") }
        }
        items(case.entries.sortedByDescending { it.time }, key = { it.id }) { e -> EntryCard(e) { state.remove(e) } }
        item { Text(" ", modifier = Modifier.padding(bottom = 12.dp)) }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            containerColor = Cp.Panel,
            title = { Text("Nouvelle enquête ?", fontFamily = HudFont, color = Cp.Yellow) },
            text = { Text("Le carnet actuel sera vidé. Exporte-le vers Notes avant si tu veux le garder.", fontFamily = HudFont, color = Cp.Text) },
            confirmButton = { TextButton({ state.newCase(); confirmNew = false }) { Text("VIDER", color = Cp.Red, fontFamily = HudFont) } },
            dismissButton = { TextButton({ confirmNew = false }) { Text("ANNULER", color = Cp.Dim, fontFamily = HudFont) } },
        )
    }
}

@Composable
private fun EntryCard(e: Case.Entry, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().hudPanel(borderColor = (if (e.report != null) Cp.Cyan else Cp.Yellow).copy(alpha = 0.35f), cut = 6.dp).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Case.stamp(e.time), color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("✕", color = Cp.Dim, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onRemove).padding(start = 10.dp))
        }
        SelectionContainer {
            Text(e.body, color = Cp.Text, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
