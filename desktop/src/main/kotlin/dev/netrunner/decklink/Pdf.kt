package dev.netrunner.decklink

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PDF documents made from the kit: the report of an intervention (for the client, with its
 * photos and signature boxes) and sheets of QR labels for the inventory.
 */
object Pdf {
    private const val MM = 72f / 25.4f
    private val Ink = Color(0x1A, 0x1A, 0x1A)
    private val Grey = Color(0x6B, 0x6B, 0x6B)
    private val Line = Color(0xCC, 0xCC, 0xCC)
    private val Band = Color(0x0D, 0x0D, 0x12)
    private val Yellow = Color(0xFC, 0xEE, 0x0A)

    private val Day = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault())
    private val Time = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
    private val Hour = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private fun time(ms: Long) = if (ms <= 0) "" else Time.format(Instant.ofEpochMilli(ms))

    /** Fonts of one document: Helvetica for text, the app's display font for titles. */
    private class Fonts(doc: PDDocument) {
        val regular: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        val title: PDFont = runCatching {
            Pdf::class.java.getResourceAsStream("/font/rajdhani_bold.ttf")!!.use { PDType0Font.load(doc, it) }
        }.getOrDefault(bold)
    }

    /** [text] with what [font] cannot draw replaced (Helvetica only knows Western European letters). */
    private fun safe(font: PDFont, text: String): String = buildString {
        text.replace('\t', ' ').forEach { c ->
            val ok = c >= ' ' && runCatching { font.encode(c.toString()) }.isSuccess
            append(
                when {
                    ok -> c
                    c == '→' || c == '►' -> '>'
                    c == '✓' -> '+'
                    c == '✕' -> 'x'
                    c.isWhitespace() -> ' '
                    else -> '?'
                },
            )
        }
    }

    private fun width(font: PDFont, size: Float, text: String) = font.getStringWidth(safe(font, text)) / 1000f * size

    /** [text] cut into lines no wider than [max], on spaces when possible. */
    private fun wrap(font: PDFont, size: Float, text: String, max: Float): List<String> = text.lines().flatMap { para ->
        if (para.isBlank()) return@flatMap listOf("")
        val out = mutableListOf<String>()
        var line = ""
        para.split(' ').forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (width(font, size, candidate) <= max) line = candidate
            else {
                if (line.isNotEmpty()) out += line
                var w = word
                while (width(font, size, w) > max && w.length > 1) {
                    var cut = w.length - 1
                    while (cut > 1 && width(font, size, w.take(cut)) > max) cut--
                    out += w.take(cut)
                    w = w.drop(cut)
                }
                line = w
            }
        }
        if (line.isNotEmpty()) out += line
        out
    }

    /** Writes pages top to bottom, starting a new page when the next block does not fit. */
    private class Writer(val doc: PDDocument, val fonts: Fonts, val footer: String) {
        val page = PDRectangle.A4
        val margin = 18 * MM
        val left = margin
        val right = page.width - margin
        val bottom = margin + 10 * MM
        lateinit var cs: PDPageContentStream
        var y = 0f
        val pages = mutableListOf<PDPage>()

        fun newPage() {
            if (pages.isNotEmpty()) cs.close()
            val p = PDPage(page)
            doc.addPage(p)
            pages += p
            cs = PDPageContentStream(doc, p)
            y = page.height - margin
        }

        fun need(h: Float) { if (y - h < bottom) newPage() }

        fun text(t: String, font: PDFont, size: Float, x: Float, yy: Float, color: Color = Ink) {
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(color)
            cs.newLineAtOffset(x, yy)
            cs.showText(safe(font, t))
            cs.endText()
        }

        fun paragraph(t: String, font: PDFont = fonts.regular, size: Float = 10f, indent: Float = 0f, color: Color = Ink) {
            val lead = size * 1.35f
            wrap(font, size, t, right - left - indent).forEach { l ->
                need(lead)
                y -= lead
                text(l, font, size, left + indent, y + size * 0.3f, color)
            }
        }

        /** A section title, kept on the same page as the first [keep] points of its content. */
        fun heading(t: String, keep: Float = 20f) {
            need(30f + keep)
            y -= 16f
            text(t.uppercase(), fonts.bold, 10.5f, left, y)
            y -= 5f
            cs.setStrokingColor(Yellow)
            cs.setLineWidth(1.5f)
            cs.moveTo(left, y); cs.lineTo(left + 40f, y); cs.stroke()
            y -= 4f
        }

        /** Label / value rows, two columns. */
        fun facts(rows: List<Pair<String, String>>) {
            rows.filter { it.second.isNotBlank() }.forEach { (k, v) ->
                val lines = wrap(fonts.regular, 10f, v, right - left - 110f)
                val h = lines.size * 13.5f + 3f
                need(h)
                y -= 13.5f
                text(k, fonts.bold, 9f, left, y + 3f, Grey)
                lines.forEachIndexed { i, l -> text(l, fonts.regular, 10f, left + 110f, y + 3f - i * 13.5f) }
                y -= (lines.size - 1) * 13.5f + 3f
            }
        }

        fun finish() {
            if (pages.isNotEmpty()) cs.close()
            pages.forEachIndexed { i, p ->
                PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true).use { c ->
                    c.setStrokingColor(Line); c.setLineWidth(0.5f)
                    c.moveTo(left, margin + 4 * MM); c.lineTo(right, margin + 4 * MM); c.stroke()
                    fun at(t: String, x: Float) {
                        c.beginText(); c.setFont(fonts.regular, 8f); c.setNonStrokingColor(Grey); c.newLineAtOffset(x, margin); c.showText(safe(fonts.regular, t)); c.endText()
                    }
                    at(footer, left)
                    val n = "${i + 1} / ${pages.size}"
                    at(n, right - width(fonts.regular, 8f, n))
                }
            }
        }
    }

    // ------------------------------------------------------------------ intervention report

    /** The report of [i]: client, dates, work done, journal, material used, photos, signatures. */
    fun interventionReport(i: KitData.Intervention, kit: KitData, photosDir: File, out: File) {
        PDDocument().use { doc ->
            val fonts = Fonts(doc)
            val w = Writer(doc, fonts, "Rapport d'intervention · généré le ${time(System.currentTimeMillis())}")
            w.newPage()
            // Header band.
            val bandH = 26 * MM
            w.cs.setNonStrokingColor(Band)
            w.cs.addRect(0f, w.page.height - bandH, w.page.width, bandH); w.cs.fill()
            w.cs.setNonStrokingColor(Yellow)
            w.cs.addRect(0f, w.page.height - bandH, w.page.width, 1.2f * MM); w.cs.fill()
            w.text("RAPPORT D'INTERVENTION", fonts.title, 22f, w.left, w.page.height - 13 * MM, Yellow)
            val ref = listOf(if (i.ticket.isBlank()) "" else "Ticket ${i.ticket}", Day.format(Instant.ofEpochMilli(i.started.coerceAtLeast(0)))).filter { it.isNotBlank() }.joinToString("  ·  ")
            w.text(ref, fonts.regular, 10f, w.left, w.page.height - 20 * MM, Color.WHITE)
            w.y = w.page.height - bandH - 8 * MM

            w.paragraph(i.title.ifBlank { "Intervention" }, fonts.bold, 15f)
            w.y -= 4f
            val duration = if (i.open || i.started <= 0) "" else {
                val min = (i.ended - i.started) / 60_000
                "${min / 60} h ${"%02d".format(min % 60)}"
            }
            w.facts(
                listOf(
                    "Client" to i.client, "Site" to i.site, "Ticket" to i.ticket,
                    "Début" to time(i.started), "Fin" to if (i.open) "en cours" else time(i.ended), "Durée" to duration,
                ),
            )
            kit.clients.firstOrNull { it.name.equals(i.client.trim(), ignoreCase = true) && i.client.isNotBlank() }?.let { c ->
                w.heading("Coordonnées du client")
                w.facts(listOf("Contact" to c.contact, "Téléphone" to c.phone, "E-mail" to c.email, "Adresse" to c.address))
            }
            if (i.notes.isNotBlank()) {
                w.heading("Travaux réalisés")
                w.paragraph(i.notes)
            }
            if (i.journal.isNotEmpty()) {
                w.heading("Déroulé")
                val sameDay = i.journal.all { Day.format(Instant.ofEpochMilli(it.at)) == Day.format(Instant.ofEpochMilli(i.journal.first().at)) }
                i.journal.sortedBy { it.at }.forEach { e ->
                    val stamp = if (sameDay) Hour.format(Instant.ofEpochMilli(e.at)) else time(e.at)
                    val lines = wrap(fonts.regular, 10f, e.text, w.right - w.left - 80f)
                    w.need(lines.size * 13.5f + 2f)
                    w.y -= 13.5f
                    w.text(stamp, fonts.bold, 9f, w.left, w.y + 3f, Grey)
                    lines.forEachIndexed { k, l -> w.text(l, fonts.regular, 10f, w.left + 80f, w.y + 3f - k * 13.5f) }
                    w.y -= (lines.size - 1) * 13.5f + 2f
                }
            }
            if (i.consumed.isNotEmpty()) {
                w.heading("Matériel posé / consommé")
                i.consumed.forEach { u ->
                    w.need(15f)
                    w.y -= 14f
                    w.text("${u.qty} ${u.unit}", fonts.bold, 10f, w.left, w.y + 3f)
                    w.text(u.name, fonts.regular, 10f, w.left + 80f, w.y + 3f)
                }
            }
            val photos = i.photos.mapNotNull { name ->
                val f = File(photosDir, name)
                if (!f.isFile) return@mapNotNull null
                runCatching { PDImageXObject.createFromFileByContent(f, doc) }.getOrNull()
            }
            if (photos.isNotEmpty()) {
                val gap = 6 * MM
                val cellW = (w.right - w.left - gap) / 2
                fun heights(row: List<PDImageXObject>) = row.map { img -> (cellW * img.height / img.width).coerceAtMost(75 * MM) }
                w.heading("Photos (${photos.size})", keep = heights(photos.take(2)).max())
                photos.chunked(2).forEach { row ->
                    val heights = heights(row)
                    val h = heights.max()
                    w.need(h + gap)
                    w.y -= h
                    row.forEachIndexed { k, img ->
                        val ih = heights[k]
                        val iw = ih * img.width / img.height
                        w.cs.drawImage(img, w.left + k * (cellW + gap) + (cellW - iw) / 2, w.y + (h - ih), iw, ih)
                    }
                    w.y -= gap
                }
            }
            // Signatures.
            val boxH = 32 * MM
            w.need(boxH + 30f)
            w.heading("Signatures")
            w.y -= 6f
            val boxW = (w.right - w.left - 10 * MM) / 2
            listOf("Le technicien", "Le client (nom, date)").forEachIndexed { k, label ->
                val x = w.left + k * (boxW + 10 * MM)
                w.text(label, fonts.bold, 9f, x, w.y - 10f, Grey)
                w.cs.setStrokingColor(Line); w.cs.setLineWidth(0.8f)
                w.cs.addRect(x, w.y - boxH, boxW, boxH - 16f); w.cs.stroke()
            }
            w.y -= boxH
            w.finish()
            doc.save(out)
        }
    }

    // ------------------------------------------------------------------ QR labels

    /** Label sheet layout: A4, 3 × 8 labels of 70 × 37 mm (Avery L7159 / 3475 and the like). */
    const val LABEL_COLS = 3
    const val LABEL_ROWS = 8

    data class Label(val code: String, val title: String, val detail: String)

    /** One label per item, [skip] cells left blank first (to reuse a half-used sheet). */
    fun labels(items: List<Label>, out: File, skip: Int = 0) {
        PDDocument().use { doc ->
            val fonts = Fonts(doc)
            val page = PDRectangle.A4
            val cellW = page.width / LABEL_COLS
            val cellH = page.height / LABEL_ROWS
            val perPage = LABEL_COLS * LABEL_ROWS
            val cells = List(skip.coerceIn(0, perPage - 1)) { null } + items
            cells.chunked(perPage).forEach { chunk ->
                val p = PDPage(page)
                doc.addPage(p)
                PDPageContentStream(doc, p).use { cs ->
                    chunk.forEachIndexed { n, label ->
                        if (label == null) return@forEachIndexed
                        val col = n % LABEL_COLS
                        val row = n / LABEL_COLS
                        val x = col * cellW
                        val y = page.height - (row + 1) * cellH
                        val pad = 4 * MM
                        val qrSize = cellH - 2 * pad
                        cs.drawImage(LosslessFactory.createFromImage(doc, qr(label.code)), x + pad, y + pad, qrSize, qrSize)
                        val tx = x + pad + qrSize + 3 * MM
                        val tw = cellW - (tx - x) - pad
                        fun fit(t: String, font: PDFont, size: Float): String {
                            val full = safe(font, t)
                            if (width(font, size, full) <= tw) return full
                            var s = full
                            while (s.isNotEmpty() && width(font, size, "$s...") > tw) s = s.dropLast(1)
                            return "$s..."
                        }
                        var ty = y + cellH - pad - 9f
                        fun line(t: String, font: PDFont, size: Float, color: Color) {
                            if (t.isBlank()) return
                            cs.beginText(); cs.setFont(font, size); cs.setNonStrokingColor(color); cs.newLineAtOffset(tx, ty); cs.showText(fit(t, font, size)); cs.endText()
                            ty -= size * 1.3f
                        }
                        line(label.code, fonts.bold, 10f, Ink)
                        wrap(fonts.regular, 8.5f, label.title, tw).take(3).forEach { line(it, fonts.regular, 8.5f, Ink) }
                        wrap(fonts.regular, 7.5f, label.detail, tw).take(2).forEach { line(it, fonts.regular, 7.5f, Grey) }
                    }
                }
            }
            doc.save(out)
        }
    }

    /** Black on white, with a quiet zone, as phone scanners read best. */
    private fun qr(text: String): BufferedImage {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        val scale = 8
        val img = BufferedImage(m.width * scale, m.height * scale, BufferedImage.TYPE_BYTE_BINARY)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
        g.color = Color.BLACK
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) g.fillRect(x * scale, y * scale, scale, scale)
        g.dispose()
        return img
    }
}
