package dev.netrunner.aska

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.util.concurrent.TimeUnit

/** A window open on the desktop, in front or not: its title and its program. */
data class OpenWindow(val title: String, val process: String)

/**
 * What the open windows tell, by their titles only: the song playing (Spotify, foobar2000,
 * YouTube Music…) and the unread messages that apps show as « (3) » (Discord, Telegram, WhatsApp,
 * Gmail…). She never reads the messages themselves.
 */
object OpenWindows {
    fun list(): List<OpenWindow> = runCatching { if (isWindows) windows() else linux() }.getOrDefault(emptyList())

    private val names = HashMap<Int, String>()

    private fun windows(): List<OpenWindow> {
        val user32 = User32.INSTANCE
        val found = mutableListOf<OpenWindow>()
        user32.EnumWindows(WinUser.WNDENUMPROC { hwnd, _ ->
            if (user32.IsWindowVisible(hwnd)) {
                val length = user32.GetWindowTextLength(hwnd)
                if (length > 0) {
                    val buffer = CharArray(length + 1)
                    user32.GetWindowText(hwnd, buffer, buffer.size)
                    val pid = IntByReference()
                    user32.GetWindowThreadProcessId(hwnd, pid)
                    val process = synchronized(names) {
                        names.getOrPut(pid.value) {
                            ProcessHandle.of(pid.value.toLong()).flatMap { it.info().command() }.map { File(it).name.substringBeforeLast('.') }.orElse("")
                        }
                    }
                    found += OpenWindow(String(buffer, 0, length), process)
                }
            }
            true
        }, null)
        return found
    }

    /** wmctrl -lp: id, desktop, pid, host, title. */
    private fun linux(): List<OpenWindow> {
        val p = ProcessBuilder("wmctrl", "-lp").redirectErrorStream(true).start()
        if (!p.waitFor(1, TimeUnit.SECONDS)) { p.destroy(); return emptyList() }
        return p.inputStream.bufferedReader().readLines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 5)
            if (parts.size < 5) return@mapNotNull null
            val process = parts[2].toLongOrNull()?.let { pid -> ProcessHandle.of(pid).flatMap { it.info().command() }.map { File(it).name }.orElse("") }.orEmpty()
            OpenWindow(parts[4], process)
        }
    }

    // ---- Music.

    data class Song(val title: String, val app: String)

    private val BROWSERS = setOf("chrome", "msedge", "firefox", "opera", "brave", "vivaldi", "chromium", "librewolf", "zen")
    private val IDLE_TITLES = setOf("spotify", "spotify premium", "spotify free", "foobar2000", "musicbee", "aimp", "deezer", "tidal", "itunes", "apple music")

    /** « Song - Artist - YouTube Music - Google Chrome »: the site's name follows the song in the tab's title. */
    private val WEB_PLAYER = Regex("\\s+[-–—|•]\\s+(YouTube Music|Deezer|SoundCloud)\\b.*$", RegexOption.IGNORE_CASE)

    /** The song of the first window that plays one. */
    fun song(windows: List<OpenWindow>): Song? = windows.firstNotNullOfOrNull { w ->
        val process = w.process.lowercase().removeSuffix(".exe")
        val title = w.title.trim()
        val lower = title.lowercase()
        when {
            lower in IDLE_TITLES -> null
            process == "spotify" && title.contains(" - ") -> Song(title, "Spotify")
            process == "foobar2000" && title.contains(" - ") -> Song(title.substringBefore(" [foobar2000").trim(), "foobar2000")
            process in setOf("musicbee", "aimp", "tidal", "deezer") && title.contains(" - ") -> Song(title.substringBefore(" - MusicBee"), w.process)
            process in BROWSERS && WEB_PLAYER.containsMatchIn(title) -> title.replace(WEB_PLAYER, "").trim().takeIf { it.isNotEmpty() }?.let { Song(it, "le navigateur") }
            else -> null
        }
    }

    /** On Linux, the MPRIS player (any player) through playerctl. */
    fun linuxSong(): Song? = runCatching {
        val p = ProcessBuilder("playerctl", "metadata", "--format", "{{status}}|{{artist}} - {{title}}|{{playerName}}").redirectErrorStream(true).start()
        if (!p.waitFor(1, TimeUnit.SECONDS)) { p.destroy(); return null }
        val (status, title, player) = p.inputStream.bufferedReader().readText().trim().split("|").takeIf { it.size == 3 } ?: return null
        if (status != "Playing" || title.isBlank() || title == " - ") null else Song(title.trim(' ', '-'), player)
    }.getOrNull()

    // ---- Unread messages.

    private val MESSAGING = mapOf(
        "discord" to "Discord", "telegram" to "Telegram", "whatsapp" to "WhatsApp", "signal" to "Signal", "slack" to "Slack",
        "ms-teams" to "Teams", "teams" to "Teams", "thunderbird" to "Thunderbird", "element" to "Element",
    )
    private val WEB = listOf("discord" to "Discord", "whatsapp" to "WhatsApp", "gmail" to "Gmail", "outlook" to "Outlook", "messenger" to "Messenger", "proton mail" to "Proton Mail")
    private val COUNT = Regex("\\((\\d{1,4})\\)")

    /** Unread counts per app, as their windows show them (« (3) Discord », « Telegram (12) »). */
    fun unread(windows: List<OpenWindow>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        windows.forEach { w ->
            val process = w.process.lowercase().removeSuffix(".exe")
            val lower = w.title.lowercase()
            val app = MESSAGING[process] ?: if (process in BROWSERS) WEB.firstOrNull { lower.contains(it.first) }?.second else null
            app ?: return@forEach
            val n = COUNT.find(w.title)?.groupValues?.get(1)?.toInt() ?: 0
            counts[app] = maxOf(counts[app] ?: 0, n)
        }
        return counts
    }
}
