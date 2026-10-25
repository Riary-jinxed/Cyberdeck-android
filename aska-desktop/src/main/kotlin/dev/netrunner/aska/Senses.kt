package dev.netrunner.aska

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeInputEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * What she notices on the desktop: keyboard and mouse activity (counted, never which key is
 * pressed), the window in front, the battery. Everything stays on this PC.
 */
object Activity {
    /** Grows by one on every key press anywhere: she plays along. Kept from one session to the next. */
    private val _taps = MutableStateFlow(Store.taps())
    val taps: StateFlow<Long> = _taps

    /** Grows by one on every Ctrl+Alt+A: she opens her bubble. */
    private val _summons = MutableStateFlow(0L)
    val summons: StateFlow<Long> = _summons

    @Volatile var lastInput: Long = System.currentTimeMillis()
        private set
    @Volatile var lastKey: Long = 0L
        private set
    /** Last time the mouse moved or clicked: her hand goes to her mouse. */
    @Volatile var lastMouse: Long = 0L
        private set

    @Volatile private var lastKeyEvent = 0L

    /** Keys held down: the auto-repeat of a long press counts once. */
    private val held = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** False when the system refused the hook (Wayland without XWayland, for instance). */
    var hooked = false
        private set

    fun start() {
        Logger.getLogger(GlobalScreen::class.java.`package`.name).apply {
            level = Level.OFF
            useParentHandlers = false
        }
        hooked = runCatching {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(keys)
            GlobalScreen.addNativeMouseListener(mouse)
            GlobalScreen.addNativeMouseMotionListener(mouse)
        }.isSuccess
    }

    fun stop() {
        runCatching { GlobalScreen.unregisterNativeHook() }
    }

    private val keys = object : NativeKeyListener {
        override fun nativeKeyPressed(e: NativeKeyEvent) {
            val now = System.currentTimeMillis()
            lastInput = now
            // A release missed (a lock screen, a focus change) must not keep a key silent forever.
            if (now - lastKeyEvent > 3_000) held.clear()
            lastKeyEvent = now
            if (!held.add(e.rawCode)) return
            lastKey = now
            _taps.update { it + 1 }
            val mods = e.modifiers
            if (e.keyCode == NativeKeyEvent.VC_A && mods and NativeInputEvent.CTRL_MASK != 0 && mods and NativeInputEvent.ALT_MASK != 0) {
                _summons.update { it + 1 }
            }
        }

        override fun nativeKeyReleased(e: NativeKeyEvent) {
            held.remove(e.rawCode)
        }
    }

    private val mouse = object : NativeMouseInputListener {
        override fun nativeMouseMoved(e: NativeMouseEvent) {
            lastInput = System.currentTimeMillis()
            lastMouse = lastInput
        }

        override fun nativeMouseDragged(e: NativeMouseEvent) = nativeMouseMoved(e)

        override fun nativeMousePressed(e: NativeMouseEvent) {
            lastInput = System.currentTimeMillis()
            lastMouse = lastInput
        }
    }

    fun idleMillis(now: Long = System.currentTimeMillis()) = now - lastInput

    fun typingRecently(now: Long = System.currentTimeMillis()) = now - lastKey < 4_000
}

/** The window in front: its title and program. */
data class Foreground(val title: String, val process: String, val fullscreen: Boolean) {
    /** As she reads it: « Elden Ring » (eldenring). */
    fun label(): String = when {
        title.isBlank() -> process
        process.isBlank() -> "« ${title.take(80)} »"
        else -> "« ${title.take(80)} » ($process)"
    }

    companion object {
        /** The desktop, the taskbar and her own window, which are not worth a remark. */
        private val SHELL = setOf("explorer", "searchhost", "shellexperiencehost", "startmenuexperiencehost", "lockapp", "aska", "java", "javaw")

        fun isShell(process: String) = process.lowercase() in SHELL

        /** Players, and streaming apps or sites. */
        private val PLAYERS = setOf(
            "vlc", "mpc-hc", "mpc-hc64", "mpc-be", "mpc-be64", "potplayer", "potplayermini", "potplayermini64", "mpv", "kodi", "plex",
            "wmplayer", "video.ui", "netflix", "totem", "celluloid", "smplayer", "jellyfin", "jellyfinmediaplayer", "stremio",
        )
        private val BROWSERS = setOf("chrome", "msedge", "firefox", "opera", "brave", "vivaldi", "chromium", "firefox-esr", "librewolf", "zen")
        private val SITES = listOf(
            "youtube", "netflix", "twitch", "prime video", "disney+", "crunchyroll", "adn", "canal+", "molotov", "arte",
            "france.tv", "paramount+", "apple tv", "max", "dailymotion", "vimeo", "plex", "jellyfin", "anime",
        )

        /** True when the window in front plays something to watch: a video player, or a streaming site in a browser. */
        fun watching(f: Foreground?): Boolean {
            f ?: return false
            val process = f.process.lowercase().removeSuffix(".exe")
            if (process in PLAYERS) return true
            if (process !in BROWSERS) return false
            val title = f.title.lowercase()
            return SITES.any { site -> Regex("(^|[^a-z])" + Regex.escape(site) + "([^a-z]|$)").containsMatchIn(title) }
        }

        fun read(): Foreground? = runCatching { if (isWindows) windows() else linux() }.getOrNull()

        private fun windows(): Foreground? {
            val user32 = User32.INSTANCE
            val hwnd = user32.GetForegroundWindow() ?: return null
            val length = user32.GetWindowTextLength(hwnd)
            val buffer = CharArray(length + 1)
            user32.GetWindowText(hwnd, buffer, buffer.size)
            val pid = IntByReference()
            user32.GetWindowThreadProcessId(hwnd, pid)
            val process = ProcessHandle.of(pid.value.toLong()).flatMap { it.info().command() }
                .map { File(it).name.substringBeforeLast('.') }.orElse("")
            val rect = WinDef.RECT()
            user32.GetWindowRect(hwnd, rect)
            val monitor = WinUser.MONITORINFO()
            user32.GetMonitorInfo(user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor)
            val screen = monitor.rcMonitor
            // A game in full screen covers its whole monitor, taskbar included; a maximized window does not.
            val fullscreen = !isShell(process) && rect.left <= screen.left && rect.top <= screen.top &&
                rect.right >= screen.right && rect.bottom >= screen.bottom
            return Foreground(String(buffer, 0, length), process, fullscreen)
        }

        /** X11 (and XWayland) through xdotool, else xprop. */
        private fun linux(): Foreground? {
            val id = run("xdotool", "getactivewindow")
                ?: run("xprop", "-root", "_NET_ACTIVE_WINDOW")?.substringAfter("# ", "")?.trim()?.takeIf { it.startsWith("0x") }
                ?: return null
            val title = run("xdotool", "getwindowname", id)
                ?: run("xprop", "-id", id, "_NET_WM_NAME")?.substringAfter("= ", "")?.trim('"')
                ?: ""
            val pid = run("xdotool", "getwindowpid", id)?.toLongOrNull()
                ?: run("xprop", "-id", id, "_NET_WM_PID")?.substringAfter("= ", "")?.trim()?.toLongOrNull()
            val process = pid?.let { p -> ProcessHandle.of(p).flatMap { it.info().command() }.map { File(it).name }.orElse("") }.orEmpty()
            val state = run("xprop", "-id", id, "_NET_WM_STATE").orEmpty()
            return Foreground(title, process, state.contains("_NET_WM_STATE_FULLSCREEN"))
        }

        private fun run(vararg command: String): String? = runCatching {
            val p = ProcessBuilder(*command).redirectErrorStream(true).start()
            if (!p.waitFor(1, TimeUnit.SECONDS)) { p.destroy(); return null }
            if (p.exitValue() != 0) return null
            p.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

/** A laptop's battery; null on a desktop PC. */
data class Battery(val percent: Int, val charging: Boolean) {
    val low get() = percent <= 15 && !charging

    fun note() = "batterie à $percent %" + if (charging) " (en charge)" else ""

    companion object {
        fun read(): Battery? = runCatching { if (isWindows) windows() else linux() }.getOrNull()

        private fun windows(): Battery? {
            val status = PowerStatus()
            if (!Kernel.INSTANCE.GetSystemPowerStatus(status)) return null
            val flag = status.BatteryFlag.toInt() and 0xFF
            val percent = status.BatteryLifePercent.toInt() and 0xFF
            // 128: no battery, 255: unknown.
            if (flag == 128 || flag == 255 || percent > 100) return null
            return Battery(percent, status.ACLineStatus.toInt() == 1)
        }

        private fun linux(): Battery? {
            val bat = File("/sys/class/power_supply").listFiles()?.firstOrNull { it.name.startsWith("BAT") } ?: return null
            val percent = File(bat, "capacity").readText().trim().toInt()
            val status = File(bat, "status").readText().trim()
            return Battery(percent, status == "Charging" || status == "Full")
        }
    }
}

@Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
class PowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

interface Kernel : Library {
    fun GetSystemPowerStatus(status: PowerStatus): Boolean

    companion object {
        val INSTANCE: Kernel by lazy { Native.load("kernel32", Kernel::class.java) }
    }
}
