package dev.netrunner.os.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.netrunner.bridge.Bridge
import dev.netrunner.os.Button
import dev.netrunner.os.Cp
import dev.netrunner.os.HudFont
import dev.netrunner.os.MonoFont
import dev.netrunner.os.T
import dev.netrunner.os.TitleFont
import dev.netrunner.os.badge
import dev.netrunner.os.hudPanel
import dev.netrunner.os.text.Doc.Align
import dev.netrunner.os.text.Doc.Kind
import dev.netrunner.os.text.Doc.Style
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JOptionPane

/** Netrunner Texte: the word processor, for Word and OpenDocument files. */
fun texte(args: List<String>) {
    val arg = args.firstOrNull()?.let { if (it.startsWith("file://")) File(java.net.URI(it)) else File(it) }
    application {
        val state = remember { TextState().apply { if (arg != null && arg.isFile) open(arg) else if (arg != null) file = arg } }
        DisposableEffect(state) {
            val stop = Bridge.serve(TextBridge(state))
            onDispose { stop() }
        }
        Window(
            onCloseRequest = { if (state.confirmDiscard("fermer")) exitApplication() },
            title = (if (state.dirty) "● " else "") + (state.file?.name ?: "Sans titre") + " · Texte",
            icon = remember { badge(Cp.Cyan) },
            state = rememberWindowState(width = 1150.dp, height = 900.dp),
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.isCtrlPressed && state.shortcut(e) },
        ) {
            TextScreen(state)
        }
    }
}

class TextState {
    var doc by mutableStateOf(Doc())
        private set
    var value by mutableStateOf(TextFieldValue(""))
        private set
    var file by mutableStateOf<File?>(null)
    var dirty by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    /** Styles chosen with nothing selected: what typing writes next, until the cursor moves. */
    var typing by mutableStateOf<Set<Style>?>(null)
        private set
    var find by mutableStateOf<String?>(null)
    var replace by mutableStateOf("")

    private class Snapshot(val doc: Doc, val selection: TextRange)
    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private var lastPush = 0L

    val selection: TextRange get() = value.selection
    val selectedText: String get() = doc.text.substring(selection.min, selection.max)

    /** Every change of the document goes here, so it can be undone. */
    private fun commit(next: Doc, sel: TextRange, coalesce: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!coalesce || now - lastPush > 1200) {
            undo.addLast(Snapshot(doc, value.selection))
            if (undo.size > 300) undo.removeFirst()
            lastPush = now
        }
        redo.clear()
        doc = next
        value = TextFieldValue(next.text, TextRange(sel.start.coerceIn(0, next.text.length), sel.end.coerceIn(0, next.text.length)))
        dirty = true
    }

    fun onValueChange(new: TextFieldValue) {
        if (new.text == value.text) {
            if (new.selection != value.selection) typing = null
            value = new
            return
        }
        // Enter on an empty list item ends the list instead of adding one more.
        val at = value.selection.start
        val i = doc.paragraphAt(at)
        if (new.text == value.text.substring(0, at) + "\n" + value.text.substring(value.selection.end) && value.selection.collapsed &&
            doc.paras[i].kind.list && doc.paragraphText(i).isEmpty()
        ) {
            commit(doc.paragraphs(i, i) { it.copy(kind = Kind.Normal) }, value.selection)
            return
        }
        val styles = typing ?: doc.stylesAt(value.selection.min)
        val next = doc.edit(new.text, styles)
        commit(next, new.selection, coalesce = true)
        value = new.copy(text = next.text)
    }

    fun toggle(style: Style) {
        val s = selection
        if (s.collapsed) {
            val now = typing ?: doc.stylesAt(s.start)
            typing = if (style in now) now - style else now + style
            return
        }
        commit(doc.toggle(style, s.min, s.max), s)
    }

    fun styleOn(style: Style): Boolean = typing?.let { style in it } ?: doc.has(style, selection.min, selection.max)

    private fun selectedParagraphs() = doc.paragraphAt(selection.min) to doc.paragraphAt(selection.max)

    fun kind(kind: Kind) {
        val (a, b) = selectedParagraphs()
        commit(doc.paragraphs(a, b) { it.copy(kind = if (it.kind == kind && kind != Kind.Normal) Kind.Normal else kind) }, selection)
    }

    fun align(align: Align) {
        val (a, b) = selectedParagraphs()
        commit(doc.paragraphs(a, b) { it.copy(align = align) }, selection)
    }

    fun select(start: Int, end: Int) {
        value = value.copy(selection = TextRange(start.coerceIn(0, doc.text.length), end.coerceIn(0, doc.text.length)))
        typing = null
    }

    fun setKind(i: Int, kind: Kind) = commit(doc.paragraphs(i, i) { it.copy(kind = kind) }, selection)

    val currentPara: Doc.Para get() = doc.paras[doc.paragraphAt(selection.min)]

    /** Puts [fragment] in place of the selection (As//ka's Markdown, a paste). */
    fun insert(fragment: Doc, start: Int = selection.min, end: Int = selection.max) {
        val next = doc.insert(start, end, fragment)
        val caret = start + fragment.text.length
        commit(next, TextRange(caret))
    }

    fun undo() {
        val s = undo.removeLastOrNull() ?: return
        redo.addLast(Snapshot(doc, value.selection))
        doc = s.doc
        value = TextFieldValue(s.doc.text, s.selection)
        dirty = true
        lastPush = 0
    }

    fun redo() {
        val s = redo.removeLastOrNull() ?: return
        undo.addLast(Snapshot(doc, value.selection))
        doc = s.doc
        value = TextFieldValue(s.doc.text, s.selection)
        dirty = true
        lastPush = 0
    }

    /** Replaces [what] by [with] (all of them, or the next after the cursor); how many. */
    fun replace(what: String, with: String, all: Boolean): Int {
        if (what.isEmpty()) return 0
        var d = doc
        var count = 0
        var from = if (all) 0 else selection.min
        while (true) {
            val at = d.text.indexOf(what, from, ignoreCase = true).takeIf { it >= 0 } ?: break
            d = d.insert(at, at + what.length, Doc.of(with).copy(spans = d.spans.filter { it.start <= at && it.end >= at + what.length }
                .map { Doc.Span(0, with.length, it.style) }.filter { with.isNotEmpty() }))
            count++
            from = at + with.length
            if (!all) {
                commit(d, TextRange(at, at + with.length))
                return 1
            }
        }
        if (count > 0) commit(d, TextRange(minOf(selection.min, d.text.length)))
        return count
    }

    /** Selects the next [what] after the cursor, from the top again at the end. */
    fun findNext(what: String): Boolean {
        if (what.isEmpty()) return false
        val at = doc.text.indexOf(what, selection.max, ignoreCase = true).takeIf { it >= 0 }
            ?: doc.text.indexOf(what, 0, ignoreCase = true).takeIf { it >= 0 } ?: return false
        value = value.copy(selection = TextRange(at, at + what.length))
        return true
    }

    fun open(f: File) {
        runCatching { DocFiles.read(f) }.onSuccess { loaded ->
            doc = loaded.doc
            value = TextFieldValue(loaded.doc.text)
            file = f
            dirty = false
            undo.clear()
            redo.clear()
            message = if (loaded.lost.isEmpty()) "" else "Ouvert sans : " + loaded.lost.joinToString(", ") + ". Enregistrer par-dessus les perdrait."
        }.onFailure { message = "Impossible d'ouvrir ${f.name} : ${it.message}" }
    }

    fun new() {
        doc = Doc()
        value = TextFieldValue("")
        file = null
        dirty = false
        undo.clear()
        redo.clear()
        message = ""
    }

    /** Saves to [file], or asks where; true once saved. */
    fun save(asNew: Boolean = false): Boolean {
        val target = file?.takeIf { !asNew && it.extension.lowercase() in DocFiles.Extensions } ?: chooseSave() ?: return false
        return saveTo(target)
    }

    fun saveTo(target: File): Boolean = runCatching { DocFiles.write(doc, target) }.fold(
        onSuccess = {
            file = target
            dirty = false
            message = "Enregistré : ${target.path}"
            true
        },
        onFailure = {
            message = "Pas enregistré : ${it.message}"
            false
        },
    )

    private fun chooseSave(): File? {
        val dialog = FileDialog(null as Frame?, "Enregistrer (.docx pour Word, .odt pour OpenDocument, .txt, .md)", FileDialog.SAVE)
        dialog.directory = (file?.parentFile ?: File(System.getProperty("user.home"), "Documents").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).path
        dialog.file = (file?.nameWithoutExtension ?: "Document") + ".docx"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val f = File(dialog.directory, name)
        return if (f.extension.lowercase() in DocFiles.Extensions) f else File(f.path + ".docx")
    }

    fun chooseOpen() {
        if (!confirmDiscard("ouvrir un autre document")) return
        val dialog = FileDialog(null as Frame?, "Ouvrir un document", FileDialog.LOAD)
        file?.parentFile?.let { dialog.directory = it.path }
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in DocFiles.Extensions }
        dialog.isVisible = true
        val name = dialog.file ?: return
        open(File(dialog.directory, name))
    }

    /** True when there is nothing unsaved, or the user saved or chose to lose it. */
    fun confirmDiscard(action: String): Boolean {
        if (!dirty) return true
        val choice = JOptionPane.showOptionDialog(
            null, "Le document a des modifications non enregistrées. Les enregistrer avant de $action ?", "Texte",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, arrayOf("Enregistrer", "Ne pas enregistrer", "Annuler"), "Enregistrer",
        )
        return when (choice) {
            0 -> save()
            1 -> true
            else -> false
        }
    }

    fun shortcut(e: KeyEvent): Boolean {
        when (e.key) {
            Key.B, Key.G -> toggle(Style.Bold)
            Key.I -> toggle(Style.Italic)
            Key.U -> toggle(Style.Underline)
            Key.S -> save(asNew = e.isShiftPressed)
            Key.O -> chooseOpen()
            Key.N -> if (confirmDiscard("créer un nouveau document")) new()
            Key.Z -> if (e.isShiftPressed) redo() else undo()
            Key.Y -> redo()
            Key.F, Key.H -> find = find ?: selectedText
            Key.Zero, Key.NumPad0 -> kind(Kind.Normal)
            Key.One, Key.NumPad1 -> kind(Kind.Heading1)
            Key.Two, Key.NumPad2 -> kind(Kind.Heading2)
            Key.Three, Key.NumPad3 -> kind(Kind.Heading3)
            Key.L -> align(Align.Start)
            Key.E -> align(Align.Center)
            Key.R -> align(Align.End)
            Key.J -> align(Align.Justify)
            else -> return false
        }
        return true
    }
}

@Composable
private fun TextScreen(state: TextState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Cp.Background)) {
        Toolbar(state)
        state.find?.let { FindBar(state, it) }
        if (state.message.isNotEmpty()) T(state.message, Cp.Yellow, 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1A26)).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            val render = remember(state.doc) { Render(state.doc) }
            BasicTextField(
                state.value, state::onValueChange,
                textStyle = TextStyle(color = Color(0xFF15151C), fontSize = 15.sp, fontFamily = FontFamily.SansSerif),
                visualTransformation = render,
                cursorBrush = SolidColor(Color(0xFF15151C)),
                modifier = Modifier.padding(vertical = 24.dp).widthIn(max = 820.dp).fillMaxWidth().heightIn(min = 1000.dp)
                    .background(Color.White).padding(horizontal = 72.dp, vertical = 64.dp).focusRequester(focus),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            T(state.doc.stats(), Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
            T(state.currentPara.kind.label + " · " + state.currentPara.align.label, Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
            T((state.file?.path ?: "pas encore enregistré") + if (state.dirty) " · modifié" else "", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal, maxLines = 1)
        }
    }
}

@Composable
private fun Toolbar(state: TextState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("TEXTE", Cp.Cyan, 22.sp, TitleFont, FontWeight.Bold)
            Box(Modifier.width(8.dp))
            Button("NOUVEAU", Cp.Cyan) { if (state.confirmDiscard("créer un nouveau document")) state.new() }
            Button("OUVRIR…", Cp.Cyan) { state.chooseOpen() }
            Button("ENREGISTRER", Cp.Cyan, filled = state.dirty) { state.save() }
            Button("ENREGISTRER SOUS…", Cp.Cyan) { state.save(asNew = true) }
            Box(Modifier.width(8.dp))
            Button("↶ ANNULER", Cp.Dim) { state.undo() }
            Button("↷ RÉTABLIR", Cp.Dim) { state.redo() }
            Button("RECHERCHER", Cp.Dim) { state.find = if (state.find == null) state.selectedText else null }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val kind = state.currentPara.kind
            for ((k, label) in listOf(Kind.Normal to "NORMAL", Kind.Title to "TITRE", Kind.Heading1 to "T1", Kind.Heading2 to "T2", Kind.Heading3 to "T3", Kind.Quote to "« CITATION »")) {
                Button(label, Cp.Yellow, filled = kind == k) { state.kind(k) }
            }
            Box(Modifier.width(8.dp))
            Button("• LISTE", Cp.Yellow, filled = kind == Kind.Bullet) { state.kind(Kind.Bullet) }
            Button("1. LISTE", Cp.Yellow, filled = kind == Kind.Numbered) { state.kind(Kind.Numbered) }
            Box(Modifier.width(8.dp))
            for ((s, label) in listOf(Style.Bold to "G", Style.Italic to "I", Style.Underline to "S", Style.Strike to "B̶")) {
                Button(label, Cp.Red, filled = state.styleOn(s)) { state.toggle(s) }
            }
            Box(Modifier.width(8.dp))
            val align = state.currentPara.align
            for ((a, label) in listOf(Align.Start to "⇤", Align.Center to "↔", Align.End to "⇥", Align.Justify to "☰")) {
                Button(label, Cp.Cyan, filled = align == a) { state.align(a) }
            }
        }
    }
}

@Composable
private fun FindBar(state: TextState, query: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(query, "Rechercher…", Modifier.width(260.dp), onEnter = { if (!state.findNext(query)) state.message = "« $query » introuvable" }) { state.find = it }
        Button("SUIVANT", Cp.Cyan) { if (!state.findNext(query)) state.message = "« $query » introuvable" }
        Field(state.replace, "Remplacer par…", Modifier.width(260.dp)) { state.replace = it }
        Button("REMPLACER", Cp.Cyan) { state.message = if (state.replace(query, state.replace, all = false) == 0) "« $query » introuvable" else "" }
        Button("TOUT REMPLACER", Cp.Cyan) { state.message = "${state.replace(query, state.replace, all = true)} remplacement(s)" }
        Button("✕", Cp.Dim) { state.find = null }
    }
}

@Composable
private fun Field(value: String, hint: String, modifier: Modifier, onEnter: () -> Unit = {}, onChange: (String) -> Unit) {
    Box(modifier.hudPanel(Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (value.isEmpty()) T(hint, Cp.Dim, 14.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(Cp.Yellow),
            modifier = Modifier.fillMaxWidth().height(20.dp).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onEnter(); true } else false
            },
        )
    }
}
