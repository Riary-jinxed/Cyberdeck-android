package dev.netrunner.recorder.data

import android.content.Context

object RecorderPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)

    /** 48 kHz / 192 kbit/s instead of 44.1 kHz / 96 kbit/s. */
    fun highQuality(context: Context) = prefs(context).getBoolean("high", false)
    fun setHighQuality(context: Context, on: Boolean) = prefs(context).edit().putBoolean("high", on).apply()

    /** Each finished transcript also becomes a note in the Notes app. */
    fun autoNotes(context: Context) = prefs(context).getBoolean("auto_notes", false)
    fun setAutoNotes(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto_notes", on).apply()
}
