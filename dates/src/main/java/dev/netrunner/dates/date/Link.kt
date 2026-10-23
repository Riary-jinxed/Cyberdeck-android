package dev.netrunner.dates.date

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import org.json.JSONArray

/**
 * As//ka lives in the launcher: who the runner is, how close they are and what she remembers come
 * from there, and each date goes back there. Through the launcher's provider, guarded by the
 * deck's signing key.
 */
object Link {

    private val PROFILE: Uri = Uri.parse("content://dev.netrunner.launcher.aska/profile")
    private val DATES: Uri = Uri.parse("content://dev.netrunner.launcher.aska/dates")

    data class Profile(
        val handle: String,
        val bond: Float,
        /** The runner opted in to adult dates, in the launcher's settings. */
        val adult: Boolean,
        val facts: List<String>,
        /** Her memories of the last dates, ready for her instructions. */
        val dates: String,
    )

    /** Null when the launcher is missing, too old, or signed with another key. */
    fun profile(context: Context): Profile? = runCatching {
        context.contentResolver.query(PROFILE, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            Profile(
                handle = c.getString(c.getColumnIndexOrThrow("handle")).ifBlank { "V" },
                bond = c.getFloat(c.getColumnIndexOrThrow("bond")),
                adult = c.getInt(c.getColumnIndexOrThrow("adult")) == 1,
                facts = c.getString(c.getColumnIndexOrThrow("facts")).lines().filter { it.isNotBlank() },
                dates = c.getString(c.getColumnIndexOrThrow("dates")).orEmpty(),
            )
        }
    }.getOrNull()

    /** Keeps the longest of [lines], in their order. */
    private fun longest(lines: List<String>, count: Int, max: Int) = lines.map { it.trim() }.filter { it.length >= 12 }
        .withIndex().sortedByDescending { it.value.length }.take(count).sortedBy { it.index }
        .map { it.value.take(max) }

    /** Hands the date back to the launcher: what the runner said, what she did. False if it refused. */
    fun dateEnded(context: Context, scene: AskaDate.Scene, messages: Int, userLines: List<String>, herLines: List<String>): Boolean {
        val gestures = herLines.flatMap { line -> Regex("\\*([^*]+)\\*").findAll(line).map { it.groupValues[1] }.toList() }
        val values = ContentValues().apply {
            put("scene", scene.name)
            put("messages", messages)
            put("said", JSONArray(longest(userLines, 5, 140)).toString())
            put("moments", JSONArray(longest(gestures, 3, 120)).toString())
        }
        return runCatching { context.contentResolver.insert(DATES, values) }.getOrNull() != null
    }
}
