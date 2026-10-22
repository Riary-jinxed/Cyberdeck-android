package dev.netrunner.music.data

import android.content.Context

object MusicPrefs {
    enum class Style(val label: String) { BARS("BARRES"), WAVE("ONDE"), RING("ANNEAU") }

    private fun prefs(context: Context) = context.getSharedPreferences("music", Context.MODE_PRIVATE)

    fun style(context: Context): Style =
        runCatching { Style.valueOf(prefs(context).getString("style", null) ?: "") }.getOrDefault(Style.BARS)
    fun setStyle(context: Context, style: Style) = prefs(context).edit().putString("style", style.name).apply()

    /** Neon glow around the visualizer; costs a little battery. */
    fun glow(context: Context) = prefs(context).getBoolean("glow", true)
    fun setGlow(context: Context, on: Boolean) = prefs(context).edit().putBoolean("glow", on).apply()
}
