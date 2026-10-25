package dev.netrunner.os.slides

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
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
import java.io.File
import javax.swing.JOptionPane

/** Netrunner Présentation: slides for PowerPoint and OpenDocument, and the slideshow. */
fun presentation(args: List<String>) {
    val arg = args.firstOrNull()?.let { if (it.startsWith("file://")) File(java.net.URI(it)) else File(it) }
    application {
        val state = remember { DeckState().apply { if (arg != null && arg.isFile) open(arg) else if (arg != null) file = arg } }
        DisposableEffect(state) {
            val stop = Bridge.serve(DeckBridge(state))
            onDispose { stop() }
        }
        Window(
            onCloseRequest = { if (state.confirmDiscard("fermer")) exitApplication() },
            title = (if (state.dirty) "● " else "") + (state.file?.name ?: "Sans titre") + " · Présentation",
            icon = remember { badge(Cp.Red) },
            state = rememberWindowState(width = 1350.dp, height = 880.dp),
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && state.shortcut(e) },
        ) {
            DeckScreen(state)
        }
        state.showing?.let { at ->
            Window(
                onCloseRequest = { state.showing = null },
                title = "Présentation",
                undecorated = true,
                state = rememberWindowState(placement = WindowPlacement.Fullscreen),
                onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && state.showKey(e) },
            ) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(
                    Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().clickable { state.step(1) },
                    contentAlignment = Alignment.Center,
                ) {
                    SlideView(state.deck.slides[at.coerceIn(0, state.deck.slides.size - 1)], state.deck.theme)
                }
            }
        }
    }
}

class DeckState {
    var deck by mutableStateOf(Deck())
        private set
    var current by mutableStateOf(0)
        private set
    var file by mutableStateOf<File?>(null)
    var dirty by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    /** The slide on screen in the slideshow, or null. */
    var showing by mutableStateOf<Int?>(null)

    private val undo = ArrayDeque<Deck>()
    private val redo = ArrayDeque<Deck>()
    private var lastPush = 0L
    private var lastKind = ""

    val slide: Slide get() = deck.slides[current.coerceIn(0, deck.slides.size - 1)]

    /** Every change goes here, so it can be undone; typing in the same field counts as one change. */
    private fun commit(next: Deck, kind: String = "") {
        val now = System.currentTimeMillis()
        if (kind.isEmpty() || kind != lastKind || now - lastPush > 1500) {
            undo.addLast(deck)
            if (undo.size > 200) undo.removeFirst()
        }
        lastPush = now
        lastKind = kind
        redo.clear()
        deck = next
        current = current.coerceIn(0, next.slides.size - 1)
        dirty = true
    }

    fun undo() {
        val d = undo.removeLastOrNull() ?: return
        redo.addLast(deck)
        deck = d
        current = current.coerceIn(0, d.slides.size - 1)
        lastKind = ""
        dirty = true
    }

    fun redo() {
        val d = redo.removeLastOrNull() ?: return
        undo.addLast(deck)
        deck = d
        current = current.coerceIn(0, d.slides.size - 1)
        lastKind = ""
        dirty = true
    }

    fun goTo(i: Int) { current = i.coerceIn(0, deck.slides.size - 1) }

    fun edit(i: Int = current, kind: String = "", change: (Slide) -> Slide) = commit(deck.with(i, change(deck.slides[i])), if (kind.isEmpty()) "" else "$kind$i")

    fun title(text: String) = edit(kind = "title") { it.copy(title = text.replace('\n', ' ')) }
    fun body(text: String) = edit(kind = "body") { it.copy(body = Slide.lines(text)) }
    fun notes(text: String) = edit(kind = "notes") { it.copy(notes = text) }
    fun layout(l: Layout) = edit { it.copy(layout = l) }
    fun theme(t: Theme) = commit(deck.copy(theme = t))

    fun add(after: Int = current, slides: List<Slide> = listOf(Slide(Layout.Content, "", listOf(Line(""))))) {
        commit(deck.insert(after + 1, slides))
        current = (after + 1).coerceIn(0, deck.slides.size - 1)
    }

    fun duplicate() = add(current, listOf(slide))

    fun replaceAll(slides: List<Slide>) {
        commit(deck.copy(slides = slides.ifEmpty { listOf(Slide()) }))
        current = 0
    }

    fun delete(i: Int = current) = commit(deck.remove(i))

    fun move(by: Int) {
        val to = (current + by).coerceIn(0, deck.slides.size - 1)
        if (to == current) return
        commit(deck.move(current, to))
        current = to
    }

    fun picture(p: Picture?) = edit { it.copy(picture = p) }

    fun choosePicture() {
        val dialog = FileDialog(null as Frame?, "Choisir une image", FileDialog.LOAD)
        File(System.getProperty("user.home"), "Images").takeIf { it.isDirectory }?.let { dialog.directory = it.path }
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in listOf("png", "jpg", "jpeg", "gif", "bmp") }
        dialog.isVisible = true
        val name = dialog.file ?: return
        val f = File(dialog.directory, name)
        runCatching { picture(Picture(f.readBytes(), f.extension.lowercase().let { if (it == "jpg") "jpeg" else it })) }
            .onFailure { message = "Image illisible : ${it.message}" }
    }

    fun show(from: Int = current) { showing = from.coerceIn(0, deck.slides.size - 1) }

    fun step(by: Int) {
        val at = showing ?: return
        val next = at + by
        showing = if (next >= deck.slides.size) null else next.coerceAtLeast(0)
        showing?.let { current = it }
    }

    fun showKey(e: KeyEvent): Boolean {
        when (e.key) {
            Key.DirectionRight, Key.DirectionDown, Key.Spacebar, Key.PageDown, Key.Enter, Key.N -> step(1)
            Key.DirectionLeft, Key.DirectionUp, Key.PageUp, Key.Backspace, Key.P -> step(-1)
            Key.Home -> showing = 0
            Key.MoveEnd -> showing = deck.slides.size - 1
            Key.Escape, Key.Q -> showing = null
            else -> return false
        }
        return true
    }

    fun shortcut(e: KeyEvent): Boolean {
        if (e.key == Key.F5) { show(if (e.isShiftPressed) current else 0); return true }
        if (!e.isCtrlPressed) return false
        when (e.key) {
            Key.S -> save(asNew = e.isShiftPressed)
            Key.O -> chooseOpen()
            Key.N -> if (confirmDiscard("créer une nouvelle présentation")) new()
            Key.M -> add()
            Key.D -> duplicate()
            Key.Z -> if (e.isShiftPressed) redo() else undo()
            Key.Y -> redo()
            Key.PageDown -> goTo(current + 1)
            Key.PageUp -> goTo(current - 1)
            else -> return false
        }
        return true
    }

    fun open(f: File) {
        runCatching { DeckFiles.read(f) }.onSuccess { loaded ->
            deck = loaded.deck
            current = 0
            file = f
            dirty = false
            undo.clear()
            redo.clear()
            message = if (loaded.lost.isEmpty()) "" else "Ouvert sans : " + loaded.lost.joinToString(", ") + ". Enregistrer par-dessus les perdrait."
        }.onFailure { message = "Impossible d'ouvrir ${f.name} : ${it.message}" }
    }

    fun new() {
        deck = Deck()
        current = 0
        file = null
        dirty = false
        undo.clear()
        redo.clear()
        message = ""
    }

    /** Saves to [file], or asks where; true once saved. */
    fun save(asNew: Boolean = false): Boolean {
        val target = file?.takeIf { !asNew && it.extension.lowercase() in DeckFiles.Writable } ?: chooseSave() ?: return false
        return saveTo(target)
    }

    fun saveTo(target: File): Boolean = runCatching { DeckFiles.write(deck, target) }.fold(
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
        val dialog = FileDialog(null as Frame?, "Enregistrer (.pptx pour PowerPoint, .odp pour OpenDocument)", FileDialog.SAVE)
        dialog.directory = (file?.parentFile ?: File(System.getProperty("user.home"), "Documents").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).path
        dialog.file = (file?.nameWithoutExtension ?: "Présentation") + ".pptx"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val f = File(dialog.directory, name)
        return DeckFiles.target(f)
    }

    fun chooseOpen() {
        if (!confirmDiscard("ouvrir une autre présentation")) return
        val dialog = FileDialog(null as Frame?, "Ouvrir une présentation", FileDialog.LOAD)
        file?.parentFile?.let { dialog.directory = it.path }
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in DeckFiles.Extensions }
        dialog.isVisible = true
        val name = dialog.file ?: return
        open(File(dialog.directory, name))
    }

    /** True when there is nothing unsaved, or the user saved or chose to lose it. */
    fun confirmDiscard(action: String): Boolean {
        if (!dirty) return true
        val choice = JOptionPane.showOptionDialog(
            null, "La présentation a des modifications non enregistrées. Les enregistrer avant de $action ?", "Présentation",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, arrayOf("Enregistrer", "Ne pas enregistrer", "Annuler"), "Enregistrer",
        )
        return when (choice) {
            0 -> save()
            1 -> true
            else -> false
        }
    }
}

private fun rgb(c: Int) = Color(0xFF000000.toInt() or c)

private val pictures = HashMap<Picture, ImageBitmap?>()

private fun bitmap(p: Picture): ImageBitmap? = pictures.getOrPut(p) {
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(p.bytes).toComposeImageBitmap() }.getOrNull()
}

/** Bullets in front of each line while editing, as they will show: "•" first level, "–" below. */
private class Bullets(private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val lines = text.text.split('\n')
        // Where each bullet goes in the original text.
        val points = IntArray(lines.size)
        var start = 0
        lines.forEachIndexed { i, l ->
            points[i] = start + (l.length - l.trimStart(' ').length)
            start += l.length + 1
        }
        val out = buildAnnotatedString {
            var at = 0
            lines.forEachIndexed { i, l ->
                val indent = l.length - l.trimStart(' ').length
                append(text.subSequence(at, at + indent))
                withStyle(SpanStyle(color = color)) { append(if (indent >= 2) "– " else "• ") }
                append(text.subSequence(at + indent, at + l.length))
                if (i < lines.size - 1) append('\n')
                at += l.length + 1
            }
        }
        return TransformedText(
            out,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = offset + 2 * points.count { it <= offset }
                override fun transformedToOriginal(offset: Int): Int {
                    var before = 0
                    for (i in points.indices) {
                        val shown = points[i] + 2 * i
                        if (offset < shown) break
                        if (offset < shown + 2) return points[i]
                        before = i + 1
                    }
                    return (offset - 2 * before).coerceIn(0, text.length)
                }
            },
        )
    }
}

/** A slide drawn at any size, 16:9; editable when [onTitle] and [onBody] are given. */
@Composable
fun SlideView(
    slide: Slide,
    theme: Theme,
    modifier: Modifier = Modifier,
    onTitle: ((String) -> Unit)? = null,
    onBody: ((String) -> Unit)? = null,
    editKey: Any? = null,
) {
    BoxWithConstraints(modifier.aspectRatio(16f / 9f).background(rgb(theme.background))) {
        val w = maxWidth
        val h = maxHeight
        val density = LocalDensity.current
        fun size(pt: Double) = with(density) { (pt * w.toPx() / 960).toFloat().toSp() }
        fun Modifier.place(b: DeckFiles.Geometry.Box) = this.offset(w * b.x.toFloat(), h * b.y.toFloat()).size(w * b.w.toFloat(), h * b.h.toFloat())

        val content = slide.layout == Layout.Content
        val titleStyle = TextStyle(color = rgb(theme.title), fontSize = size(DeckFiles.Geometry.titleSize(slide)), fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, textAlign = if (content) TextAlign.Start else TextAlign.Center)
        val tb = DeckFiles.Geometry.title(slide)
        Box(Modifier.place(tb), contentAlignment = if (content) Alignment.CenterStart else Alignment.Center) {
            if (onTitle != null) {
                BasicTextField(slide.title, onTitle, textStyle = titleStyle, cursorBrush = SolidColor(rgb(theme.accent)), modifier = Modifier.fillMaxWidth())
                if (slide.title.isEmpty()) BasicText("Cliquer pour ajouter un titre", style = titleStyle.copy(color = rgb(theme.title).copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth())
            } else BasicText(slide.title, style = titleStyle, modifier = Modifier.fillMaxWidth())
        }

        if (slide.layout != Layout.Section || slide.body.isNotEmpty() || onBody != null) {
            val bb = DeckFiles.Geometry.body(slide)
            val bodyStyle = if (content) TextStyle(color = rgb(theme.text), fontSize = size(DeckFiles.Geometry.bodySize(slide, 0)), fontFamily = FontFamily.SansSerif)
            else TextStyle(color = rgb(theme.accent), fontSize = size(DeckFiles.Geometry.bodySize(slide, 0)), fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
            Box(Modifier.place(bb), contentAlignment = if (content) Alignment.TopStart else Alignment.TopCenter) {
                if (onBody != null) {
                    val bullets = remember(theme) { Bullets(rgb(theme.accent)) }
                    // What is typed stays as typed (a first space, an empty last line); the slide gets its lines.
                    var typed by remember(editKey) { mutableStateOf(TextFieldValue(slide.bodyText)) }
                    if (Slide.lines(typed.text) != slide.body) typed = TextFieldValue(slide.bodyText, TextRange(slide.bodyText.length))
                    fun change(v: TextFieldValue) {
                        typed = v
                        onBody(v.text)
                    }
                    BasicTextField(
                        typed, ::change, textStyle = bodyStyle, cursorBrush = SolidColor(rgb(theme.accent)),
                        visualTransformation = if (content) bullets else VisualTransformation.None,
                        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                            // Tab and Shift+Tab move the line a level in or out.
                            if (e.type != KeyEventType.KeyDown || e.key != Key.Tab || !content) return@onPreviewKeyEvent false
                            val t = typed.text
                            val at = typed.selection.start
                            val lineStart = t.lastIndexOf('\n', at - 1) + 1
                            if (e.isShiftPressed) {
                                val n = t.substring(lineStart).takeWhile { it == ' ' }.length.coerceAtMost(2)
                                if (n > 0) change(TextFieldValue(t.removeRange(lineStart, lineStart + n), TextRange(maxOf(lineStart, at - n))))
                            } else change(TextFieldValue(t.substring(0, lineStart) + "  " + t.substring(lineStart), TextRange(at + 2)))
                            true
                        },
                    )
                } else if (content) {
                    Column(verticalArrangement = Arrangement.spacedBy(with(density) { (8 * w.toPx() / 960).toDp() })) {
                        for (l in slide.body) {
                            BasicText(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = rgb(theme.accent))) { append(if (l.level == 0) "• " else "– ") }
                                    append(l.text)
                                },
                                style = bodyStyle.copy(fontSize = size(DeckFiles.Geometry.bodySize(slide, l.level))),
                                modifier = Modifier.padding(start = w * (0.04f * l.level)),
                            )
                        }
                    }
                } else {
                    BasicText(slide.body.joinToString("\n") { it.text }, style = bodyStyle, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        slide.picture?.let { p ->
            bitmap(p)?.let { img ->
                Image(img, null, Modifier.place(DeckFiles.Geometry.picture(slide)), contentScale = ContentScale.Fit)
            }
        }
        // The accent bar of every theme, under the title of content slides.
        if (content) Box(Modifier.offset(w * 0.06f, h * 0.225f).size(w * 0.12f, h * 0.008f).background(rgb(theme.accent)))
    }
}

@Composable
private fun DeckScreen(state: DeckState) {
    Column(Modifier.fillMaxSize().background(Cp.Background)) {
        Toolbar(state)
        if (state.message.isNotEmpty()) T(state.message, Cp.Yellow, 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.width(220.dp).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.deck.slides) { i, s ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        T("${i + 1}", if (i == state.current) Cp.Red else Cp.Dim, 12.sp, MonoFont, FontWeight.Normal, modifier = Modifier.width(18.dp))
                        SlideView(
                            s, state.deck.theme,
                            Modifier.fillMaxWidth().border(if (i == state.current) 2.dp else 1.dp, if (i == state.current) Cp.Red else Cp.Dim.copy(alpha = 0.4f))
                                .clickable { state.goTo(i) },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SlideView(state.slide, state.deck.theme, Modifier.border(1.dp, Cp.Dim.copy(alpha = 0.5f)), onTitle = state::title, onBody = state::body, editKey = state.current)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    T("DISPOSITION", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
                    for (l in Layout.entries) Button(l.label.uppercase(), Cp.Red, filled = state.slide.layout == l) { state.layout(l) }
                    Box(Modifier.width(8.dp))
                    Button(if (state.slide.picture == null) "IMAGE…" else "CHANGER L'IMAGE…", Cp.Cyan) { state.choosePicture() }
                    if (state.slide.picture != null) Button("SANS IMAGE", Cp.Dim) { state.picture(null) }
                    Box(Modifier.width(8.dp))
                    T("THÈME", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
                    for (t in Theme.entries) Button(t.label.uppercase(), Cp.Yellow, filled = state.deck.theme == t) { state.theme(t) }
                }
                Box(Modifier.fillMaxWidth().height(110.dp).hudPanel(Cp.Dim.copy(alpha = 0.6f), cut = 6.dp).padding(10.dp)) {
                    if (state.slide.notes.isEmpty()) T("Notes pour l'orateur (seulement sur votre écran)…", Cp.Dim, 14.sp)
                    BasicTextField(
                        state.slide.notes, state::notes,
                        textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(Cp.Yellow),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            T("Diapo ${state.current + 1} sur ${state.deck.slides.size} · ${state.slide.layout.label}", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
            T("Retrait : deux espaces en début de ligne", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
            T((state.file?.path ?: "pas encore enregistré") + if (state.dirty) " · modifié" else "", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal, maxLines = 1)
        }
    }
}

@Composable
private fun Toolbar(state: DeckState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        T("PRÉSENTATION", Cp.Red, 22.sp, TitleFont, FontWeight.Bold)
        Box(Modifier.width(8.dp))
        Button("NOUVEAU", Cp.Red) { if (state.confirmDiscard("créer une nouvelle présentation")) state.new() }
        Button("OUVRIR…", Cp.Red) { state.chooseOpen() }
        Button("ENREGISTRER", Cp.Red, filled = state.dirty) { state.save() }
        Button("ENREGISTRER SOUS…", Cp.Red) { state.save(asNew = true) }
        Box(Modifier.width(8.dp))
        Button("↶", Cp.Dim) { state.undo() }
        Button("↷", Cp.Dim) { state.redo() }
        Box(Modifier.width(8.dp))
        Button("+ DIAPO", Cp.Cyan) { state.add() }
        Button("DUPLIQUER", Cp.Cyan) { state.duplicate() }
        Button("↑", Cp.Cyan) { state.move(-1) }
        Button("↓", Cp.Cyan) { state.move(1) }
        Button("SUPPRIMER", Cp.Dim) { state.delete() }
        Box(Modifier.width(8.dp))
        Button("▶ PRÉSENTER", Cp.Yellow, filled = true) { state.show(0) }
        Button("▶ D'ICI", Cp.Yellow) { state.show() }
    }
}
