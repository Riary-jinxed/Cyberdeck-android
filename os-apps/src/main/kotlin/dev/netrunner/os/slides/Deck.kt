package dev.netrunner.os.slides

/**
 * A presentation: slides with a title, lines of text in levels, an optional picture and speaker
 * notes, all drawn in one theme. Immutable: every change makes a new one.
 */
data class Deck(val slides: List<Slide> = listOf(Slide(Layout.Title, "Titre de la présentation", listOf(Line("Sous-titre")))), val theme: Theme = Theme.Netrunner) {
    fun with(i: Int, s: Slide) = copy(slides = slides.toMutableList().also { it[i] = s })

    fun insert(at: Int, new: List<Slide>) = copy(slides = slides.toMutableList().also { it.addAll(at.coerceIn(0, slides.size), new) })

    fun remove(i: Int) = copy(slides = slides.filterIndexed { k, _ -> k != i }.ifEmpty { listOf(Slide()) })

    fun move(from: Int, to: Int): Deck {
        if (from !in slides.indices) return this
        val list = slides.toMutableList()
        val s = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), s)
        return copy(slides = list)
    }
}

enum class Layout(val label: String) {
    Title("Titre"), Content("Titre et contenu"), Section("Section"),
}

data class Line(val text: String, val level: Int = 0)

data class Slide(
    val layout: Layout = Layout.Content,
    val title: String = "",
    val body: List<Line> = emptyList(),
    val notes: String = "",
    val picture: Picture? = null,
) {
    /** The body as typed in the editor: one line per line, two spaces per level. */
    val bodyText: String get() = body.joinToString("\n") { "  ".repeat(it.level) + it.text }

    companion object {
        fun lines(text: String): List<Line> = text.split('\n').map { raw ->
            val expanded = raw.replace("\t", "  ")
            val indent = expanded.length - expanded.trimStart(' ').length
            Line(expanded.trim(), (indent / 2).coerceAtMost(4))
        }.dropLastWhile { it.text.isEmpty() }
    }
}

/** A picture's bytes and their kind ("png", "jpeg", "gif"…); equal when the bytes are. */
class Picture(val bytes: ByteArray, val type: String) {
    override fun equals(other: Any?) = other is Picture && other.type == type && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "Picture($type, ${bytes.size} octets)"
}

/** Colors as 0xRRGGBB. */
enum class Theme(val label: String, val background: Int, val title: Int, val text: Int, val accent: Int) {
    Netrunner("Netrunner", 0x07070D, 0xFCEE0A, 0xE8E8F0, 0x00F0FF),
    Clair("Clair", 0xFFFFFF, 0x1A1A2E, 0x2E2E3A, 0x0B6BCB),
    Ardoise("Ardoise", 0x22303C, 0xFFFFFF, 0xDDE6EE, 0xFF8A3D),
    Papier("Papier", 0xF6F1E7, 0x5A2A1A, 0x3A302A, 0xB0412E),
}

/**
 * Slides as Markdown, for As//ka and for pasting: slides separated by "---", "# " the title,
 * "## " a subtitle (a title slide), "- " lines (two spaces per level), "Notes :" the speaker notes.
 * A slide with only a title is a section.
 */
object SlideMarkdown {
    fun parse(md: String): List<Slide> = md.replace("\r", "").split(Regex("(?m)^\\s*---+\\s*$")).mapNotNull { one(it) }

    private fun one(text: String): Slide? {
        var title = ""
        var subtitle = false
        val body = mutableListOf<Line>()
        val notes = StringBuilder()
        var inNotes = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd()
            val t = line.trim()
            when {
                inNotes -> notes.append(if (notes.isEmpty()) "" else "\n").append(t)
                Regex("^notes?\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(t) -> { inNotes = true; notes.append(t.substringAfter(':').trim()) }
                t.startsWith("## ") -> { body += Line(t.removePrefix("## ").trim()); subtitle = true }
                t.startsWith("# ") && title.isEmpty() -> title = t.removePrefix("# ").trim()
                t.isEmpty() || t.equals("[image]", ignoreCase = true) -> {}
                else -> {
                    val expanded = line.replace("\t", "  ")
                    val indent = expanded.length - expanded.trimStart().length
                    val content = t.replace(Regex("^([-*•]|\\d+[.)])\\s+"), "")
                    body += Line(content, (indent / 2).coerceAtMost(4))
                }
            }
        }
        if (title.isEmpty() && body.isEmpty() && notes.isEmpty()) return null
        val layout = when {
            subtitle -> Layout.Title
            body.isEmpty() -> Layout.Section
            else -> Layout.Content
        }
        return Slide(layout, title, body, notes.toString().trim())
    }

    fun write(slides: List<Slide>): String = slides.joinToString("\n---\n") { s ->
        buildString {
            if (s.title.isNotEmpty()) append("# ").append(s.title).append('\n')
            for (l in s.body) {
                if (s.layout == Layout.Title) append("## ").append(l.text).append('\n')
                else append("  ".repeat(l.level)).append("- ").append(l.text).append('\n')
            }
            if (s.picture != null) append("[image]\n")
            if (s.notes.isNotEmpty()) append("Notes : ").append(s.notes).append('\n')
        }
    }
}
