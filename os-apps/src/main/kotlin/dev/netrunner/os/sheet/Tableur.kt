package dev.netrunner.os.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JOptionPane

/** Netrunner Tableur: the spreadsheet, for Excel and OpenDocument files. */
fun tableur(args: List<String>) {
    val arg = args.firstOrNull()?.let { if (it.startsWith("file://")) File(java.net.URI(it)) else File(it) }
    application {
        val state = remember { SheetState().apply { if (arg != null && arg.isFile) open(arg) else if (arg != null) file = arg } }
        DisposableEffect(state) {
            val stop = Bridge.serve(SheetBridge(state))
            onDispose { stop() }
        }
        Window(
            onCloseRequest = { if (state.confirmDiscard("fermer")) exitApplication() },
            title = (if (state.dirty) "● " else "") + (state.file?.name ?: "Sans titre") + " · Tableur",
            icon = remember { badge(Cp.Yellow) },
            state = rememberWindowState(width = 1300.dp, height = 860.dp),
        ) {
            SheetScreen(state)
        }
    }
}

class SheetState {
    var book by mutableStateOf(Book())
        private set
    var current by mutableStateOf(0)
        private set
    var cursor by mutableStateOf(Ref(0, 0))
        private set
    /** The other corner of the selection (the cursor is one). */
    var anchor by mutableStateOf(Ref(0, 0))
        private set
    /** What is being typed in the cursor's cell, or null when not editing. */
    var editing by mutableStateOf<TextFieldValue?>(null)
    /** Typing in the cell itself, or in the formula bar. */
    var inCell by mutableStateOf(true)
    var file by mutableStateOf<File?>(null)
    var dirty by mutableStateOf(false)
        private set
    var message by mutableStateOf("")

    private val undo = ArrayDeque<Book>()
    private val redo = ArrayDeque<Book>()

    val sheet: Sheet get() = book.sheets[current]
    val area: Area get() = Area.of(cursor, anchor)

    private var calcOf: Sheet? = null
    private var calcCache: Calc? = null

    /** The values of the current sheet, computed again only when it changed. */
    val calc: Calc
        get() {
            val s = sheet
            if (calcOf !== s) { calcOf = s; calcCache = Calc(s) }
            return calcCache!!
        }

    /** Every change of the workbook goes here, so it can be undone. */
    private fun commit(next: Sheet) {
        undo.addLast(book)
        if (undo.size > 200) undo.removeFirst()
        redo.clear()
        book = book.with(current, next)
        dirty = true
    }

    fun undo() {
        val b = undo.removeLastOrNull() ?: return
        redo.addLast(book)
        book = b
        current = current.coerceAtMost(b.sheets.size - 1)
        dirty = true
    }

    fun redo() {
        val b = redo.removeLastOrNull() ?: return
        undo.addLast(book)
        book = b
        current = current.coerceAtMost(b.sheets.size - 1)
        dirty = true
    }

    fun select(r: Ref, extend: Boolean = false) {
        commitEdit()
        cursor = Ref(r.col.coerceAtLeast(0), r.row.coerceAtLeast(0))
        if (!extend) anchor = cursor
    }

    fun selectArea(a: Area) {
        commitEdit()
        anchor = Ref(a.left, a.top)
        cursor = Ref(a.right, a.bottom)
    }

    fun move(dc: Int, dr: Int, extend: Boolean = false) = select(Ref(cursor.col + dc, cursor.row + dr), extend)

    fun startEdit(with: String? = null) {
        val text = with ?: sheet.input(cursor)
        editing = TextFieldValue(text, TextRange(text.length))
        inCell = true
    }

    /** Writes what is being typed into the cell; true when something was being edited. */
    fun commitEdit(): Boolean {
        val e = editing ?: return false
        editing = null
        if (e.text != sheet.input(cursor)) commit(sheet.set(cursor, e.text.trimEnd('\n')))
        return true
    }

    fun cancelEdit() { editing = null }

    fun set(r: Ref, input: String) = commit(sheet.set(r, input))

    fun paste(at: Ref, rows: List<List<String>>) = commit(sheet.paste(at, rows))

    fun clear(a: Area = area) = commit(sheet.clear(a))

    fun bold(a: Area = area, on: Boolean = !isBold()) {
        val cells = (a.top..a.bottom).flatMap { r -> (a.left..a.right).map { Ref(it, r) } }
        commit(sheet.copy(bold = if (on) sheet.bold + cells else sheet.bold - cells.toSet()))
    }

    fun isBold(): Boolean = cursor in sheet.bold

    fun sort(a: Area = area, by: Int = cursor.col, descending: Boolean = false) = commit(sheet.sort(a, by, descending))

    /** Σ: the sum of the numbers above the cursor (or of the selection, written below it). */
    fun autoSum() {
        val a = area
        if (a.size > 1) {
            val target = Ref(a.left, a.bottom + 1)
            commit(sheet.set(target, "=SOMME($a)"))
            select(target)
            return
        }
        var top = cursor.row - 1
        while (top >= 0 && calc.value(Ref(cursor.col, top)) is Value.Num) top--
        if (top == cursor.row - 1) { startEdit("=SOMME()"); editing = editing?.copy(selection = TextRange(7)); return }
        commit(sheet.set(cursor, "=SOMME(${Area(cursor.col, top + 1, cursor.col, cursor.row - 1)})"))
    }

    fun widen(col: Int, by: Int) = commit(sheet.copy(widths = sheet.widths + (col to (sheet.width(col) + by).coerceIn(40, 600))))

    fun goTo(i: Int) {
        commitEdit()
        current = i.coerceIn(0, book.sheets.size - 1)
        cursor = Ref(0, 0)
        anchor = cursor
    }

    fun addSheet(name: String = "Feuille ${book.sheets.size + 1}"): Int {
        undo.addLast(book)
        redo.clear()
        book = book.copy(sheets = book.sheets + Sheet(name))
        dirty = true
        goTo(book.sheets.size - 1)
        return current
    }

    fun renameSheet(name: String) {
        if (name.isBlank()) return
        commit(sheet.copy(name = name.trim()))
    }

    fun copy(cut: Boolean = false) {
        val a = area
        val text = (a.top..a.bottom).joinToString("\n") { r -> (a.left..a.right).joinToString("\t") { c -> sheet.input(Ref(c, r)) } }
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        if (cut) clear(a)
    }

    fun pasteClipboard() {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull() ?: return
        paste(Ref(area.left, area.top), rows(text))
    }

    fun open(f: File) {
        runCatching { SheetFiles.read(f) }.onSuccess { loaded ->
            book = loaded.book
            current = 0
            cursor = Ref(0, 0)
            anchor = cursor
            editing = null
            file = f
            dirty = false
            undo.clear()
            redo.clear()
            message = if (loaded.lost.isEmpty()) "" else "Ouvert sans : " + loaded.lost.joinToString(", ") + ". Enregistrer par-dessus les perdrait."
        }.onFailure { message = "Impossible d'ouvrir ${f.name} : ${it.message}" }
    }

    fun new() {
        book = Book()
        current = 0
        cursor = Ref(0, 0)
        anchor = cursor
        editing = null
        file = null
        dirty = false
        undo.clear()
        redo.clear()
        message = ""
    }

    /** Saves to [file], or asks where; true once saved. */
    fun save(asNew: Boolean = false): Boolean {
        commitEdit()
        val target = file?.takeIf { !asNew && it.extension.lowercase() in SheetFiles.Writable } ?: chooseSave() ?: return false
        return saveTo(target)
    }

    fun saveTo(target: File): Boolean = runCatching { SheetFiles.write(book, target) }.fold(
        onSuccess = {
            file = target
            dirty = false
            message = if (target.extension.lowercase() == "csv" && book.sheets.size > 1) "Enregistré : ${target.path} (le CSV ne garde que la première feuille)" else "Enregistré : ${target.path}"
            true
        },
        onFailure = {
            message = "Pas enregistré : ${it.message}"
            false
        },
    )

    private fun chooseSave(): File? {
        val dialog = FileDialog(null as Frame?, "Enregistrer (.xlsx pour Excel, .ods pour OpenDocument, .csv)", FileDialog.SAVE)
        dialog.directory = (file?.parentFile ?: File(System.getProperty("user.home"), "Documents").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).path
        dialog.file = (file?.nameWithoutExtension ?: "Classeur") + ".xlsx"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val f = File(dialog.directory, name)
        return if (f.extension.lowercase() in SheetFiles.Writable) f else File(f.path + ".xlsx")
    }

    fun chooseOpen() {
        if (!confirmDiscard("ouvrir un autre classeur")) return
        val dialog = FileDialog(null as Frame?, "Ouvrir un classeur", FileDialog.LOAD)
        file?.parentFile?.let { dialog.directory = it.path }
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in SheetFiles.Extensions }
        dialog.isVisible = true
        val name = dialog.file ?: return
        open(File(dialog.directory, name))
    }

    /** True when there is nothing unsaved, or the user saved or chose to lose it. */
    fun confirmDiscard(action: String): Boolean {
        commitEdit()
        if (!dirty) return true
        val choice = JOptionPane.showOptionDialog(
            null, "Le classeur a des modifications non enregistrées. Les enregistrer avant de $action ?", "Tableur",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, arrayOf("Enregistrer", "Ne pas enregistrer", "Annuler"), "Enregistrer",
        )
        return when (choice) {
            0 -> save()
            1 -> true
            else -> false
        }
    }

    /** Keys on the grid (not while typing in a cell). */
    fun key(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val shift = e.isShiftPressed
        if (e.isCtrlPressed) {
            when (e.key) {
                Key.S -> save(asNew = shift)
                Key.O -> chooseOpen()
                Key.N -> if (confirmDiscard("créer un nouveau classeur")) new()
                Key.Z -> if (shift) redo() else undo()
                Key.Y -> redo()
                Key.B, Key.G -> bold()
                Key.C -> copy()
                Key.X -> copy(cut = true)
                Key.V -> pasteClipboard()
                Key.A -> { anchor = Ref(0, 0); cursor = Ref(maxOf(0, sheet.cols - 1), maxOf(0, sheet.rows - 1)) }
                Key.Home -> select(Ref(0, 0))
                Key.DirectionUp -> select(Ref(cursor.col, 0), shift)
                Key.DirectionDown -> select(Ref(cursor.col, maxOf(0, sheet.rows - 1)), shift)
                Key.DirectionLeft -> select(Ref(0, cursor.row), shift)
                Key.DirectionRight -> select(Ref(maxOf(0, sheet.cols - 1), cursor.row), shift)
                Key.PageDown -> goTo(current + 1)
                Key.PageUp -> goTo(current - 1)
                else -> return false
            }
            return true
        }
        when (e.key) {
            Key.DirectionUp -> move(0, -1, shift)
            Key.DirectionDown -> move(0, 1, shift)
            Key.DirectionLeft -> move(-1, 0, shift)
            Key.DirectionRight -> move(1, 0, shift)
            Key.Enter, Key.NumPadEnter -> move(0, if (shift) -1 else 1)
            Key.Tab -> move(if (shift) -1 else 1, 0)
            Key.PageDown -> move(0, 25, shift)
            Key.PageUp -> move(0, -25, shift)
            Key.Home -> select(Ref(0, cursor.row), shift)
            Key.F2 -> startEdit()
            Key.Delete, Key.Backspace -> clear()
            else -> {
                val ch = e.utf16CodePoint
                if (ch < 32 || ch == 127 || e.isAltPressed) return false
                startEdit(String(Character.toChars(ch)))
            }
        }
        return true
    }

    companion object {
        /** A pasted or written table: lines, cells separated by tabs (or " | " when there are none). */
        fun rows(text: String): List<List<String>> {
            val lines = text.trimEnd('\n', '\r').split(Regex("\r?\n"))
            val sep = if (text.contains('\t')) "\t" else "|"
            return lines.map { line -> if (sep == "|" && !line.contains('|')) listOf(line.trim()) else line.split(sep).map { if (sep == "|") it.trim() else it } }
        }
    }
}

private val RowHeight = 26.dp
private val HeaderWidth = 52.dp
private val Grid = Color(0xFF2A2A3A)

@Composable
private fun SheetScreen(state: SheetState) {
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { gridFocus.requestFocus() } }
    LaunchedEffect(state.editing == null) { if (state.editing == null) runCatching { gridFocus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Cp.Background)) {
        Toolbar(state)
        FormulaBar(state)
        if (state.message.isNotEmpty()) T(state.message, Cp.Yellow, 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val calc = state.calc
        val sheet = state.sheet
        val cols = maxOf(26, sheet.cols + 3)
        val rows = maxOf(200, sheet.rows + 50, state.cursor.row + 30)
        val widths = (0 until maxOf(cols, state.cursor.col + 4)).map { sheet.width(it) }
        val list = rememberLazyListState()
        LaunchedEffect(state.cursor.row) {
            val visible = list.layoutInfo.visibleItemsInfo
            if (visible.isNotEmpty() && (state.cursor.row < visible.first().index || state.cursor.row > visible.last().index - 2)) {
                list.scrollToItem(maxOf(0, state.cursor.row - visible.size / 2))
            }
        }
        val hscroll = rememberScrollState()
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).focusRequester(gridFocus).focusable()
                .onPreviewKeyEvent { e -> state.editing == null && state.key(e) },
        ) {
            Column(Modifier.horizontalScroll(hscroll).width(HeaderWidth + widths.sum().dp)) {
                Row {
                    Header("", HeaderWidth, false)
                    widths.forEachIndexed { c, w ->
                        Header(Ref.colName(c), w.dp, c in state.area.left..state.area.right) { state.selectArea(Area(c, 0, c, maxOf(0, sheet.rows - 1))) }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), list) {
                    items(rows) { r ->
                        Row {
                            Header((r + 1).toString(), HeaderWidth, r in state.area.top..state.area.bottom) { state.selectArea(Area(0, r, maxOf(0, sheet.cols - 1), r)) }
                            widths.forEachIndexed { c, w -> Cell(state, calc, Ref(c, r), w) }
                        }
                    }
                }
            }
        }
        Tabs(state)
    }
}

@Composable
private fun Header(label: String, width: Dp, on: Boolean, onClick: () -> Unit = {}) {
    Box(
        Modifier.width(width).height(RowHeight).background(if (on) Cp.Yellow.copy(alpha = 0.18f) else Cp.Panel).border(0.5.dp, Grid).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        T(label, if (on) Cp.Yellow else Cp.Dim, 12.sp, MonoFont, FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun Cell(state: SheetState, calc: Calc, ref: Ref, width: Int) {
    val isCursor = ref == state.cursor
    val inArea = ref in state.area
    val editing = state.editing
    Box(
        Modifier.width(width.dp).height(RowHeight)
            .background(if (inArea && !isCursor) Cp.Cyan.copy(alpha = 0.10f) else Color(0xFF0D0D16))
            .border(if (isCursor) 2.dp else 0.5.dp, if (isCursor) Cp.Cyan else Grid)
            .clickable { if (isCursor && editing == null) state.startEdit() else state.select(ref, extend = shiftDown) }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (isCursor && editing != null && !state.inCell) {
            T(editing.text, Cp.Text, 14.sp, FontFamily.SansSerif, FontWeight.Normal, maxLines = 1)
        } else if (isCursor && editing != null) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            BasicTextField(
                editing, { state.editing = it }, singleLine = true,
                textStyle = TextStyle(color = Cp.Text, fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
                cursorBrush = SolidColor(Cp.Yellow),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e -> editKey(state, e) },
            )
        } else if (state.sheet.cells.containsKey(ref)) {
            val v = calc.value(ref)
            T(
                v.text(),
                when (v) { is Value.Error -> Cp.Red; is Value.Num -> Color(0xFFBFF6FF); else -> Cp.Text },
                14.sp, FontFamily.SansSerif, if (ref in state.sheet.bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                modifier = Modifier.align(if (v is Value.Num || v is Value.Bool) Alignment.CenterEnd else Alignment.CenterStart),
            )
        }
    }
}

/** Keys while typing in a cell or the formula bar: Enter and Tab write it and move on, Escape gives up. */
private fun editKey(state: SheetState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    when (e.key) {
        Key.Enter, Key.NumPadEnter -> { state.commitEdit(); state.move(0, if (e.isShiftPressed) -1 else 1) }
        Key.Tab -> { state.commitEdit(); state.move(if (e.isShiftPressed) -1 else 1, 0) }
        Key.Escape -> state.cancelEdit()
        else -> return false
    }
    return true
}

/** Shift held while clicking extends the selection; tracked from AWT, which knows. */
private val shiftDown: Boolean
    get() = ShiftWatch.down

private object ShiftWatch {
    @Volatile var down = false

    init {
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            if (e is java.awt.event.InputEvent) down = e.isShiftDown
        }, java.awt.AWTEvent.KEY_EVENT_MASK or java.awt.AWTEvent.MOUSE_EVENT_MASK)
    }
}

@Composable
private fun Toolbar(state: SheetState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        T("TABLEUR", Cp.Yellow, 22.sp, TitleFont, FontWeight.Bold)
        Box(Modifier.width(8.dp))
        Button("NOUVEAU", Cp.Yellow) { if (state.confirmDiscard("créer un nouveau classeur")) state.new() }
        Button("OUVRIR…", Cp.Yellow) { state.chooseOpen() }
        Button("ENREGISTRER", Cp.Yellow, filled = state.dirty) { state.save() }
        Button("ENREGISTRER SOUS…", Cp.Yellow) { state.save(asNew = true) }
        Box(Modifier.width(8.dp))
        Button("↶", Cp.Dim) { state.undo() }
        Button("↷", Cp.Dim) { state.redo() }
        Box(Modifier.width(8.dp))
        Button("G", Cp.Red, filled = state.isBold()) { state.bold() }
        Button("Σ SOMME", Cp.Cyan) { state.autoSum() }
        Button("TRIER ↑", Cp.Cyan) { state.sort() }
        Button("TRIER ↓", Cp.Cyan) { state.sort(descending = true) }
        Button("COLONNE −", Cp.Dim) { state.widen(state.cursor.col, -20) }
        Button("COLONNE +", Cp.Dim) { state.widen(state.cursor.col, 20) }
    }
}

@Composable
private fun FormulaBar(state: SheetState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T(state.area.toString(), Cp.Cyan, 15.sp, MonoFont, FontWeight.Normal, modifier = Modifier.width(110.dp).hudPanel(Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 7.dp))
        T("ƒx", Cp.Dim, 16.sp)
        val shown = state.editing ?: TextFieldValue(state.sheet.input(state.cursor))
        Box(Modifier.weight(1f).hudPanel(Cp.Yellow.copy(alpha = 0.4f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
            BasicTextField(
                shown, { if (state.editing == null) state.inCell = false; state.editing = it }, singleLine = true,
                textStyle = TextStyle(color = Cp.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                cursorBrush = SolidColor(Cp.Yellow),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e -> state.editing != null && editKey(state, e) },
            )
        }
        val v = state.calc.value(state.cursor)
        if (Input.isFormula(state.sheet.input(state.cursor))) T("= " + v.text(), if (v is Value.Error) Cp.Red else Cp.Dim, 14.sp, MonoFont, FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun Tabs(state: SheetState) {
    var renaming by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.book.sheets.forEachIndexed { i, s ->
            Button(s.name.uppercase(), Cp.Yellow, filled = i == state.current) { if (i == state.current) renaming = s.name else state.goTo(i) }
        }
        Button("+", Cp.Dim) { state.addSheet() }
        renaming?.let { name ->
            Box(Modifier.width(220.dp).hudPanel(Cp.Yellow.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                BasicTextField(
                    name, { renaming = it }, singleLine = true,
                    textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Cp.Yellow),
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { state.renameSheet(name); renaming = null; true }
                        else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { renaming = null; true } else false
                    },
                )
            }
        }
        Box(Modifier.weight(1f))
        val a = state.area
        if (a.size > 1) {
            val nums = (a.top..a.bottom).flatMap { r -> (a.left..a.right).map { Ref(it, r) } }.map { state.calc.value(it) }.filterIsInstance<Value.Num>().map { it.v }
            if (nums.isNotEmpty()) T("Somme ${Value.format(nums.sum())} · Moyenne ${Value.format(nums.average())} · Nb ${nums.size}", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
        }
        T((state.file?.path ?: "pas encore enregistré") + if (state.dirty) " · modifié" else "", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal, maxLines = 1)
    }
}
