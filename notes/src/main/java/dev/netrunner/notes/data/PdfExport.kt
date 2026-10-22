package dev.netrunner.notes.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notes as a plain A4 PDF: title, date line, then the text or the checklist, one note after another. */
object PdfExport {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48f

    fun write(context: Context, uri: Uri, notes: List<Note>): Boolean = runCatching {
        val doc = PdfDocument()
        val pager = Pager(doc)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val meta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.GRAY }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val date = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)
        notes.forEachIndexed { i, n ->
            if (i > 0) pager.rule()
            if (n.title.isNotBlank()) pager.text(n.title.trim(), title, after = 4f)
            val by = if (n.source == "aska") " · écrite par As//ka" else ""
            pager.text("Modifiée le ${date.format(Date(n.updated))}$by", meta, after = 10f)
            val text = if (n.checklist) n.items.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text } else n.body.trim()
            if (text.isNotEmpty()) pager.text(text, body, after = 6f)
        }
        pager.finish()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { doc.writeTo(it) }
        doc.close()
        true
    }.getOrDefault(false)

    /** Lays text out line by line, starting a new page when the current one is full. */
    private class Pager(private val doc: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var number = 0
        private var y = MARGIN
        private val width = (PAGE_W - 2 * MARGIN).toInt()
        private val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.7f }

        private fun canvas(): Canvas {
            page?.let { return it.canvas }
            number++
            return doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create()).also { page = it; y = MARGIN }.canvas
        }

        private fun ensure(height: Float) {
            if (page != null && y + height > PAGE_H - MARGIN) {
                doc.finishPage(page)
                page = null
            }
        }

        fun text(text: String, paint: TextPaint, after: Float) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            for (l in 0 until layout.lineCount) {
                val h = (layout.getLineBottom(l) - layout.getLineTop(l)).toFloat()
                ensure(h)
                val c = canvas()
                val start = layout.getLineStart(l)
                val end = layout.getLineEnd(l).let { e -> if (e > start && text[e - 1] == '\n') e - 1 else e }
                c.drawText(text, start, end, MARGIN, y + (layout.getLineBaseline(l) - layout.getLineTop(l)), paint)
                y += h
            }
            y += after
        }

        fun rule() {
            ensure(24f)
            val c = canvas()
            y += 10f
            c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
            y += 14f
        }

        fun finish() {
            canvas() // an empty export still gets one page
            doc.finishPage(page)
            page = null
        }
    }

    /** "notes-2026-10-22.pdf", or the note's title. */
    fun fileName(notes: List<Note>): String {
        val single = notes.singleOrNull()?.title?.trim()?.replace(Regex("""[\\/:*?"<>|]+"""), " ")?.take(60)
        return if (!single.isNullOrBlank()) "$single.pdf" else "notes-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.pdf"
    }
}
