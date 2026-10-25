package dev.netrunner.os.text

/**
 * A Netrunner Texte document: its text as one string (paragraphs separated by '\n'), one [Para]
 * per paragraph (kind and alignment) and the character styles as ranges. The editor shows the
 * plain text in one text field and paints the rest over it, so selection, copy and paste work
 * across paragraphs; [edit] keeps styles and paragraphs in step with every change of the text.
 */
data class Doc(val text: String = "", val paras: List<Para> = listOf(Para()), val spans: List<Span> = emptyList()) {
    init {
        require(paras.size == text.count { it == '\n' } + 1) { "${paras.size} paragraphes pour ${text.count { it == '\n' } + 1}" }
    }

    enum class Kind(val label: String) {
        Normal("Normal"), Title("Titre du document"), Heading1("Titre 1"), Heading2("Titre 2"), Heading3("Titre 3"),
        Bullet("Liste à puces"), Numbered("Liste numérotée"), Quote("Citation");

        val list get() = this == Bullet || this == Numbered
        val heading get() = this == Title || this == Heading1 || this == Heading2 || this == Heading3
    }

    enum class Align(val label: String) { Start("Gauche"), Center("Centré"), End("Droite"), Justify("Justifié") }

    enum class Style(val label: String) { Bold("Gras"), Italic("Italique"), Underline("Souligné"), Strike("Barré") }

    data class Para(val kind: Kind = Kind.Normal, val align: Align = Align.Start)

    /** [style] on the characters [start] until [end] (excluded). */
    data class Span(val start: Int, val end: Int, val style: Style)

    /** Where each paragraph starts, computed once per version of the text. */
    private val starts: IntArray by lazy {
        val out = IntArray(paras.size)
        var n = 1
        for (i in text.indices) if (text[i] == '\n') out[n++] = i + 1
        out
    }

    /** Where paragraph [i] starts and ends (the '\n' excluded). */
    fun range(i: Int): IntRange = starts[i] until (if (i + 1 < starts.size) starts[i + 1] - 1 else text.length)

    /** The paragraph holding [offset]. */
    fun paragraphAt(offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        val found = starts.binarySearch(o)
        return if (found >= 0) found else -found - 2
    }

    fun paragraphText(i: Int): String = range(i).let { text.substring(it.first, it.last + 1) }

    /** The styles of the character before [offset]: what typing there continues. */
    fun stylesAt(offset: Int): Set<Style> {
        if (offset <= 0) return spans.filter { it.start == 0 && it.end > 0 }.map { it.style }.toSet()
        return spans.filter { it.start < offset && it.end >= offset }.map { it.style }.toSet()
    }

    fun has(style: Style, start: Int, end: Int): Boolean {
        if (start >= end) return style in stylesAt(start)
        var at = start
        for (s in spans.filter { it.style == style && it.end > start && it.start < end }.sortedBy { it.start }) {
            if (s.start > at) return false
            at = maxOf(at, s.end)
            if (at >= end) return true
        }
        return at >= end
    }

    /** Adds [style] on [start]..[end], or takes it off when it is already everywhere there. */
    fun toggle(style: Style, start: Int, end: Int): Doc = set(style, start, end, !has(style, start, end))

    fun set(style: Style, start: Int, end: Int, on: Boolean): Doc {
        if (start >= end) return this
        val kept = spans.flatMap { s ->
            if (s.style != style || s.end <= start || s.start >= end) listOf(s)
            else listOfNotNull(Span(s.start, start, style).takeIf { it.start < it.end }, Span(end, s.end, style).takeIf { it.start < it.end })
        }
        return copy(spans = normalize(if (on) kept + Span(start, end, style) else kept))
    }

    /** Changes the paragraphs from [first] to [last]. */
    fun paragraphs(first: Int, last: Int, change: (Para) -> Para): Doc =
        copy(paras = paras.mapIndexed { i, p -> if (i in first..last) change(p) else p })

    /**
     * The document once its text became [newText] (typing, pasting, deleting): the changed part
     * is found from both ends, styles move with the text around it and the inserted characters
     * take [typing]; paragraphs split or merged by the change are added or dropped.
     */
    fun edit(newText: String, typing: Set<Style>): Doc {
        if (newText == text) return this
        var p = 0
        val max = minOf(text.length, newText.length)
        while (p < max && text[p] == newText[p]) p++
        var s = 0
        while (s < max - p && text[text.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val oldEnd = text.length - s
        val newEnd = newText.length - s
        val delta = newText.length - text.length

        // A style starting in the removed part starts after the change; one ending there ends before it.
        fun mapStart(a: Int) = if (a >= oldEnd) a + delta else if (a < p) a else newEnd
        fun mapEnd(b: Int) = if (b <= p) b else if (b >= oldEnd) b + delta else p
        var moved = spans.map { Span(mapStart(it.start), mapEnd(it.end), it.style) }.filter { it.start < it.end }
        // The inserted characters: exactly the typing styles.
        if (newEnd > p) {
            for (style in Style.entries) {
                moved = moved.flatMap { sp ->
                    if (sp.style != style || sp.end <= p || sp.start >= newEnd) listOf(sp)
                    else listOfNotNull(Span(sp.start, p, style).takeIf { it.start < it.end }, Span(newEnd, sp.end, style).takeIf { it.start < it.end })
                }
                if (style in typing) moved = moved + Span(p, newEnd, style)
            }
        }

        val pi = text.substring(0, p).count { it == '\n' }
        val removed = text.substring(p, oldEnd).count { it == '\n' }
        val inserted = newText.substring(p, newEnd).count { it == '\n' }
        val first = paras[pi]
        // A new paragraph after a heading is normal text; a list or a quote goes on.
        val next = if (first.kind.heading) Para(Kind.Normal, first.align) else first
        // Split in the middle of a paragraph: both halves keep its kind.
        val tailKept = text.indexOf('\n', oldEnd).let { if (it < 0) text.length else it } > oldEnd
        val added = List(inserted) { i -> if (i == inserted - 1 && tailKept) first else next }
        val newParas = paras.take(pi + 1) + added + paras.drop(pi + removed + 1)
        return Doc(newText, newParas, normalize(moved))
    }

    /**
     * [fragment] put in place of [start]..[end]: its paragraphs keep their kinds (the first one
     * only when it starts a paragraph here) and its styles. How As//ka's Markdown gets in.
     */
    fun insert(start: Int, end: Int, fragment: Doc): Doc {
        val atParagraphStart = start == 0 || text[start - 1] == '\n'
        // Blocks put before existing text end their own paragraph: that text stays on its own.
        val closes = atParagraphStart && end < text.length && text[end] != '\n'
        val inserted = if (closes) fragment.text + "\n" else fragment.text
        val edited = edit(text.substring(0, start) + inserted + text.substring(end), emptySet())
        val first = edited.paragraphAt(start)
        val tail = paras[paragraphAt(end)]
        val paras = edited.paras.mapIndexed { i, p ->
            val j = i - first
            when {
                closes && j == fragment.paras.size -> tail
                j < 0 || j >= fragment.paras.size -> p
                j == 0 && !atParagraphStart -> p
                else -> fragment.paras[j].copy(align = if (fragment.paras[j].align == Align.Start) p.align else fragment.paras[j].align)
            }
        }
        val moved = fragment.spans.map { Span(it.start + start, it.end + start, it.style) }
        return Doc(edited.text, paras, normalize(edited.spans + moved))
    }

    /** Words, characters and paragraphs, for the status bar. */
    fun stats(): String {
        val words = Regex("[\\p{L}\\p{N}'’-]+").findAll(text).count()
        return "$words mot" + (if (words > 1) "s" else "") + " · ${text.count { it != '\n' }} caractères · ${paras.size} paragraphe" + if (paras.size > 1) "s" else ""
    }

    companion object {
        fun of(text: String) = Doc(text, List(text.count { it == '\n' } + 1) { Para() })

        /** Spans of one style merged when they touch or overlap, empty ones dropped. */
        fun normalize(spans: List<Span>): List<Span> = spans.filter { it.start < it.end }.groupBy { it.style }.flatMap { (style, list) ->
            val out = mutableListOf<Span>()
            for (s in list.sortedBy { it.start }) {
                val last = out.lastOrNull()
                if (last != null && s.start <= last.end) out[out.size - 1] = Span(last.start, maxOf(last.end, s.end), style) else out += s
            }
            out
        }.sortedWith(compareBy({ it.start }, { it.style.ordinal }))
    }
}
