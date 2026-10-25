package dev.netrunner.os.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * One open PDF for Netrunner PDF: its pages' sizes, their pictures at the width shown, and
 * their text (for the search and for As//ka). PDFBox is not thread-safe: everything goes
 * through [worker], one task at a time.
 */
class Pdf private constructor(val file: File, private val doc: PDDocument) {
    val pages: Int = doc.numberOfPages
    /** Width / height of each page, as drawn (rotation included). */
    val ratios: List<Float> = (0 until pages).map { i ->
        val p = doc.getPage(i)
        val box = p.cropBox
        if (p.rotation % 180 == 0) box.width / box.height else box.height / box.width
    }
    val title: String = doc.documentInformation?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
    val author: String = doc.documentInformation?.author.orEmpty()

    private val renderer = PDFRenderer(doc)
    private var texts: List<String>? = null
    private val cache = object : LinkedHashMap<Pair<Int, Int>, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, ImageBitmap>?) = size > 24
    }

    /** Page [index] (from 0), [widthPx] pixels wide. Call on [worker]. */
    fun render(index: Int, widthPx: Int): ImageBitmap {
        val key = index to widthPx
        cache[key]?.let { return it }
        val pageWidthPt = doc.getPage(index).let { if (it.rotation % 180 == 0) it.cropBox.width else it.cropBox.height }
        val dpi = (widthPx / pageWidthPt * 72f).coerceIn(10f, 600f)
        return renderer.renderImageWithDPI(index, dpi, ImageType.RGB).toComposeImageBitmap().also { cache[key] = it }
    }

    /** The text of every page, extracted once. Call on [worker]. */
    fun texts(): List<String> = texts ?: (0 until pages).map { i ->
        runCatching { PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(doc) }.getOrDefault("")
    }.also { texts = it }

    /** Pages [from]..[to] (from 1) as text, at most [max] characters. Call on [worker]. */
    fun read(from: Int, to: Int, max: Int = 12_000): String {
        val a = from.coerceIn(1, pages)
        val b = to.coerceIn(a, pages)
        val text = (a..b).joinToString("\n\n") { "— Page $it —\n" + texts()[it - 1].trim() }
        return if (text.length > max) text.take(max) + "\n[… coupé : demande moins de pages]" else text
    }

    /** Pages (from 1) holding [query], ignoring case and accents, with a few words around. Call on [worker]. */
    fun search(query: String): List<Pair<Int, String>> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        return texts().mapIndexedNotNull { i, t ->
            val flat = t.replace(Regex("\\s+"), " ")
            val at = fold(flat).indexOf(q)
            if (at < 0) null else (i + 1) to flat.substring(maxOf(0, at - 40), minOf(flat.length, at + q.length + 60)).trim()
        }
    }

    fun close() = doc.close()

    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        val worker = Dispatchers.IO.limitedParallelism(1)

        /** Opens [file]; a damaged or password-protected file gives an error message instead. */
        fun open(file: File): Result<Pdf> = runCatching { Pdf(file, Loader.loadPDF(file)) }
            .recoverCatching { e ->
                throw IllegalStateException(
                    if (e is org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) "${file.name} est protégé par un mot de passe."
                    else "${file.name} n'est pas un PDF lisible (${e.message ?: e.javaClass.simpleName}).",
                )
            }

        fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    }
}
