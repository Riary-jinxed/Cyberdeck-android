package dev.netrunner.launcher.aska

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The bridge to the Rendez-vous app (same signing key): the dates now happen there, but As//ka
 * stays one. It reads who the runner is, how close they are and what she remembers; it brings
 * back what happened, which the launcher keeps.
 */
object AskaLink {

    const val PACKAGE = "dev.netrunner.dates"
    private const val PENDING = "aska_pending_dates"

    /** Opens the Rendez-vous app on [scene]. False when it is not installed. */
    fun openDates(context: Context, scene: AskaDate.Scene): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        intent.putExtra("scene", scene.name)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** A date that ended in the app: her memory of it now, the bond once her nest opens. */
    fun dateEnded(context: Context, scene: AskaDate.Scene, messages: Int, said: List<String>, moments: List<String>) {
        AskaRecall.saveDate(context, scene.label, said, moments)
        val prefs = prefs(context)
        synchronized(this) {
            val pending = runCatching { JSONArray(prefs.getString(PENDING, null) ?: "[]") }.getOrDefault(JSONArray())
            pending.put(JSONObject().put("scene", scene.name).put("messages", messages))
            prefs.edit().putString(PENDING, pending.toString()).apply()
        }
    }

    /** The dates waiting for their reward, oldest first; cleared once read. */
    fun takePending(context: Context): List<Pair<AskaDate.Scene, Int>> = synchronized(this) {
        val prefs = prefs(context)
        val raw = prefs.getString(PENDING, null) ?: return emptyList()
        prefs.edit().remove(PENDING).apply()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            AskaDate.Scene.entries.firstOrNull { it.name == o.optString("scene") }?.let { it to o.optInt("messages") }
        }
    }

    fun facts(context: Context): List<String> = runCatching {
        val array = JSONArray(File(context.filesDir, "aska_memory.json").readText())
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}

/**
 * content://dev.netrunner.launcher.aska/profile: one row with handle, bond (0 to 100), adult
 * (1 when the runner opted in to adult dates), facts (one per line) and dates (her memories of
 * the last rendez-vous, ready for her instructions).
 * Insert on content://dev.netrunner.launcher.aska/dates: scene (the scene's name), messages, and
 * said / moments (JSON arrays of strings).
 * Guarded by a signature permission, like the notes.
 */
class AskaLinkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        if (uri.lastPathSegment != "profile") return null
        val prefs = ctx.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
        val handle = prefs.getString("handle", "V") ?: "V"
        val bond = AskaPet.projected(ctx)?.bond?.coerceIn(0f, 100f) ?: 20f
        return MatrixCursor(arrayOf("handle", "bond", "adult", "facts", "dates")).apply {
            addRow(
                arrayOf<Any>(
                    handle,
                    bond,
                    if (prefs.getBoolean("adult_dates", false)) 1 else 0,
                    AskaLink.facts(ctx).joinToString("\n"),
                    AskaRecall.datesForPrompt(ctx, handle),
                ),
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        if (uri.lastPathSegment != "dates" || values == null) return null
        val scene = AskaDate.Scene.entries.firstOrNull { it.name == values.getAsString("scene") } ?: return null
        fun list(key: String) = runCatching {
            val array = JSONArray(values.getAsString(key) ?: "[]")
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        AskaLink.dateEnded(ctx, scene, values.getAsInteger("messages") ?: 0, list("said"), list("moments"))
        return uri
    }

    override fun getType(uri: Uri): String? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
