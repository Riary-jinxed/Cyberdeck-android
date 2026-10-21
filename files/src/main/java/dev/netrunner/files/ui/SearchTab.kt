package dev.netrunner.files.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import dev.netrunner.files.data.FileOps
import dev.netrunner.files.system.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Name search below the explorer's current folder, as `find . -iname '*query*'`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchTab(explorer: Explorer, onReveal: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<File>()) }
    var running by remember { mutableStateOf(false) }
    val root = explorer.dir

    LaunchedEffect(query, root, explorer.showHidden, explorer.version) {
        results = emptyList()
        if (query.isBlank()) return@LaunchedEffect
        delay(300) // Waits for the typing to settle.
        running = true
        try {
            val found = mutableListOf<File>()
            withContext(Dispatchers.IO) {
                for (f in FileOps.search(root, query, explorer.showHidden)) {
                    ensureActive()
                    found += f
                    // Shows the first hits quickly, then in batches.
                    if (found.size == 20 || found.size % 100 == 0) {
                        val snapshot = found.toList()
                        withContext(Dispatchers.Main) { results = snapshot }
                    }
                    if (found.size >= LIMIT) break
                }
            }
            results = found.toList()
        } finally {
            running = false
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("runner@deck:${Storage.pretty(root)}$ find", color = Cp.Dim, fontFamily = MonoFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        TextField(
            query, { query = it }, singleLine = true, colors = hudFieldColors(),
            placeholder = { Text("Nom de fichier ou de dossier", fontFamily = HudFont, color = Cp.Dim) },
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 17.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        SectionTitle(
            when {
                query.isBlank() -> "TAPE UN NOM"
                running -> "RECHERCHE… ${results.size}"
                results.size >= LIMIT -> "$LIMIT PREMIERS RÉSULTATS"
                else -> "${results.size} RÉSULTAT${if (results.size > 1) "S" else ""}"
            },
            Cp.Yellow,
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f), cut = 8.dp)) {
            items(results, key = { it.absolutePath }) { f ->
                val kind = FileOps.kind(f)
                Row(
                    Modifier.fillMaxWidth().combinedClickable(
                        // Folders open in the explorer; files open directly, long press shows where they are.
                        onClick = { if (f.isDirectory) { explorer.go(f); onReveal() } else Storage.open(context, f) },
                        onLongClick = { f.parentFile?.let { explorer.go(it); explorer.selection = setOf(f); onReveal() } },
                    ).padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (f.isDirectory) "▸" else kind.glyph, color = kindColor(kind), fontFamily = MonoFont, fontSize = 16.sp, modifier = Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = kindColor(kind), fontFamily = MonoFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            f.parentFile?.relativeTo(root)?.path?.ifEmpty { "." } ?: "", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (f.isFile) Text(FileOps.size(f.length()), color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
                }
            }
        }
        Text("Appui long : afficher dans l'explorateur.", color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private const val LIMIT = 500
