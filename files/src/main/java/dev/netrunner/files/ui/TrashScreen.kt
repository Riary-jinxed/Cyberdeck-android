package dev.netrunner.files.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.hudShape
import dev.netrunner.files.data.FileOps
import dev.netrunner.files.data.Trash
import dev.netrunner.files.system.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What was deleted to the bin: restore it where it was, or erase it for good. */
@Composable
fun TrashScreen(explorer: Explorer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val entries by produceState<List<Trash.Entry>?>(null, explorer.version) {
        value = withContext(Dispatchers.IO) { explorer.trash.list() }
    }
    BackHandler { if (selection.isNotEmpty()) selection = emptySet() else explorer.showTrash = false }
    val format = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◂", color = Cp.Cyan, fontSize = 22.sp, modifier = Modifier.clickable { explorer.showTrash = false }.padding(end = 10.dp))
            Text("CORBEILLE", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            if (!entries.isNullOrEmpty()) Chip("VIDER", Cp.Red) { confirmEmpty = true }
        }
        Text(
            "Les éléments sont effacés pour de bon après ${Trash.KEEP_DAYS} jours.",
            color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp),
        )
        val list = entries
        Box(Modifier.weight(1f).fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f), cut = 8.dp)) {
            when {
                list == null -> Message("Lecture…")
                list.isEmpty() -> Message("Corbeille vide.")
                else -> LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                    items(list, key = { it.id }) { e ->
                        val on = e.id in selection
                        val kind = FileOps.kind(e.item)
                        Row(
                            Modifier.fillMaxWidth().background(if (on) Cp.Yellow.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable { selection = if (on) selection - e.id else selection + e.id }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(kind.glyph, color = kindColor(kind), fontFamily = MonoFont, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.name + if (e.isDir) "/" else "", color = if (on) Cp.Yellow else kindColor(kind), fontFamily = MonoFont, fontSize = 15.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    Storage.pretty(File(e.origin).parentFile ?: File("/")), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                                if (!e.isDir) Text(FileOps.size(e.item.length()), color = Cp.Text.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 12.sp)
                                Text(format.format(Date(e.deletedAt)), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        if (selection.isNotEmpty()) {
            val picked = list.orEmpty().filter { it.id in selection }
            ActionBar("${picked.size} SÉLECTIONNÉ${if (picked.size > 1) "S" else ""}") {
                Chip("TOUT", Cp.Cyan) { selection = list.orEmpty().map { it.id }.toSet() }
                Chip("RESTAURER", Cp.Yellow) {
                    selection = emptySet()
                    scope.fileOp(context, explorer, "Restauré") { picked.forEach { explorer.trash.restore(it) } }
                }
                Chip("EFFACER", Cp.Red) {
                    selection = emptySet()
                    scope.fileOp(context, explorer, "Effacé") { picked.forEach { explorer.trash.purge(it) } }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            containerColor = Cp.Panel,
            shape = hudShape(14.dp),
            title = { Text("VIDER LA CORBEILLE ?", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { Text("Tout son contenu sera effacé définitivement.", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp) },
            confirmButton = {
                HudButton("VIDER", color = Cp.Red, filled = true) {
                    confirmEmpty = false
                    selection = emptySet()
                    scope.fileOp(context, explorer, "Corbeille vidée") { explorer.trash.empty() }
                }
            },
            dismissButton = { HudButton("ANNULER", color = Cp.Dim) { confirmEmpty = false } },
        )
    }
}
