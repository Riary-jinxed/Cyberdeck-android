package dev.netrunner.launcher.system

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalTime

/**
 * "Netwatch" night mode: from a chosen hour the deck dims, the animated background and the CRT
 * glitches stop, until the morning hour.
 */
object NightWatch {
    var enabled by mutableStateOf(false)
        private set
    var startHour by mutableIntStateOf(23)
        private set
    var endHour by mutableIntStateOf(7)
        private set
    /** True while the night window is running; refreshed once a minute from the home screen. */
    var active by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(context: Context, force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        val p = prefs(context)
        enabled = p.getBoolean("night_on", false)
        startHour = p.getInt("night_start", 23)
        endHour = p.getInt("night_end", 7)
        refresh()
    }

    fun refresh(now: LocalTime = LocalTime.now()) {
        val h = now.hour
        active = enabled && when {
            startHour == endHour -> true
            startHour < endHour -> h in startHour until endHour
            else -> h >= startHour || h < endHour
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        prefs(context).edit().putBoolean("night_on", on).apply()
        refresh()
    }

    /** Moves the start hour forward by one, wrapping at midnight. */
    fun shiftStart(context: Context) {
        startHour = (startHour + 1) % 24
        prefs(context).edit().putInt("night_start", startHour).apply()
        refresh()
    }

    fun shiftEnd(context: Context) {
        endHour = (endHour + 1) % 24
        prefs(context).edit().putInt("night_end", endHour).apply()
        refresh()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
