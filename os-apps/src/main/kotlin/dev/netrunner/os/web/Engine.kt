package dev.netrunner.os.web

import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.EventFlags
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * The page engine under Netrunner Web: Chromium, through JCEF. The OS package ships it in
 * /usr/lib/netrunner/jcef (installed at build time with `web --installer <dir>`); elsewhere it
 * is fetched once into ~/.local/share/netrunner/jcef.
 */
class Engine private constructor(private val app: CefApp, private val events: Events) {
    /** What the engine tells the window, always on the Swing thread. */
    interface Events {
        fun address(browser: CefBrowser, url: String)
        fun title(browser: CefBrowser, title: String)
        fun loading(browser: CefBrowser, loading: Boolean, back: Boolean, forward: Boolean)
        fun popup(url: String)
        fun download(id: Int, name: String, percent: Int, done: Boolean, failed: Boolean, path: String)
        /** A key pressed in a page; true when the window took it. */
        fun key(ctrl: Boolean, shift: Boolean, alt: Boolean, code: Int): Boolean
    }

    private val client: CefClient = app.createClient()

    init {
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(browser: CefBrowser, frame: CefFrame?, url: String) {
                if (frame == null || frame.isMain) ui { events.address(browser, url) }
            }

            override fun onTitleChange(browser: CefBrowser, title: String?) = ui { events.title(browser, title.orEmpty()) }
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(browser: CefBrowser, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) =
                ui { events.loading(browser, isLoading, canGoBack, canGoForward) }
        })
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            // Pages that open a window open a tab instead.
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
                if (!targetUrl.isNullOrBlank()) ui { events.popup(targetUrl) }
                return true
            }
        })
        client.addDownloadHandler(object : CefDownloadHandlerAdapter() {
            override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem, suggestedName: String?, callback: CefBeforeDownloadCallback): Boolean {
                val name = (suggestedName?.takeIf { it.isNotBlank() } ?: "telechargement").replace('/', '_')
                callback.Continue(free(File(downloads(), name)).path, false)
                return true
            }

            override fun onDownloadUpdated(browser: CefBrowser, item: CefDownloadItem, callback: CefDownloadItemCallback) {
                if (!item.isValid) return
                val path = item.fullPath.orEmpty()
                val name = File(path).name.ifEmpty { item.suggestedFileName.orEmpty() }
                val id = item.id
                val percent = item.percentComplete
                val done = item.isComplete
                val failed = item.isCanceled
                ui { events.download(id, name, percent, done, failed, path) }
            }
        })
        client.addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
            override fun onPreKeyEvent(browser: CefBrowser, event: CefKeyboardHandler.CefKeyEvent, isShortcut: BoolRef): Boolean {
                if (event.type != CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) return false
                val m = event.modifiers
                val ctrl = m and EventFlags.EVENTFLAG_CONTROL_DOWN != 0
                val alt = m and EventFlags.EVENTFLAG_ALT_DOWN != 0
                val shift = m and EventFlags.EVENTFLAG_SHIFT_DOWN != 0
                // Only what the window has a use for leaves the page.
                if (!ctrl && !alt && event.windows_key_code !in listOf(116, 122, 27)) return false
                return onUi { events.key(ctrl, shift, alt, event.windows_key_code) }
            }
        })
    }

    fun browser(url: String): CefBrowser = client.createBrowser(url, false, false).also { it.createImmediately() }

    /** The text of the page, as As//ka reads it. Not to be called on the Swing thread. */
    fun text(browser: CefBrowser, timeoutMs: Long = 6000): String? {
        val result = CompletableFuture<String>()
        browser.getText { result.complete(it) }
        return runCatching { result.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    fun close() {
        runCatching { client.dispose() }
        runCatching { app.dispose() }
    }

    companion object {
        private val home = File(System.getProperty("user.home"))
        val SystemDir = File("/usr/lib/netrunner/jcef")
        val UserDir = File(home, ".local/share/netrunner/jcef")

        /** Where the engine is: in the OS, else for this user. */
        fun installDir(): File = if (File(SystemDir, "install.lock").isFile) SystemDir else UserDir

        /** Fetches the engine into [dir] (the OS package does it when it is built). */
        fun install(dir: File) {
            CefAppBuilder().apply { setInstallDir(dir) }.install()
        }

        fun start(events: Events, onProgress: (String) -> Unit = {}): Engine {
            val b = CefAppBuilder()
            b.setInstallDir(installDir())
            b.setProgressHandler { state, percent -> onProgress(if (percent >= 0) "$state ${percent.toInt()} %" else state.toString()) }
            val profile = File(home, ".cache/netrunner/web").apply { mkdirs() }
            b.cefSettings.windowless_rendering_enabled = false
            b.cefSettings.locale = "fr"
            b.cefSettings.root_cache_path = profile.path
            b.cefSettings.cache_path = File(profile, "profil").path
            b.cefSettings.persist_session_cookies = true
            // Chromium's sandbox refuses root (a build machine); users are never root.
            if (System.getProperty("user.name") == "root") b.addJcefArgs("--no-sandbox")
            b.setAppHandler(object : MavenCefAppHandlerAdapter() {})
            return Engine(b.build(), events)
        }

        fun downloads(): File {
            val xdg = runCatching {
                val p = ProcessBuilder("xdg-user-dir", "DOWNLOAD").redirectErrorStream(true).start()
                val out = p.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).trim()
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) File(out) else null
            }.getOrNull()
            return listOfNotNull(xdg, File(home, "Téléchargements"), File(home, "Downloads")).firstOrNull { it.isDirectory && it != home }
                ?: File(home, "Téléchargements").apply { mkdirs() }
        }

        /** [f], or "nom (2).ext" when it is taken. */
        fun free(f: File): File {
            if (!f.exists()) return f
            var n = 2
            while (true) {
                val c = File(f.parentFile, "${f.nameWithoutExtension} ($n)" + (if (f.extension.isEmpty()) "" else ".${f.extension}"))
                if (!c.exists()) return c
                n++
            }
        }

        private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

        private fun onUi(block: () -> Boolean): Boolean {
            if (SwingUtilities.isEventDispatchThread()) return block()
            var r = false
            runCatching { SwingUtilities.invokeAndWait { r = block() } }
            return r
        }
    }
}
