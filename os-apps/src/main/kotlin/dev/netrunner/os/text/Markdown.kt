package dev.netrunner.os.text

import dev.netrunner.os.text.Doc.Kind
import dev.netrunner.os.text.Doc.Style

/**
 * Light Markdown to and from a [Doc]: how As//ka writes into Netrunner Texte (she answers in
 * Markdown) and the .md format. Headings (#, ##, ###), lists (-, *, 1.), quotes (>), **gras**,
 * *italique*, __souligné__ (not Markdown, but useful here) and ~~barré~~.
 */
object Markdown {
    private val Inline = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|~~(.+?)~~|\\*(?!\\s)(.+?)(?<!\\s)\\*|_(?!\\s)([^_]+?)(?<!\\s)_")

    fun parse(markdown: String): Doc {
        val text = StringBuilder()
        val paras = mutableListOf<Doc.Para>()
        val spans = mutableListOf<Doc.Span>()
        for (raw in markdown.replace("\r\n", "\n").trimEnd('\n').split('\n')) {
            var line = raw
            val kind = when {
                line.startsWith("### ") -> Kind.Heading3.also { line = line.drop(4) }
                line.startsWith("## ") -> Kind.Heading2.also { line = line.drop(3) }
                line.startsWith("# ") -> Kind.Heading1.also { line = line.drop(2) }
                Regex("^\\s*[-*•] ").containsMatchIn(line) -> Kind.Bullet.also { line = line.replaceFirst(Regex("^\\s*[-*•] "), "") }
                Regex("^\\s*\\d+[.)] ").containsMatchIn(line) -> Kind.Numbered.also { line = line.replaceFirst(Regex("^\\s*\\d+[.)] "), "") }
                line.startsWith("> ") -> Kind.Quote.also { line = line.drop(2) }
                else -> Kind.Normal
            }
            if (paras.isNotEmpty()) text.append('\n')
            paras += Doc.Para(kind)
            var at = 0
            for (m in Inline.findAll(line)) {
                text.append(line, at, m.range.first)
                val (inner, style) = when {
                    m.groups[1] != null -> m.groupValues[1] to Style.Bold
                    m.groups[2] != null -> m.groupValues[2] to Style.Underline
                    m.groups[3] != null -> m.groupValues[3] to Style.Strike
                    m.groups[4] != null -> m.groupValues[4] to Style.Italic
                    else -> m.groupValues[5] to Style.Italic
                }
                spans += Doc.Span(text.length, text.length + inner.length, style)
                text.append(inner)
                at = m.range.last + 1
            }
            text.append(line, at, line.length)
        }
        if (paras.isEmpty()) paras += Doc.Para()
        return Doc(text.toString(), paras, Doc.normalize(spans))
    }

    fun write(doc: Doc): String {
        var number = 0
        return doc.paras.indices.joinToString("\n") { i ->
            val para = doc.paras[i]
            number = if (para.kind == Kind.Numbered) number + 1 else 0
            val prefix = when (para.kind) {
                Kind.Title, Kind.Heading1 -> "# "
                Kind.Heading2 -> "## "
                Kind.Heading3 -> "### "
                Kind.Bullet -> "- "
                Kind.Numbered -> "$number. "
                Kind.Quote -> "> "
                Kind.Normal -> ""
            }
            val r = doc.range(i)
            prefix + DocFiles.pieces(doc, r.first, r.last + 1).joinToString("") { (s, e) ->
                val on = DocFiles.stylesOn(doc, s, e)
                var t = doc.text.substring(s, e)
                if (Style.Strike in on) t = "~~$t~~"
                if (Style.Underline in on) t = "__${t}__"
                if (Style.Italic in on) t = "*$t*"
                if (Style.Bold in on) t = "**$t**"
                t
            }
        }
    }
}
