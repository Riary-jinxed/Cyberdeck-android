package dev.netrunner.notes.data

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One note: free text, or a checklist when [items] is not empty. Plain Kotlin, tested on the JVM. */
data class Note(
    val id: Long,
    val title: String = "",
    val body: String = "",
    val items: List<Item> = emptyList(),
    val checklist: Boolean = false,
    val pinned: Boolean = false,
    val color: Int = 0,
    val created: Long = 0,
    val updated: Long = 0,
    /** Who wrote it: "" for the user, "aska" for notes added by As//ka. */
    val source: String = "",
) {
    data class Item(val text: String, val done: Boolean = false)

    val isEmpty get() = title.isBlank() && body.isBlank() && items.none { it.text.isNotBlank() }

    /** The text shown on the card and shared: the body, or the checklist as "☐ / ☑" lines. */
    fun content(): String = if (checklist) items.joinToString("\n") { (if (it.done) "☑ " else "☐ ") + it.text } else body

    fun shareText(): String = listOf(title.trim(), content().trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** Switches between text and checklist, keeping the content line by line. */
    fun toggleChecklist(): Note = if (checklist) {
        copy(checklist = false, body = items.joinToString("\n") { it.text }, items = emptyList())
    } else {
        copy(checklist = true, items = body.lines().filter { it.isNotBlank() }.map { Item(it.trim()) }, body = "")
    }

    companion object {
        /** Pinned first, then the most recently changed. */
        val order: Comparator<Note> = compareByDescending<Note> { it.pinned }.thenByDescending { it.updated }

        /** Notes containing every word of [query] in the title or content, accents and case ignored. */
        fun filter(notes: List<Note>, query: String): List<Note> {
            val words = fold(query).split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return notes
            return notes.filter { n -> fold(n.title + "\n" + n.content()).let { t -> words.all { t.contains(it) } } }
        }

        fun fold(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        /** "14:32" today, "hier", "12 oct." this year, "12/10/2025" before. */
        fun age(ms: Long, now: Long = System.currentTimeMillis()): String {
            val a = Calendar.getInstance().apply { timeInMillis = ms }
            val b = Calendar.getInstance().apply { timeInMillis = now }
            val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            val days = b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR)
            return when {
                sameYear && days == 0 -> SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(ms))
                sameYear && days == 1 -> "hier"
                sameYear -> SimpleDateFormat("d MMM", Locale.FRANCE).format(Date(ms))
                else -> SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(ms))
            }
        }
    }
}
