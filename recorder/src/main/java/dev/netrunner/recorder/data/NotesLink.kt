package dev.netrunner.recorder.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Writes into the deck's Notes app through its provider (same signing key). */
object NotesLink {
    private val NOTES = Uri.parse("content://dev.netrunner.notes.provider/notes")

    /** The new note's uri, or null when Notes is missing or refused. */
    fun add(context: Context, title: String, content: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put("title", title)
            put("content", content)
            put("source", "dictaphone")
        }
        context.contentResolver.insert(NOTES, values)
    }.getOrNull()

    fun open(context: Context, note: Uri) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, note).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
