package dev.netrunner.launcher.aska

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import java.text.Normalizer
import java.util.Locale

/**
 * Notes Netrunner, through its provider: As//ka reads the runner's notes and adds new ones
 * (always confirmed first). Understood without the model, like the alarms.
 */
object AskaNotes {

    data class Note(val id: Long, val title: String, val content: String) {
        /** The title, or the first line when it has none. */
        val name get() = title.ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(40) }
    }

    sealed class Request {
        data class Add(val title: String, val content: String) : Request()
        object List : Request()
        data class Find(val query: String) : Request()
    }

    private val URI: Uri = Uri.parse("content://dev.netrunner.notes.provider/notes")

    // On the original text: the note keeps its case and accents.
    private val ADD = Regex(
        "^\\s*(?:as//ka\\s*,?\\s*)?(?:(?:tu peux|peux[- ]tu)\\s+)?" +
            "(?:(?:prends?|prendre|ajoute[rz]?|cr[ée]e[rz]?|fais|faire|[ée]cris|[ée]crire)(?:[- ]moi)?\\s+(?:une\\s+)?(?:nouvelle\\s+)?note|nouvelle\\s+note|note\\s+dans\\s+mes\\s+notes)" +
            "(?:\\s+dans\\s+(?:mes|les)\\s+notes)?\\s*(?::|que\\b|qu'|pour\\b|sur\\b|avec\\b)?\\s*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val LIST_ADD = Regex(
        "^\\s*(?:as//ka\\s*,?\\s*)?(?:(?:fais|faire|cr[ée]e[rz]?|ajoute[rz]?|nouvelle)(?:[- ]moi)?\\s+(?:une\\s+)?)?liste\\s+(?:de\\s+courses|(?:de|des|pour)\\s+([^:]{1,40}?))\\s*:\\s*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    // On the normalized text.
    private val LIST = Regex("^(lis|lire|montre|affiche|donne)[- ]?(moi)?\\s+(mes|les)\\s+(dernieres\\s+)?notes\\b|^(mes|les)\\s+notes\\s*\\??$|^(quelles sont|qu'est-ce qu'il y a dans)\\s+mes\\s+notes|j'ai quoi (comme|dans mes) notes")
    private val FIND = Regex("^(?:lis|lire|montre|affiche|ouvre|donne)[- ]?(?:moi)?\\s+(?:ma|la|une)\\s+note\\s+(?:sur|de|des|du|d'|pour|avec|qui parle de)?\\s*(.+)$|^(?:cherche|trouve|recherche)\\s+(?:dans|parmi)\\s+mes\\s+notes\\s+(.+)$|^(?:cherche|trouve|recherche)\\s+(?:ma|la)\\s+note\\s+(?:sur|de|des|du|d'|pour)?\\s*(.+)$|^(?:qu'est-ce que dit|que dit)\\s+(?:ma|la)\\s+note\\s+(?:sur|de|des|du|d'|pour)?\\s*(.+)$")

    fun parse(input: String): Request? {
        LIST_ADD.find(input)?.let { m ->
            val items = m.groupValues[2].split(Regex("\\s*(?:,|;|\\bet\\b|\\n)\\s*")).map { it.trim(' ', '.') }.filter { it.isNotBlank() }
            if (items.isNotEmpty()) {
                val title = m.groupValues[1].trim().ifEmpty { "Courses" }.replaceFirstChar { it.uppercase() }
                return Request.Add(title, items.joinToString("\n") { "[ ] $it" })
            }
        }
        ADD.find(input)?.let { m ->
            val text = m.groupValues[1].trim().trimEnd('.').replace(Regex("^(?:de |d'|d’)", RegexOption.IGNORE_CASE), "")
            if (text.isBlank()) return null
            // "courses : lait, œufs" → a title and the rest.
            val colon = text.indexOf(':')
            return if (colon in 1..40) Request.Add(text.substring(0, colon).trim(), text.substring(colon + 1).trim())
            else Request.Add("", text.replaceFirstChar { it.uppercase() })
        }
        val t = normalize(input).trim().trimEnd('?', '.', '!', ' ')
        if (LIST.containsMatchIn(t)) return Request.List
        FIND.find(t)?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { return Request.Find(it.trim()) } }
        return null
    }

    fun installed(context: Context) = context.packageManager.resolveContentProvider(URI.authority!!, 0) != null

    /** The notes, pinned first then the most recent. Null when they cannot be read. */
    private fun all(context: Context): List<Note>? = runCatching {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow("_id")
            val title = c.getColumnIndexOrThrow("title")
            val content = c.getColumnIndexOrThrow("content")
            buildList { while (c.moveToNext()) add(Note(c.getLong(id), c.getString(title).orEmpty(), c.getString(content).orEmpty())) }
        }
    }.getOrNull()

    private fun unavailable(context: Context) =
        if (!installed(context)) "Je ne trouve pas l'appli Notes Netrunner. Installe-la depuis Réglages › Applis du deck."
        else "Notes Netrunner ne me laisse pas entrer… Mets à jour Notes puis le launcher, et je pourrai lire tes notes."

    fun listText(context: Context): String {
        val notes = all(context) ?: return unavailable(context)
        if (notes.isEmpty()) return "Aucune note pour l'instant. Dis « prends une note : … » et je l'écris."
        val more = if (notes.size > 6) "\n…et ${notes.size - 6} autres." else ""
        return "Tes notes :\n" + notes.take(6).joinToString("\n") { "▸ ${it.name}" } + more
    }

    fun findText(context: Context, query: String): String {
        val notes = all(context) ?: return unavailable(context)
        val words = normalize(query).split(Regex("\\s+")).filter { it.length > 1 && it !in STOP }
        val found = notes.filter { n -> normalize(n.title + "\n" + n.content).let { t -> words.isNotEmpty() && words.all { t.contains(it) } } }
        return when {
            found.isEmpty() -> "Aucune note ne parle de « $query »."
            found.size == 1 -> found[0].let { n -> (if (n.title.isNotBlank()) "« ${n.title} »\n" else "") + n.content.take(700) }
            else -> "${found.size} notes en parlent :\n" + found.take(6).joinToString("\n") { "▸ ${it.name}" } + "\nDis-moi laquelle lire."
        }
    }

    fun add(context: Context, title: String, content: String): String {
        val values = ContentValues().apply {
            put("title", title)
            put("content", content)
            put("source", "aska")
        }
        val uri = runCatching { context.contentResolver.insert(URI, values) }.getOrNull()
        return if (uri != null) "C'est écrit dans tes notes." else unavailable(context)
    }

    private val STOP = setOf("la", "le", "les", "de", "des", "du", "un", "une", "mes", "ma", "mon", "sur", "pour")

    private fun normalize(s: String) =
        Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'')
}
