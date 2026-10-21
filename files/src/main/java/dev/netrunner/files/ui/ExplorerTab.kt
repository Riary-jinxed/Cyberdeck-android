package dev.netrunner.files.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import dev.netrunner.files.data.FileOps
import dev.netrunner.files.system.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Colour of each file family, faction-aware. */
fun kindColor(kind: FileOps.Kind): Color = when (kind) {
    FileOps.Kind.DIR -> Cp.Yellow
    FileOps.Kind.IMAGE, FileOps.Kind.VIDEO -> Cp.Cyan
    FileOps.Kind.AUDIO -> Cp.Cyan.copy(alpha = 0.8f)
    FileOps.Kind.APK, FileOps.Kind.ARCHIVE -> Cp.Red
    else -> Cp.Text
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerTab(explorer: Explorer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf<Pair<String, File?>?>(null) } // title, file to rename (null = new folder)
    var deleting by remember { mutableStateOf<List<File>?>(null) }
    var info by remember { mutableStateOf<File?>(null) }

    val rows by produceState<List<Explorer.Row>?>(
        emptyList(), explorer.dir, explorer.expanded, explorer.sort, explorer.descending, explorer.showHidden, explorer.version,
    ) {
        value = withContext(Dispatchers.IO) { explorer.rows() }
    }
    val roots = remember { Storage.roots(context) }
    val selecting = explorer.selection.isNotEmpty()

    BackHandler(enabled = selecting) { explorer.selection = emptySet() }
    BackHandler(enabled = !selecting && explorer.dir.parentFile != null && roots.none { it.dir == explorer.dir }) { explorer.up() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // Prompt line, as in a shell.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("runner@deck", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 15.sp)
            Text(":", color = Cp.Dim, fontFamily = MonoFont, fontSize = 15.sp)
            Text(
                Storage.pretty(explorer.dir), color = Cp.Cyan, fontFamily = MonoFont, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Text("$ ls", color = Cp.Dim, fontFamily = MonoFont, fontSize = 15.sp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            roots.forEach { r ->
                val on = FileOps.isInside(explorer.dir, r.dir) && roots.none { it != r && FileOps.isInside(explorer.dir, it.dir) && it.dir.absolutePath.length > r.dir.absolutePath.length }
                Chip(r.label, if (on) Cp.Yellow else Cp.Dim) { explorer.go(r.dir) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("+ DOSSIER", Cp.Cyan) { naming = "NOUVEAU DOSSIER" to null }
            Chip("TRI : ${explorer.sort.label} ${if (explorer.descending) "▼" else "▲"}", Cp.Cyan) {
                val all = FileOps.Sort.entries
                explorer.sortBy(all[(all.indexOf(explorer.sort) + 1) % all.size])
            }
            Chip(if (explorer.showHidden) "CACHÉS ✓" else "CACHÉS", if (explorer.showHidden) Cp.Yellow else Cp.Dim) { explorer.toggleHidden() }
        }
        Spacer(Modifier.padding(top = 6.dp))

        val list = rows
        Box(Modifier.weight(1f).fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f), cut = 8.dp)) {
            when {
                list == null -> Message("Lecture refusée : ce dossier est protégé par Android.")
                else -> LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                    if (explorer.dir.parentFile?.canRead() == true && roots.none { it.dir == explorer.dir }) {
                        item {
                            Text(
                                "▴ ..", color = Cp.Dim, fontFamily = MonoFont, fontSize = 16.sp,
                                modifier = Modifier.fillMaxWidth().clickable { explorer.up() }.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (list.isEmpty()) item { Message("Dossier vide.") }
                    items(list, key = { it.file.absolutePath }) { row ->
                        val f = row.file
                        val selected = f in explorer.selection
                        val kind = FileOps.kind(f)
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (selected) Cp.Yellow.copy(alpha = 0.16f) else Color.Transparent)
                                .combinedClickable(
                                    onLongClick = { explorer.toggleSelected(f) },
                                    onClick = {
                                        when {
                                            selecting -> explorer.toggleSelected(f)
                                            f.isDirectory -> explorer.go(f)
                                            else -> Storage.open(context, f)
                                        }
                                    },
                                )
                                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (row.depth > 0) {
                                Text(row.guides + if (row.isLast) "└─" else "├─", color = Cp.Dim, fontFamily = MonoFont, fontSize = 15.sp, maxLines = 1)
                            }
                            // The chevron unfolds a folder in place; tapping the name enters it.
                            val open = f.absolutePath in explorer.expanded
                            Text(
                                if (kind == FileOps.Kind.DIR) (if (open) "▾" else "▸") else kind.glyph,
                                color = kindColor(kind), fontFamily = MonoFont, fontSize = 16.sp,
                                modifier = Modifier.width(28.dp)
                                    .then(if (f.isDirectory && !selecting) Modifier.clickable { explorer.toggleExpanded(f) } else Modifier)
                                    .padding(horizontal = 4.dp),
                            )
                            Text(
                                f.name + if (f.isDirectory) "/" else "", color = if (selected) Cp.Yellow else kindColor(kind),
                                fontFamily = MonoFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                                if (f.isFile) Text(FileOps.size(f.length()), color = Cp.Text.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 12.sp)
                                Text(FileOps.date(f), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Actions on the selection, or the pending copy/move.
        when {
            selecting -> {
                val sel = explorer.selection.toList()
                ActionBar("${sel.size} SÉLECTIONNÉ${if (sel.size > 1) "S" else ""}") {
                    Chip("TOUT", Cp.Cyan) { explorer.selection = rows.orEmpty().filter { it.depth == 0 }.map { it.file }.toSet() }
                    Chip("COPIER", Cp.Cyan) { explorer.clipboard = sel; explorer.cut = false; explorer.selection = emptySet() }
                    Chip("COUPER", Cp.Cyan) { explorer.clipboard = sel; explorer.cut = true; explorer.selection = emptySet() }
                    Chip("PARTAGER", Cp.Cyan) { Storage.share(context, sel) }
                    if (sel.size == 1) {
                        Chip("RENOMMER", Cp.Cyan) { naming = "RENOMMER" to sel[0] }
                        Chip("INFOS", Cp.Cyan) { info = sel[0] }
                    }
                    Chip("SUPPR.", Cp.Red) { deleting = sel }
                }
            }
            explorer.clipboard.isNotEmpty() -> {
                val clip = explorer.clipboard
                ActionBar("${clip.size} À ${if (explorer.cut) "DÉPLACER" else "COPIER"}") {
                    Chip("COLLER ICI", Cp.Yellow) {
                        val target = explorer.dir
                        val move = explorer.cut
                        explorer.clipboard = emptyList()
                        scope.fileOp(context, explorer, if (move) "Déplacé" else "Copié") {
                            clip.forEach { if (move) FileOps.moveInto(it, target) else FileOps.copyInto(it, target) }
                        }
                    }
                    Chip("ANNULER", Cp.Dim) { explorer.clipboard = emptyList() }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    naming?.let { (title, file) ->
        NameDialog(title, file?.name ?: "", if (file == null) "CRÉER" else "RENOMMER", onDismiss = { naming = null }) { name ->
            naming = null
            explorer.selection = emptySet()
            val parent = explorer.dir
            scope.fileOp(context, explorer, if (file == null) "Dossier créé" else "Renommé") {
                if (file == null) FileOps.mkdir(parent, name) else FileOps.rename(file, name)
            }
        }
    }
    deleting?.let { files ->
        DeleteDialog(files, onDismiss = { deleting = null }) {
            deleting = null
            explorer.selection = emptySet()
            scope.fileOp(context, explorer, "Supprimé") { files.forEach(FileOps::delete) }
        }
    }
    info?.let { InfoDialog(it) { info = null } }
}

@Composable
fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, maxLines = 1,
        modifier = Modifier.hudPanel(borderColor = color.copy(alpha = 0.6f), cut = 5.dp).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun ActionBar(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f), cut = 8.dp).padding(10.dp)) {
        Text(title, color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
fun Message(text: String) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
}

/** Shown until "All files access" is granted. */
@Composable
fun AccessGate(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("ACCÈS REFUSÉ", color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 3.sp)
        Text(
            "Pour parcourir tout le stockage, Android demande l'autorisation « Accès à tous les fichiers ». " +
                "Rien ne quitte le téléphone : l'appli n'a pas d'accès réseau hors mises à jour.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(vertical = 14.dp),
        )
        HudButton("AUTORISER", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onGrant)
    }
}
