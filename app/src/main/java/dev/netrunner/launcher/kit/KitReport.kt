package dev.netrunner.launcher.kit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Intervention report as a PDF: the checklist, the site's cable survey, equipment and addressing
 * plan, the technician's notes and photos. Built on the phone, shared only when the user asks.
 */
object KitReport {
    private const val W = 595 // A4 at 72 dpi
    private const val H = 842
    private const val M = 40

    private class Writer(private val doc: PdfDocument) {
        private var pageNo = 0
        private lateinit var page: PdfDocument.Page
        private lateinit var canvas: Canvas
        private var y = 0f

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val h2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(20, 90, 120) }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
        val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; typeface = Typeface.MONOSPACE }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.GRAY }

        init { newPage() }

        private fun newPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page.canvas
            canvas.drawText("Netrunner · rapport d'intervention · page $pageNo", M.toFloat(), (H - 20).toFloat(), dim)
            y = M.toFloat()
        }

        private fun room(height: Float) {
            if (y + height > H - M) newPage()
        }

        /** Draws [text] wrapped to the page width. */
        fun text(text: String, paint: Paint = body, gapAfter: Float = 3f) {
            text.lines().forEach { raw ->
                var line = raw
                do {
                    val n = paint.breakText(line, true, (W - 2 * M).toFloat(), null).coerceAtLeast(1)
                    // Break on a space when the line is cut in the middle of a word.
                    val cut = if (n < line.length) line.lastIndexOf(' ', n).takeIf { it > n / 2 } ?: n else n
                    room(paint.textSize + 2)
                    y += paint.textSize + 2
                    canvas.drawText(line.substring(0, cut), M.toFloat(), y, paint)
                    line = line.substring(cut).trimStart()
                } while (line.isNotEmpty())
            }
            y += gapAfter
        }

        fun section(name: String) {
            room(40f)
            y += 10f
            text(name, h2, 2f)
            canvas.drawLine(M.toFloat(), y, (W - M).toFloat(), y, h2)
            y += 6f
        }

        fun image(bitmap: Bitmap) {
            val maxW = (W - 2 * M).toFloat()
            val maxH = 320f
            val scale = minOf(maxW / bitmap.width, maxH / bitmap.height, 1f)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            room(h + 8)
            canvas.drawBitmap(bitmap, null, Rect(M, y.toInt(), (M + w).toInt(), (y + h).toInt()), null)
            y += h + 8
        }

        fun finish() = doc.finishPage(page)
    }

    /** Writes the report for [run] and returns the file, or null on failure. */
    fun build(context: Context, run: ChecklistRun, notes: String, photos: List<Uri>): File? = runCatching {
        NetPlan.load(context)
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        val doc = PdfDocument()
        val w = Writer(doc)
        w.text("Rapport d'intervention", w.title, 6f)
        w.text(run.name)
        if (run.site.isNotBlank()) w.text("Site : ${run.site}")
        w.text("Démarrée le ${fmt.format(Instant.ofEpochMilli(run.started))} · rapport du ${fmt.format(Instant.now())}")

        w.section("Checklist · ${run.done.size}/${run.steps.size}")
        run.steps.forEachIndexed { i, step -> w.text((if (i in run.done) "[x] " else "[ ] ") + "${i + 1}. $step", gapAfter = 1f) }

        siteSections(w, run.site)

        if (notes.isNotBlank()) {
            w.section("Notes")
            w.text(notes)
        }
        if (photos.isNotEmpty()) {
            w.section("Photos")
            photos.forEach { uri ->
                decode(context, uri)?.let { w.image(it); it.recycle() }
            }
        }
        w.finish()
        write(context, doc, run.site.ifBlank { run.name })
    }.getOrNull()

    /** Report of a logbook intervention: header, journal, its checklists, the site, notes and photos. */
    fun build(context: Context, i: Intervention): File? = runCatching {
        NetPlan.load(context)
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        val hour = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val doc = PdfDocument()
        val w = Writer(doc)
        w.text("Rapport d'intervention", w.title, 6f)
        w.text(i.title.ifBlank { "Intervention" })
        if (i.site.isNotBlank()) w.text("Site : ${i.site}")
        if (i.client.isNotBlank()) w.text("Client : ${i.client}")
        if (i.ticket.isNotBlank()) w.text("Ticket : ${i.ticket}")
        val end = if (i.open) "en cours" else "fin ${fmt.format(Instant.ofEpochMilli(i.ended))}"
        val minutes = ((if (i.open) System.currentTimeMillis() else i.ended) - i.started) / 60000
        w.text("Début ${fmt.format(Instant.ofEpochMilli(i.started))} · $end · durée ${minutes / 60} h ${"%02d".format(minutes % 60)}")
        w.text("Rapport du ${fmt.format(Instant.now())}", w.dim)

        if (i.journal.isNotEmpty()) {
            w.section("Journal")
            i.journal.sortedBy { it.at }.forEach { e -> w.text("${hour.format(Instant.ofEpochMilli(e.at))}  ${e.text}", gapAfter = 1f) }
        }
        Logbook.runsOf(i).forEach { run ->
            w.section("Checklist ${run.name} · ${run.done.size}/${run.steps.size}")
            run.steps.forEachIndexed { k, step -> w.text((if (k in run.done) "[x] " else "[ ] ") + "${k + 1}. $step", gapAfter = 1f) }
        }
        if (i.consumed.isNotEmpty()) {
            w.section("Consommables")
            i.consumed.forEach { w.text("${it.qty} ${it.unit}  ${it.name}", w.mono, 1f) }
        }
        siteSections(w, i.site)
        if (i.notes.isNotBlank()) {
            w.section("Notes")
            w.text(i.notes)
        }
        if (i.photos.isNotEmpty()) {
            w.section("Photos · ${i.photos.size}")
            i.photos.forEach { name ->
                Logbook.decode(context, Uri.fromFile(Logbook.photo(context, i.id, name)), 1000)?.let { w.image(it); it.recycle() }
            }
        }
        w.finish()
        write(context, doc, listOf(i.site, i.title).firstOrNull { it.isNotBlank() } ?: i.id)
    }.getOrNull()

    private fun write(context: Context, doc: PdfDocument, label: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val file = File(dir, "rapport-$safe.pdf")
        try {
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return file
    }

    /** Cables, equipment and addressing plan recorded for [siteName]. */
    private fun siteSections(w: Writer, siteName: String) {
        val site = siteName.trim()
        if (site.isEmpty()) return
        val cables = KitStore.cables.filter { it.site.equals(site, ignoreCase = true) }
        if (cables.isNotEmpty()) {
            w.section("Relevé des câbles · ${cables.size}")
            cables.forEach { c ->
                val e = KitStore.equipment.firstOrNull { it.id == c.equipmentId }?.let { " · ${it.name.ifBlank { it.code }}" }.orEmpty()
                w.text("${c.outlet.ifBlank { "?" }} → ${c.switchName} ${c.port} · VLAN ${c.vlan.ifBlank { "-" }} · ${c.lengthM.ifBlank { "?" }} m · ${c.poe} · ${c.test}$e", w.mono, 1f)
            }
        }
        val equipment = KitStore.equipment.filter { it.location.contains(site, ignoreCase = true) }
        if (equipment.isNotEmpty()) {
            w.section("Équipements · ${equipment.size}")
            equipment.forEach { e ->
                w.text("${e.name.ifBlank { "?" }} · ${e.code} · S/N ${e.serial.ifBlank { "-" }} · ${e.location} · ${e.state}", w.mono, 1f)
            }
        }
        val plans = NetPlan.plans.filter { it.site.equals(site, ignoreCase = true) }
        if (plans.isNotEmpty()) {
            w.section("Plan d'adressage")
            plans.sortedBy { it.vlan.toIntOrNull() ?: 0 }.forEach { p ->
                w.text("VLAN ${p.vlan} ${p.name} · ${p.subnet} · passerelle ${p.gateway.ifBlank { "-" }}", w.body, 1f)
                NetPlan.assignmentsOf(p.id).forEach { a -> w.text("    ${a.ip}  ${a.host}", w.mono, 1f) }
            }
        }
    }

    /** Photos are downscaled while decoding so a dozen of them stay light in memory and in the PDF. */
    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1000) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Partager le rapport").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
