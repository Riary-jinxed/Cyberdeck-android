package dev.netrunner.launcher.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Shares the launcher's theme with the deck apps (signature permission only): one row with the
 * faction id and the glitch setting at content://dev.netrunner.launcher.theme/current. Changes are
 * announced with notifyChange, and the apps read it again on each resume.
 */
class ThemeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        if (uri.pathSegments.firstOrNull() != "current") return null
        val prefs = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
        // Faction.of maps old ids (dogtown) to their current faction.
        val faction = Faction.of(prefs.getString("faction", null)).id
        val glitch = GlitchMode.entries.firstOrNull { it.name == prefs.getString("glitch_mode", null) } ?: GlitchMode.Auto
        return MatrixCursor(arrayOf("faction", "glitch")).apply { addRow(arrayOf(faction, glitch.name)) }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.netrunner.theme"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        val CURRENT: Uri = Uri.parse("content://dev.netrunner.launcher.theme/current")

        fun changed(context: Context) {
            runCatching { context.contentResolver.notifyChange(CURRENT, null) }
        }
    }
}
