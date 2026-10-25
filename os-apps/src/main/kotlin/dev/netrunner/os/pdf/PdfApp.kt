package dev.netrunner.os.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Netrunner PDF: reads a PDF page after page, with zoom and search. */
fun pdf(args: List<String>) {
    val arg = args.firstOrNull()?.let { if (it.startsWith("file://")) File(java.net.URI(it)) else File(it) }
    application {
        val scope = rememberCoroutineScope()
        val state = remember { PdfState(scope) }
        LaunchedEffect(Unit) { if (arg != null) state.open(arg) }
        DisposableEffect(state) {
            val stop = Bridge.serve(PdfBridge(state))
            onDispose {
                stop()
                state.doc?.close()
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = (state.doc?.title ?: "PDF") + " · PDF",
            icon = remember { badge(Cp.Yellow) },
            state = rememberWindowState(width = 1000.dp, height = 900.dp),
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && state.key(e.key, e.isCtrlPressed) },
        ) {
            PdfScreen(state)
        }
    }
}

class PdfState(private val scope: CoroutineScope) {
    var doc by mutableStateOf<Pdf?>(null)
        private set
    var error by mutableStateOf("")
    /** 1 = the page as wide as the window (up to 1100 px), 0.5 to 4. */
    var zoom by mutableStateOf(1f)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    var searching by mutableStateOf(false)
    val list = LazyListState()

    /** The page at the top of the view, from 1. */
    val page: Int get() = list.firstVisibleItemIndex + 1

    suspend fun open(file: File) {
        val result = withContext(Pdf.worker) { Pdf.open(file) }
        result.onSuccess {
            doc?.let { old -> withContext(Pdf.worker) { old.close() } }
            doc = it
            error = ""
            results = emptyList()
            query = ""
            list.scrollToItem(0)
        }.onFailure { error = it.message.orEmpty() }
    }

    fun goTo(page: Int) {
        val d = doc ?: return
        scope.launch { list.scrollToItem((page - 1).coerceIn(0, d.pages - 1)) }
    }

    fun zoomBy(f: Float) { zoom = (zoom * f).coerceIn(0.5f, 4f) }

    fun search() {
        val d = doc ?: return
        val q = query
        searching = true
        scope.launch {
            results = withContext(Pdf.worker) { d.search(q) }
            searching = false
            results.firstOrNull()?.let { goTo(it.first) }
        }
    }

    fun chooseFile() {
        val dialog = FileDialog(null as Frame?, "Ouvrir un PDF", FileDialog.LOAD)
        doc?.file?.parentFile?.let { dialog.directory = it.path }
        dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".pdf") }
        dialog.isVisible = true
        val name = dialog.file ?: return
        scope.launch { open(File(dialog.directory, name)) }
    }

    fun key(key: Key, ctrl: Boolean): Boolean {
        val d = doc ?: return false
        when {
            // Arrows, Home and End stay with the search field and the page list.
            key == Key.PageDown -> goTo(page + 1)
            key == Key.PageUp -> goTo(page - 1)
            ctrl && key == Key.MoveHome -> goTo(1)
            ctrl && key == Key.MoveEnd -> goTo(d.pages)
            ctrl && (key == Key.Plus || key == Key.Equals || key == Key.NumPadAdd) -> zoomBy(1.2f)
            ctrl && (key == Key.Minus || key == Key.NumPadSubtract) -> zoomBy(1 / 1.2f)
            ctrl && key == Key.Zero -> zoom = 1f
            else -> return false
        }
        return true
    }
}

@Composable
private fun PdfScreen(state: PdfState) {
    val doc = state.doc
    Column(Modifier.fillMaxSize().background(Cp.Background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("PDF", Cp.Yellow, 22.sp, TitleFont, FontWeight.Bold)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                T(doc?.title ?: "Aucun document", Cp.Text, 15.sp, maxLines = 1)
                if (doc != null) {
                    val page by remember(doc) { derivedStateOf { state.page } }
                    T("page $page / ${doc.pages}" + if (doc.author.isNotEmpty()) " · ${doc.author}" else "", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
                }
            }
            if (doc != null) {
                SearchBox(state)
                Button("◄") { state.goTo(state.page - 1) }
                Button("►") { state.goTo(state.page + 1) }
                Button("−", Cp.Cyan) { state.zoomBy(1 / 1.2f) }
                T("${(state.zoom * 100).toInt()} %", Cp.Cyan, 14.sp, MonoFont, FontWeight.Normal)
                Button("+", Cp.Cyan) { state.zoomBy(1.2f) }
            }
            Button("OUVRIR…", filled = doc == null) { state.chooseFile() }
        }
        if (state.error.isNotEmpty()) T(state.error, Cp.Red, 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (state.results.isNotEmpty() || state.searching) Results(state)
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1A1A26))) {
                if (doc == null) {
                    T("Ouvre un PDF pour le lire.", Cp.Dim, 16.sp, modifier = Modifier.align(Alignment.Center))
                } else {
                    val width = (minOf(maxWidth - 32.dp, 1100.dp) * state.zoom)
                    val px = with(LocalDensity.current) { width.roundToPx() }
                    LazyColumn(Modifier.fillMaxSize(), state.list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        items(doc.pages) { i -> Page(doc, i, px, Modifier.width(width).aspectRatio(doc.ratios[i])) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(doc: Pdf, index: Int, px: Int, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, doc, index, px) { value = withContext(Pdf.worker) { runCatching { doc.render(index, px) }.getOrNull() } }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val img = image
        if (img == null) T("${index + 1}", Color(0xFF9A9AA8), 20.sp, MonoFont, FontWeight.Normal)
        else Image(img, "page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun SearchBox(state: PdfState) {
    Box(Modifier.width(220.dp).hudPanel(Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (state.query.isEmpty()) T("Rechercher…", Cp.Dim, 14.sp)
        BasicTextField(
            state.query, { state.query = it }, singleLine = true,
            textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(Cp.Yellow),
            modifier = Modifier.fillMaxWidth().onEnter { state.search() },
        )
    }
}

private fun Modifier.onEnter(action: () -> Unit) = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
        action()
        true
    } else {
        false
    }
}

@Composable
private fun Results(state: PdfState) {
    LazyColumn(Modifier.width(280.dp).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            T(if (state.searching) "Recherche…" else "${state.results.size} page(s) pour « ${state.query} »", Cp.Yellow, 13.sp)
        }
        items(state.results) { (page, snippet) ->
            Column(Modifier.fillMaxWidth().hudPanel(Cp.Yellow.copy(alpha = 0.4f), cut = 6.dp).clickable { state.goTo(page) }.padding(8.dp)) {
                T("Page $page", Cp.Yellow, 13.sp)
                T(snippet, Cp.Text, 12.sp, MonoFont, FontWeight.Normal, maxLines = 3)
            }
        }
        item { Button("FERMER", Cp.Dim) { state.results = emptyList(); state.query = "" } }
    }
}
