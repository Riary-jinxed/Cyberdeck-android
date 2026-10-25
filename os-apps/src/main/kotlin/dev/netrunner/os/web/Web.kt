package dev.netrunner.os.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
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
import org.cef.browser.CefBrowser
import java.awt.CardLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JPanel
import kotlin.system.exitProcess

/** Netrunner Web: the browser, tabs, favourites and history around the Chromium engine. */
fun web(args: List<String>) {
    // At build time: the engine goes into the OS package.
    if (args.firstOrNull() == "--installer") {
        Engine.install(File(args.getOrNull(1) ?: error("web --installer <dossier>")))
        exitProcess(0)
    }
    val first = args.firstOrNull()?.let { a ->
        val f = if (a.startsWith("file://")) null else File(a)
        if (f != null && f.exists()) f.toURI().toString() else Address.resolve(a)
    } ?: Address.Home
    val state = WebState()
    // The engine starts before the window, as Chromium wants on Linux.
    state.engine = Engine.start(state) { System.err.println("Netrunner Web : moteur $it") }
    application {
        remember { state.open(first) }
        DisposableEffect(state) {
            val stop = Bridge.serve(WebBridge(state))
            onDispose { stop() }
        }
        val window = rememberWindowState(width = 1380.dp, height = 900.dp)
        state.window = window
        Window(
            onCloseRequest = {
                state.engine.close()
                exitApplication()
            },
            title = state.tabs.getOrNull(state.current)?.title?.ifBlank { null }?.let { "$it · Web" } ?: "Web",
            icon = remember { badge(Cp.Cyan) },
            state = window,
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && state.composeKey(e) },
        ) {
            WebScreen(state)
        }
    }
    exitProcess(0)
}

class Tab(val id: Int, val browser: CefBrowser) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var loading by mutableStateOf(true)
    var back by mutableStateOf(false)
    var forward by mutableStateOf(false)
}

class Download(val id: Int, val name: String, val percent: Int, val done: Boolean, val failed: Boolean, val path: String)

enum class Panel(val label: String) { Bookmarks("FAVORIS"), History("HISTORIQUE"), Downloads("TÉLÉCHARGEMENTS") }

class WebState : Engine.Events {
    lateinit var engine: Engine
    var window: WindowState? = null
    val tabs = mutableStateListOf<Tab>()
    var current by mutableStateOf(0)
        private set
    /** The address bar while it is being typed in, else null (it shows the page's address). */
    var typed by mutableStateOf<TextFieldValue?>(null)
    var panel by mutableStateOf<Panel?>(null)
    var find by mutableStateOf<String?>(null)
    var message by mutableStateOf("")
    val downloads = mutableStateListOf<Download>()
    val places = Places(File(System.getProperty("user.home"), ".config/netrunner/web"))
    /** Bumped when favourites or history change, so the window redraws them. */
    var placesVersion by mutableStateOf(0)
    val addressFocus = FocusRequester()
    val cards = JPanel(CardLayout())
    private var nextId = 1

    val tab: Tab? get() = tabs.getOrNull(current)

    fun open(url: String = Address.Home): Tab {
        val t = Tab(nextId++, engine.browser(url)).apply { this.url = url }
        tabs += t
        cards.add(t.browser.uiComponent, t.id.toString())
        select(tabs.size - 1)
        return t
    }

    fun select(i: Int) {
        if (tabs.isEmpty()) return
        current = i.coerceIn(0, tabs.size - 1)
        (cards.layout as CardLayout).show(cards, tabs[current].id.toString())
        cards.revalidate()
        typed = null
        find = null
    }

    fun close(i: Int = current) {
        val t = tabs.getOrNull(i) ?: return
        cards.remove(t.browser.uiComponent)
        t.browser.close(true)
        tabs.removeAt(i)
        if (tabs.isEmpty()) open() else select(if (current >= tabs.size) tabs.size - 1 else current)
    }

    /** What the address bar was given: an address, or words to search. */
    fun go(input: String) {
        val url = Address.resolve(input)
        val t = tab ?: open(url)
        t.browser.loadURL(url)
        t.url = url
        typed = null
        panel = null
        t.browser.setFocus(true)
    }

    fun bookmark() {
        val t = tab ?: return
        val on = places.toggleBookmark(t.url, t.title)
        placesVersion++
        message = if (on) "Ajouté aux favoris." else "Retiré des favoris."
    }

    fun zoom(by: Double) {
        val b = tab?.browser ?: return
        b.zoomLevel = if (by == 0.0) 0.0 else (b.zoomLevel + by).coerceIn(-4.0, 6.0)
    }

    fun focusAddress() {
        panel = null
        typed = TextFieldValue(tab?.url.orEmpty(), TextRange(0, tab?.url.orEmpty().length))
        runCatching { addressFocus.requestFocus() }
    }

    fun findNext(text: String, forward: Boolean = true) {
        val b = tab?.browser ?: return
        if (text.isEmpty()) b.stopFinding(true) else b.find(text, forward, false, true)
    }

    fun fullscreen() {
        val w = window ?: return
        w.placement = if (w.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }

    private fun tabOf(b: CefBrowser) = tabs.firstOrNull { it.browser === b || it.browser.identifier == b.identifier }

    override fun address(browser: CefBrowser, url: String) {
        val t = tabOf(browser) ?: return
        t.url = url
        places.visit(url, t.title)
        placesVersion++
    }

    override fun title(browser: CefBrowser, title: String) {
        val t = tabOf(browser) ?: return
        t.title = title
        places.retitle(t.url, title)
    }

    override fun loading(browser: CefBrowser, loading: Boolean, back: Boolean, forward: Boolean) {
        val t = tabOf(browser) ?: return
        t.loading = loading
        t.back = back
        t.forward = forward
    }

    override fun popup(url: String) { open(url) }

    override fun download(id: Int, name: String, percent: Int, done: Boolean, failed: Boolean, path: String) {
        val d = Download(id, name, percent, done, failed, path)
        val i = downloads.indexOfFirst { it.id == id }
        if (i < 0) {
            downloads.add(0, d)
            message = "Téléchargement de $name…"
        } else {
            val was = downloads[i]
            downloads[i] = d
            if (done && !was.done) message = "Téléchargé : $name (dans ${File(path).parentFile?.name ?: "Téléchargements"})"
            if (failed && !was.failed) message = "Téléchargement annulé : $name"
        }
    }

    /** Keys from a page (Windows key codes, as Chromium gives them). */
    override fun key(ctrl: Boolean, shift: Boolean, alt: Boolean, code: Int): Boolean {
        when {
            ctrl && code == 'T'.code -> open()
            ctrl && code == 'W'.code -> close()
            ctrl && code == 'L'.code -> focusAddress()
            ctrl && code == 'R'.code || code == 116 -> tab?.browser?.reload()
            ctrl && code == 'F'.code -> find = find ?: ""
            ctrl && code == 'D'.code -> bookmark()
            ctrl && code == 'H'.code -> panel = Panel.History
            ctrl && code == 'J'.code -> panel = Panel.Downloads
            ctrl && code == 9 -> select((current + if (shift) tabs.size - 1 else 1) % tabs.size)
            ctrl && (code == 187 || code == 107) -> zoom(0.5)
            ctrl && (code == 189 || code == 109) -> zoom(-0.5)
            ctrl && (code == 48 || code == 96) -> zoom(0.0)
            alt && code == 37 -> tab?.browser?.goBack()
            alt && code == 39 -> tab?.browser?.goForward()
            code == 122 -> fullscreen()
            code == 27 && find != null -> { find = null; findNext("") }
            else -> return false
        }
        return true
    }

    /** The same keys when the window's own controls have the focus. */
    fun composeKey(e: KeyEvent): Boolean {
        val code = when (e.key) {
            Key.T -> 'T'.code; Key.W -> 'W'.code; Key.L -> 'L'.code; Key.R -> 'R'.code; Key.F -> 'F'.code; Key.D -> 'D'.code
            Key.H -> 'H'.code; Key.J -> 'J'.code; Key.Tab -> 9; Key.Equals, Key.Plus, Key.NumPadAdd -> 187; Key.Minus, Key.NumPadSubtract -> 189
            Key.Zero, Key.NumPad0 -> 48; Key.DirectionLeft -> 37; Key.DirectionRight -> 39; Key.F5 -> 116; Key.F11 -> 122; Key.Escape -> 27
            else -> return false
        }
        // Arrows and Escape belong to the text fields unless Alt is held.
        if ((code == 37 || code == 39) && !e.isAltPressed) return false
        if (!e.isCtrlPressed && !e.isAltPressed && code !in listOf(116, 122, 27)) return false
        if (code == 27 && typed != null) { typed = null; return true }
        return key(e.isCtrlPressed, e.isShiftPressed, e.isAltPressed, code)
    }
}

@Composable
private fun WebScreen(state: WebState) {
    Column(Modifier.fillMaxSize().background(Cp.Background)) {
        Tabs(state)
        Toolbar(state)
        state.find?.let { FindBar(state, it) }
        val downloading = state.downloads.firstOrNull { !it.done && !it.failed }
        if (state.message.isNotEmpty() || downloading != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (downloading != null) T("⤓ ${downloading.name} : ${maxOf(0, downloading.percent)} %", Cp.Cyan, 13.sp)
                T(state.message, Cp.Yellow, 13.sp, maxLines = 1)
                Box(Modifier.weight(1f))
                if (state.message.isNotEmpty()) T("✕", Cp.Dim, 13.sp, modifier = Modifier.clickable { state.message = "" })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            state.panel?.let { PanelView(state, it) }
            // The page stays in the window even behind a panel: Chromium does not like being taken out.
            SwingPanel(
                background = Color.White,
                factory = { state.cards },
                modifier = if (state.panel == null) Modifier.fillMaxSize() else Modifier.size(1.dp),
            )
        }
    }
}

@Composable
private fun Tabs(state: WebState) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        T("WEB", Cp.Cyan, 20.sp, TitleFont, FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        state.tabs.forEachIndexed { i, t ->
            val on = i == state.current
            Row(
                Modifier.widthIn(max = 230.dp).hudPanel(if (on) Cp.Cyan else Cp.Dim.copy(alpha = 0.5f), if (on) Cp.Cyan.copy(alpha = 0.14f) else Cp.Panel, 6.dp)
                    .clickable { state.select(i) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T((if (t.loading) "◌ " else "") + t.title.ifBlank { Address.short(t.url).ifBlank { "Nouvel onglet" } }, if (on) Cp.Text else Cp.Dim, 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                T("✕", Cp.Dim, 12.sp, modifier = Modifier.clickable { state.close(i) })
            }
        }
        T("+", Cp.Cyan, 20.sp, weight = FontWeight.Bold, modifier = Modifier.clickable { state.open() }.padding(horizontal = 8.dp))
    }
}

@Composable
private fun Toolbar(state: WebState) {
    val t = state.tab
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button("←", if (t?.back == true) Cp.Cyan else Cp.Dim) { t?.browser?.goBack() }
        Button("→", if (t?.forward == true) Cp.Cyan else Cp.Dim) { t?.browser?.goForward() }
        if (t?.loading == true) Button("✕", Cp.Red) { t.browser.stopLoad() } else Button("⟳", Cp.Cyan) { t?.browser?.reload() }
        Button("⌂", Cp.Cyan) { state.go(Address.Home) }
        val shown = state.typed ?: TextFieldValue(t?.url.orEmpty())
        val secure = t?.url?.startsWith("https://") == true
        Row(Modifier.weight(1f).hudPanel(Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            T(if (secure) "🔒 " else if (t?.url?.startsWith("http://") == true) "⚠ " else "", if (secure) Cp.Cyan else Cp.Yellow, 13.sp)
            Box(Modifier.weight(1f)) {
                if (state.typed?.text?.isEmpty() == true) T("Rechercher ou saisir une adresse", Cp.Dim, 14.sp)
                BasicTextField(
                    shown, { state.typed = it }, singleLine = true,
                    textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Cp.Yellow),
                    modifier = Modifier.fillMaxWidth().height(20.dp).focusRequester(state.addressFocus)
                        .onFocusChanged { if (it.isFocused && state.typed == null) state.typed = TextFieldValue(t?.url.orEmpty(), TextRange(0, t?.url.orEmpty().length)) }
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { state.go(shown.text); true } else false
                        },
                )
            }
        }
        val version = state.placesVersion
        val starred = remember(version, t?.url) { t != null && state.places.isBookmarked(t.url) }
        Button(if (starred) "★" else "☆", Cp.Yellow, filled = starred) { state.bookmark() }
        for (p in Panel.entries) Button(p.label, Cp.Yellow, filled = state.panel == p) { state.panel = if (state.panel == p) null else p }
        Button("−", Cp.Dim) { state.zoom(-0.5) }
        Button("+", Cp.Dim) { state.zoom(0.5) }
    }
}

@Composable
private fun FindBar(state: WebState, query: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(300.dp).hudPanel(Cp.Yellow.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (query.isEmpty()) T("Rechercher dans la page…", Cp.Dim, 14.sp)
            BasicTextField(
                query, { state.find = it; state.findNext(it) }, singleLine = true,
                textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(Cp.Yellow),
                modifier = Modifier.fillMaxWidth().height(20.dp).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { state.findNext(query, !e.isShiftPressed); true } else false
                },
            )
        }
        Button("↑", Cp.Yellow) { state.findNext(query, false) }
        Button("↓", Cp.Yellow) { state.findNext(query, true) }
        Button("✕", Cp.Dim) { state.find = null; state.findNext("") }
    }
}

private val day = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE)
private val hour = SimpleDateFormat("HH:mm", Locale.FRANCE)

@Composable
private fun PanelView(state: WebState, panel: Panel) {
    var filter by remember(panel) { mutableStateOf("") }
    val version = state.placesVersion
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            T(panel.label, Cp.Yellow, 20.sp, TitleFont, FontWeight.Bold)
            if (panel != Panel.Downloads) Box(Modifier.width(320.dp).hudPanel(Cp.Yellow.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (filter.isEmpty()) T("Filtrer…", Cp.Dim, 14.sp)
                BasicTextField(
                    filter, { filter = it }, singleLine = true,
                    textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Cp.Yellow), modifier = Modifier.fillMaxWidth().height(20.dp),
                )
            }
            Box(Modifier.weight(1f))
            if (panel == Panel.History) Button("EFFACER L'HISTORIQUE", Cp.Red) { state.places.clearHistory(); state.placesVersion++ }
            if (panel == Panel.Downloads) Button("OUVRIR LE DOSSIER", Cp.Cyan) { open(Engine.downloads().path) }
            Button("FERMER", Cp.Dim) { state.panel = null }
        }
        if (panel == Panel.Downloads) {
            if (state.downloads.isEmpty()) T("Rien de téléchargé depuis l'ouverture de Web. Les fichiers vont dans ${Engine.downloads().path}.", Cp.Dim, 15.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.downloads) { d ->
                    Row(Modifier.fillMaxWidth().hudPanel(Cp.Dim.copy(alpha = 0.4f), cut = 6.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        T(d.name, Cp.Text, 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        T(if (d.failed) "annulé" else if (d.done) "terminé" else "${maxOf(0, d.percent)} %", if (d.failed) Cp.Red else Cp.Cyan, 13.sp, MonoFont, FontWeight.Normal)
                        if (d.done) Button("OUVRIR", Cp.Cyan) { open(d.path) }
                    }
                }
            }
        } else {
            val list = remember(version, filter, panel) {
                val source = if (panel == Panel.Bookmarks) state.places.bookmarks.reversed() else state.places.history
                if (filter.isBlank()) source else state.places.search(filter, 500).filter { p -> source.any { it.url == p.url } }
            }
            if (list.isEmpty()) T(if (panel == Panel.Bookmarks) "Pas encore de favoris : ☆ ou Ctrl+D sur une page." else "Rien dans l'historique.", Cp.Dim, 15.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var lastDay = ""
                for (p in list) {
                    val d = day.format(Date(p.at))
                    if (panel == Panel.History && d != lastDay) {
                        lastDay = d
                        item { T(d.replaceFirstChar { it.uppercase() }, Cp.Yellow, 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { state.go(p.url) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (panel == Panel.History) T(hour.format(Date(p.at)), Cp.Dim, 13.sp, MonoFont, FontWeight.Normal)
                            T(p.title.ifBlank { Address.short(p.url) }, Cp.Text, 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            T(Address.short(p.url), Cp.Dim, 13.sp, MonoFont, FontWeight.Normal, maxLines = 1, modifier = Modifier.widthIn(max = 420.dp))
                            if (panel == Panel.Bookmarks) T("✕", Cp.Dim, 13.sp, modifier = Modifier.clickable { state.places.toggleBookmark(p.url, p.title); state.placesVersion++ })
                        }
                    }
                }
            }
        }
    }
}

/** Opens a file or folder with its app (Photos, PDF, Texte…). */
private fun open(path: String) {
    runCatching { ProcessBuilder("xdg-open", path).start() }
}
