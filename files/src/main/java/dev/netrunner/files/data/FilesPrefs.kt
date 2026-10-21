package dev.netrunner.files.data

import android.content.Context

object FilesPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("files", Context.MODE_PRIVATE)

    fun sort(context: Context) = FileOps.Sort.entries.firstOrNull { it.name == prefs(context).getString("sort", null) } ?: FileOps.Sort.NAME
    fun descending(context: Context) = prefs(context).getBoolean("descending", false)
    fun setSort(context: Context, sort: FileOps.Sort, descending: Boolean) =
        prefs(context).edit().putString("sort", sort.name).putBoolean("descending", descending).apply()

    fun showHidden(context: Context) = prefs(context).getBoolean("hidden", false)
    fun setShowHidden(context: Context, on: Boolean) = prefs(context).edit().putBoolean("hidden", on).apply()

    fun lastDir(context: Context): String? = prefs(context).getString("dir", null)
    fun setLastDir(context: Context, path: String) = prefs(context).edit().putString("dir", path).apply()
}
