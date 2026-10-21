package dev.netrunner.launcher.system

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "Ripperdoc" power saving: the animated background, the CRT glitches, the boot animation and
 * the audio visualiser stop, and the live wallpaper freezes on one frame. Automatic below
 * [LOW_BATTERY] % (not charging) or with Android's battery saver, or always on.
 */
object Ripperdoc {
    enum class Mode(val label: String) { Off("Off"), Auto("Auto"), Always("Toujours") }

    const val LOW_BATTERY = 20

    var mode by mutableStateOf(Mode.Auto)
        private set
    /** True while the deck runs lean; every animation checks it. */
    var active by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        mode = Mode.entries.firstOrNull { it.name == prefs(context).getString(KEY, null) } ?: Mode.Auto
        refresh(context)
    }

    fun setMode(context: Context, value: Mode) {
        mode = value
        prefs(context).edit().putString(KEY, value.name).apply()
        refresh(context)
    }

    fun refresh(context: Context, battery: BatteryState = SystemInfo.battery(context)) {
        load(context)
        active = when (mode) {
            Mode.Off -> false
            Mode.Always -> true
            Mode.Auto -> context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true ||
                (battery.level in 0 until LOW_BATTERY && !battery.charging)
        }
    }

    private const val KEY = "ripperdoc"

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
