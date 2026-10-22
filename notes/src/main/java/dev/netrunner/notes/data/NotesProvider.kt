package dev.netrunner.notes.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Lets the other deck apps (As//ka in the launcher) read and add notes:
 * content://dev.netrunner.notes.provider/notes, guarded by signature permissions.
 * Columns: _id, title, content, checklist, pinned, updated, source.
 * Insert takes "title" and "content" (lines starting with "[ ]" or "[x]" make a checklist), and "source";
 * update (on notes/ID) takes "title", "content" and "pinned". Query accepts ?q=words (accents and case ignored)
 * and ?kit=CODE (notes holding a "kit:CODE" link to a field-kit item).
 */
class NotesProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(NoteStore.AUTHORITY, "notes", NOTES)
        addURI(NoteStore.AUTHORITY, "notes/#", NOTE)
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        NOTES -> "vnd.android.cursor.dir/vnd.netrunner.note"
        NOTE -> "vnd.android.cursor.item/vnd.netrunner.note"
        else -> null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val notes = when (matcher.match(uri)) {
            NOTES -> {
                var list = NoteStore.all(ctx).sortedWith(Note.order)
                uri.getQueryParameter("q")?.let { list = Note.filter(list, it) }
                uri.getQueryParameter("kit")?.let { code -> list = list.filter { code in it.kitCodes() } }
                list
            }
            NOTE -> listOfNotNull(NoteStore.get(ctx, ContentUris.parseId(uri)))
            else -> return null
        }
        val cursor = MatrixCursor(COLUMNS)
        notes.forEach { n ->
            cursor.addRow(arrayOf<Any>(n.id, n.title, n.content(), if (n.checklist) 1 else 0, if (n.pinned) 1 else 0, n.updated, n.source))
        }
        cursor.setNotificationUri(ctx.contentResolver, NoteStore.NOTES_URI)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        if (matcher.match(uri) != NOTES || values == null) return null
        val now = System.currentTimeMillis()
        val note = withContent(
            Note(
                id = NoteStore.newId(ctx),
                title = values.getAsString("title").orEmpty(),
                created = now,
                updated = now,
                source = values.getAsString("source") ?: "aska",
            ),
            values.getAsString("content").orEmpty(),
        )
        if (note.isEmpty) return null
        NoteStore.save(ctx, note)
        return ContentUris.withAppendedId(NoteStore.NOTES_URI, note.id)
    }

    /** Changes "title", "content" (same rules as insert) and/or "pinned" of one note. */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val ctx = context ?: return 0
        if (matcher.match(uri) != NOTE || values == null) return 0
        var note = NoteStore.get(ctx, ContentUris.parseId(uri)) ?: return 0
        values.getAsString("title")?.let { note = note.copy(title = it) }
        values.getAsString("content")?.let { note = withContent(note, it) }
        values.getAsBoolean("pinned")?.let { note = note.copy(pinned = it) }
        if (note.isEmpty) return 0
        NoteStore.save(ctx, note.copy(updated = System.currentTimeMillis()))
        return 1
    }

    private fun withContent(note: Note, content: String): Note {
        val lines = content.lines().filter { it.isNotBlank() }
        val isList = lines.isNotEmpty() && lines.all { CHECK.matches(it) }
        return note.copy(
            body = if (isList) "" else content,
            items = if (isList) lines.map { CHECK.find(it)!!.let { m -> Note.Item(m.groupValues[2].trim(), m.groupValues[1].isNotBlank()) } } else emptyList(),
            checklist = isList,
        )
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val ctx = context ?: return 0
        if (matcher.match(uri) != NOTE) return 0
        val id = ContentUris.parseId(uri)
        if (NoteStore.get(ctx, id) == null) return 0
        NoteStore.delete(ctx, id)
        return 1
    }

    private companion object {
        const val NOTES = 1
        const val NOTE = 2
        val COLUMNS = arrayOf("_id", "title", "content", "checklist", "pinned", "updated", "source")
        val CHECK = Regex("""^\s*\[([ xX]?)]\s?(.*)$""")
    }
}
