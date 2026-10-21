package dev.netrunner.gallery.data

import android.content.Context

object GalleryPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    /** Holo filter on the grid and when a photo opens. */
    fun holo(context: Context) = prefs(context).getBoolean("holo", false)
    fun setHolo(context: Context, on: Boolean) = prefs(context).edit().putBoolean("holo", on).apply()

    fun columns(context: Context) = prefs(context).getInt("columns", 4)
    fun setColumns(context: Context, n: Int) = prefs(context).edit().putInt("columns", n).apply()
}
