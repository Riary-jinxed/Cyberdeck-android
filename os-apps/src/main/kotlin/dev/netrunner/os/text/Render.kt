package dev.netrunner.os.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.netrunner.os.text.Doc.Align
import dev.netrunner.os.text.Doc.Kind
import dev.netrunner.os.text.Doc.Style

/**
 * Paints a [Doc] over its plain text: sizes of the headings, bold and the rest, alignment, and
 * the bullets and numbers of the lists (added in front, hence the offset mapping).
 */
class Render(private val doc: Doc) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // The text field may run one change ahead of the document: plain until they agree.
        if (text.text != doc.text) return TransformedText(text, OffsetMapping.Identity)
        val prefixes = prefixes(doc)
        val out = AnnotatedString.Builder()
        val starts = IntArray(doc.paras.size)
        var shift = 0
        for (i in doc.paras.indices) {
            val r = doc.range(i)
            val para = doc.paras[i]
            val begin = out.length
            starts[i] = shift
            if (prefixes[i].isNotEmpty()) {
                out.withStyle(SpanStyle(color = Color(0xFF6A6A80))) { append(prefixes[i]) }
                shift += prefixes[i].length
            }
            out.append(doc.text.substring(r.first, r.last + 1))
            kindStyle(para.kind)?.let { out.addStyle(it, begin, out.length) }
            if (out.length > begin) out.addStyle(paragraphStyle(para), begin, out.length)
            if (i < doc.paras.size - 1) out.append('\n')
        }
        for (s in doc.spans) {
            val a = s.start + starts[doc.paragraphAt(s.start)] + prefixes[doc.paragraphAt(s.start)].length
            val b = s.end + starts[doc.paragraphAt(s.end)] + prefixes[doc.paragraphAt(s.end)].length
            out.addStyle(spanStyle(s.style), a, b)
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val i = doc.paragraphAt(offset)
                return offset + starts[i] + prefixes[i].length
            }

            override fun transformedToOriginal(offset: Int): Int {
                // The last paragraph starting at or before [offset] on screen; inside a bullet means its start.
                var lo = 0
                var hi = doc.paras.size - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (doc.range(mid).first + starts[mid] <= offset) lo = mid else hi = mid - 1
                }
                val r = doc.range(lo)
                return (offset - starts[lo] - prefixes[lo].length).coerceIn(r.first, r.last + 1)
            }
        }
        return TransformedText(out.toAnnotatedString(), mapping)
    }

    companion object {
        fun prefixes(doc: Doc): List<String> {
            var n = 0
            return doc.paras.map { p ->
                n = if (p.kind == Kind.Numbered) n + 1 else 0
                when (p.kind) {
                    Kind.Bullet -> "•  "
                    Kind.Numbered -> "$n.  "
                    else -> ""
                }
            }
        }

        fun kindStyle(kind: Kind): SpanStyle? = when (kind) {
            Kind.Title -> SpanStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Kind.Heading1 -> SpanStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B3A6B))
            Kind.Heading2 -> SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B3A6B))
            Kind.Heading3 -> SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Kind.Quote -> SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF555566))
            else -> null
        }

        fun spanStyle(style: Style): SpanStyle = when (style) {
            Style.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            Style.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            Style.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
            Style.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        }

        fun paragraphStyle(p: Doc.Para) = ParagraphStyle(
            textAlign = when (p.align) {
                Align.Start -> TextAlign.Start
                Align.Center -> TextAlign.Center
                Align.End -> TextAlign.End
                Align.Justify -> TextAlign.Justify
            },
            textIndent = when {
                p.kind.list -> TextIndent(firstLine = 1.em, restLine = 2.4.em)
                p.kind == Kind.Quote -> TextIndent(firstLine = 2.em, restLine = 2.em)
                else -> null
            },
            lineHeight = 1.45.em,
        )
    }
}
