package dev.netrunner.notes.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.notes.data.Note
import dev.netrunner.notes.data.NoteStore

/** The notes on screen, kept in step with the store (As//ka can add notes while the app is open). */
class NotesState(context: Context) {
    private val app = context.applicationContext

    var notes by mutableStateOf(NoteStore.all(app).sortedWith(Note.order))
        private set
    /** The note open in the editor (a new one has an id not yet in the store). */
    var editing by mutableStateOf<Note?>(null)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    fun start() {
        app.contentResolver.registerContentObserver(NoteStore.NOTES_URI, true, observer)
        reload()
    }

    fun stop() = app.contentResolver.unregisterContentObserver(observer)

    fun reload() {
        notes = NoteStore.all(app).sortedWith(Note.order)
    }

    fun new(checklist: Boolean = false, title: String = "", body: String = "") {
        val now = System.currentTimeMillis()
        editing = Note(
            id = NoteStore.newId(app), title = title, body = if (checklist) "" else body, checklist = checklist,
            items = if (checklist) listOf(Note.Item("")) else emptyList(), created = now, updated = now,
        )
    }

    /** Saves the note if it has content, drops it if it was emptied. */
    fun commit(note: Note) {
        val stored = NoteStore.get(app, note.id)
        when {
            note.isEmpty -> if (stored != null) NoteStore.delete(app, note.id)
            stored == null || stored != note -> NoteStore.save(app, note.copy(updated = if (contentChanged(stored, note)) System.currentTimeMillis() else note.updated))
        }
        reload()
    }

    fun togglePin(note: Note) = NoteStore.save(app, note.copy(pinned = !note.pinned)).also { reload() }

    fun delete(note: Note) = NoteStore.delete(app, note.id).also { reload() }

    fun restore(note: Note) = NoteStore.save(app, note).also { reload() }

    /** Pinning or recolouring doesn't move a note to the top; editing its text does. */
    private fun contentChanged(old: Note?, new: Note) =
        old == null || old.title != new.title || old.body != new.body || old.items != new.items || old.checklist != new.checklist
}
