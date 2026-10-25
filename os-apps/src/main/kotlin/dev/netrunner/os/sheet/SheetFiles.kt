package dev.netrunner.os.sheet

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * Workbooks on disk: Excel (.xlsx, .xls read), OpenDocument (.ods, written and read here) and
 * CSV. Inputs keep what the user would type: formulas with French names ("=SOMME(A1:A3)").
 */
object SheetFiles {
    val Extensions = listOf("xlsx", "xls", "ods", "csv")
    val Writable = listOf("xlsx", "ods", "csv")

    /** Where a file is saved: as it is, or as .xlsx when its kind cannot be written. */
    fun target(f: File): File = when (f.extension.lowercase()) {
        in Writable -> f
        in Extensions -> File(f.parentFile, f.nameWithoutExtension + ".xlsx")
        else -> File(f.path + ".xlsx")
    }

    class Loaded(val book: Book, val lost: Set<String>)

    fun read(file: File): Loaded = when (file.extension.lowercase()) {
        "ods" -> readOds(file)
        "csv", "tsv", "txt" -> Loaded(Book(listOf(readCsv(file.readText(), file.nameWithoutExtension))), emptySet())
        else -> readExcel(file)
    }

    fun write(book: Book, file: File) {
        val bytes = when (file.extension.lowercase()) {
            "ods" -> writeOds(book)
            "csv" -> writeCsv(book.sheets.first()).toByteArray(Charsets.UTF_8)
            else -> ByteArrayOutputStream().also { out -> writeXlsx(book).use { it.write(out) } }.toByteArray()
        }
        // Written next to it, then moved: a failure halfway never leaves half a file.
        val tmp = File(file.parentFile ?: File("."), ".${file.name}.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.writeBytes(bytes); tmp.delete() }
    }

    // ---- Excel.

    private fun readExcel(file: File): Loaded {
        val lost = mutableSetOf<String>()
        val sheets = WorkbookFactory.create(file, null, true).use { wb ->
            (0 until wb.numberOfSheets).map { i ->
                val s = wb.getSheetAt(i)
                val cells = HashMap<Ref, String>()
                val bold = HashSet<Ref>()
                for (row in s) for (cell in row) {
                    val ref = Ref(cell.columnIndex, cell.rowIndex)
                    val input = when (cell.cellType) {
                        CellType.FORMULA -> FormulaText.fromExcel(cell.cellFormula).also { f ->
                            if (runCatching { Formula.parse(f.substring(1)) }.isFailure) lost += "certaines formules"
                        }
                        CellType.NUMERIC -> if (DateUtil.isCellDateFormatted(cell)) cell.localDateTimeCellValue.toLocalDate().let(::date) else Value.format(cell.numericCellValue)
                        CellType.BOOLEAN -> if (cell.booleanCellValue) "VRAI" else "FAUX"
                        CellType.STRING -> cell.stringCellValue.let { if (Input.number(it) != null || it.startsWith("=")) "'$it" else it }
                        else -> ""
                    }
                    if (input.isNotEmpty()) cells[ref] = input
                    if (wb.getFontAt(cell.cellStyle.fontIndex).bold && input.isNotEmpty()) bold += ref
                }
                if (s.numMergedRegions > 0) lost += "les cellules fusionnées"
                if (wb is XSSFWorkbook && wb.getSheetAt(i).let { it.drawingPatriarch?.charts?.isNotEmpty() == true }) lost += "les graphiques"
                val cols = (cells.keys.maxOfOrNull { it.col } ?: -1) + 1
                val widths = (0 until cols).mapNotNull { c -> if (s.isColumnHidden(c)) null else c to (s.getColumnWidth(c) / 256 * 8).coerceIn(40, 600) }
                    .filter { (_, w) -> w != 8 * 8 }.toMap()
                Sheet(wb.getSheetName(i), cells, bold, widths)
            }
        }
        return Loaded(Book(sheets.ifEmpty { listOf(Sheet()) }), lost)
    }

    private fun date(d: LocalDate) = "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year)

    private fun writeXlsx(book: Book): XSSFWorkbook {
        val wb = XSSFWorkbook()
        val boldStyle = wb.createCellStyle().apply { setFont(wb.createFont().apply { bold = true }) }
        for (sheet in book.sheets) {
            val s = wb.createSheet(sheet.name.ifBlank { "Feuille" }.take(31).replace(Regex("[\\\\/?*\\[\\]:]"), "_"))
            val calc = Calc(sheet)
            for ((ref, input) in sheet.cells.entries.sortedWith(compareBy({ it.key.row }, { it.key.col }))) {
                val cell = (s.getRow(ref.row) ?: s.createRow(ref.row)).createCell(ref.col)
                when {
                    Input.isFormula(input) -> {
                        runCatching { cell.cellFormula = FormulaText.toExcel(input) }.onFailure { cell.setCellValue(input) }
                        // The value as we computed it, for readers that do not compute.
                        if (cell.cellType == CellType.FORMULA) when (val v = calc.value(ref)) {
                            is Value.Num -> cell.setCellValue(v.v)
                            is Value.Bool -> cell.setCellValue(v.v)
                            is Value.Text -> cell.setCellValue(v.v)
                            else -> {}
                        }
                    }
                    Input.number(input) != null -> cell.setCellValue(Input.number(input)!!)
                    else -> cell.setCellValue(input.removePrefix("'"))
                }
                if (ref in sheet.bold) cell.cellStyle = boldStyle
            }
            for ((c, w) in sheet.widths) s.setColumnWidth(c, (w / 8 * 256).coerceIn(256, 255 * 256))
        }
        return wb
    }

    // ---- CSV (";" by default, as French spreadsheets write it).

    fun readCsv(text: String, name: String): Sheet {
        val first = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> first.count { it == c } } ?: ';'
        val cells = HashMap<Ref, String>()
        var row = 0
        var col = 0
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        fun end() {
            if (cur.isNotEmpty()) cells[Ref(col, row)] = cur.toString()
            cur.clear()
        }
        val t = text.removePrefix("﻿")
        while (i < t.length) {
            val c = t[i]
            when {
                quoted && c == '"' && t.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' && (quoted || cur.isEmpty()) -> quoted = !quoted
                quoted -> cur.append(c)
                c == sep -> { end(); col++ }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && t.getOrNull(i + 1) == '\n') i++
                    end(); col = 0; row++
                }
                else -> cur.append(c)
            }
            i++
        }
        end()
        return Sheet(name.ifBlank { "Feuille 1" }, cells)
    }

    /** Values, not formulas: CSV readers would not compute them. */
    fun writeCsv(sheet: Sheet): String {
        val calc = Calc(sheet)
        return (0 until sheet.rows).joinToString("\r\n", postfix = "\r\n") { r ->
            (0 until sheet.cols).joinToString(";") { c ->
                val v = if (sheet.cells.containsKey(Ref(c, r))) calc.shown(Ref(c, r)) else ""
                if (v.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
            }
        }
    }

    // ---- OpenDocument.

    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun writeOds(book: Book): ByteArray {
        val body = StringBuilder()
        val widths = sortedSetOf<Int>()
        for (sheet in book.sheets) {
            val calc = Calc(sheet)
            body.append("<table:table table:name=\"${esc(sheet.name)}\">")
            for (c in 0 until maxOf(1, sheet.cols)) {
                val w = sheet.width(c)
                widths += w
                body.append("<table:table-column table:style-name=\"co$w\"/>")
            }
            for (r in 0 until sheet.rows) {
                body.append("<table:table-row>")
                for (c in 0 until sheet.cols) {
                    val ref = Ref(c, r)
                    val input = sheet.cells[ref]
                    val style = if (ref in sheet.bold) " table:style-name=\"ceBold\"" else ""
                    if (input == null) { body.append("<table:table-cell$style/>"); continue }
                    val v = calc.value(ref)
                    val formula = if (Input.isFormula(input)) " table:formula=\"${esc(FormulaText.toOdf(input))}\"" else ""
                    val typed = when {
                        v is Value.Num -> " office:value-type=\"float\" office:value=\"${v.v}\""
                        v is Value.Bool -> " office:value-type=\"boolean\" office:boolean-value=\"${v.v}\""
                        else -> " office:value-type=\"string\""
                    }
                    body.append("<table:table-cell$style$formula$typed><text:p>${esc(v.text())}</text:p></table:table-cell>")
                }
                body.append("</table:table-row>")
            }
            body.append("</table:table>")
        }
        val auto = StringBuilder("<style:style style:name=\"ceBold\" style:family=\"table-cell\"><style:text-properties fo:font-weight=\"bold\"/></style:style>")
        for (w in widths) auto.append("<style:style style:name=\"co$w\" style:family=\"table-column\"><style:table-column-properties style:column-width=\"${"%.2f".format(java.util.Locale.ROOT, w / 96.0 * 2.54)}cm\"/></style:style>")
        val ns = "xmlns:office=\"$OFFICE\" xmlns:style=\"$STYLE\" xmlns:text=\"$TEXT\" xmlns:table=\"$TABLE\" xmlns:fo=\"$FO\" xmlns:of=\"urn:oasis:names:tc:opendocument:xmlns:of:1.2\""
        val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $ns office:version=\"1.3\">" +
            "<office:automatic-styles>$auto</office:automatic-styles><office:body><office:spreadsheet>$body</office:spreadsheet></office:body></office:document-content>"
        val manifest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">" +
            "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.spreadsheet\"/>" +
            "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/></manifest:manifest>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // The mimetype comes first, uncompressed: that is how readers recognise the format.
            val mime = "application/vnd.oasis.opendocument.spreadsheet".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            for ((name, xml) in listOf("content.xml" to content, "META-INF/manifest.xml" to manifest)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readOds(file: File): Loaded {
        val lost = mutableSetOf<String>()
        val xml = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("content.xml") ?: error("pas de content.xml")).use { it.readBytes() } }
        val dom = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(xml.inputStream())
        // Bold cell styles and column widths, from the automatic styles.
        val boldStyles = HashSet<String>()
        val colWidths = HashMap<String, Int>()
        val styles = dom.getElementsByTagNameNS(STYLE, "style")
        for (i in 0 until styles.length) {
            val st = styles.item(i) as Element
            val name = st.getAttributeNS(STYLE, "name")
            val text = st.getElementsByTagNameNS(STYLE, "text-properties").item(0) as Element?
            if (text?.getAttributeNS(FO, "font-weight") == "bold") boldStyles += name
            val col = st.getElementsByTagNameNS(STYLE, "table-column-properties").item(0) as Element?
            col?.getAttributeNS(STYLE, "column-width")?.let(::pixels)?.let { colWidths[name] = it }
        }
        val tables = dom.getElementsByTagNameNS(TABLE, "table")
        val sheets = (0 until tables.length).map { t ->
            val table = tables.item(t) as Element
            val cells = HashMap<Ref, String>()
            val bold = HashSet<Ref>()
            val widths = HashMap<Int, Int>()
            var row = 0
            var col = 0
            // Rows and columns can sit in groups and header blocks.
            val wrappers = setOf("table-row-group", "table-column-group", "table-rows", "table-columns", "table-header-rows", "table-header-columns")
            fun children(e: Element, name: String): List<Element> = (0 until e.childNodes.length).map { e.childNodes.item(it) }
                .filterIsInstance<Element>().filter { it.namespaceURI == TABLE }
                .flatMap { if (it.localName == name) listOf(it) else if (it.localName in wrappers) children(it, name) else emptyList() }
            for (c in children(table, "table-column")) {
                val n = c.getAttributeNS(TABLE, "number-columns-repeated").toIntOrNull() ?: 1
                val w = colWidths[c.getAttributeNS(TABLE, "style-name")]
                repeat(minOf(n, 1024)) { if (w != null && w != Sheet.DefaultWidth) widths[col + it] = w }
                col += n
            }
            for (r in children(table, "table-row")) {
                val rowRepeat = r.getAttributeNS(TABLE, "number-rows-repeated").toIntOrNull() ?: 1
                col = 0
                val rowCells = mutableListOf<Pair<Int, Element>>()
                for (c in (0 until r.childNodes.length).map { r.childNodes.item(it) }.filterIsInstance<Element>()) {
                    if (c.namespaceURI != TABLE || (c.localName != "table-cell" && c.localName != "covered-table-cell")) continue
                    val n = c.getAttributeNS(TABLE, "number-columns-repeated").toIntOrNull() ?: 1
                    if (c.localName == "table-cell" && c.hasChildNodes() || c.hasAttributeNS(TABLE, "formula")) repeat(minOf(n, 1024)) { rowCells += (col + it) to c }
                    if (c.hasAttributeNS(TABLE, "number-columns-spanned")) lost += "les cellules fusionnées"
                    col += n
                }
                if (rowCells.isNotEmpty()) repeat(minOf(rowRepeat, 1024)) { k ->
                    for ((cc, c) in rowCells) {
                        val ref = Ref(cc, row + k)
                        val formula = c.getAttributeNS(TABLE, "formula")
                        val input = when {
                            formula.isNotEmpty() -> FormulaText.fromOdf(formula)
                            c.getAttributeNS(OFFICE, "value-type") in listOf("float", "percentage", "currency") -> Value.format(c.getAttributeNS(OFFICE, "value").toDouble())
                            c.getAttributeNS(OFFICE, "value-type") == "boolean" -> if (c.getAttributeNS(OFFICE, "boolean-value") == "true") "VRAI" else "FAUX"
                            else -> c.getElementsByTagNameNS(TEXT, "p").let { ps -> (0 until ps.length).joinToString("\n") { ps.item(it).textContent } }
                                .let { if (Input.number(it) != null) "'$it" else it }
                        }
                        if (input.isNotEmpty()) cells[ref] = input
                        if (c.getAttributeNS(TABLE, "style-name") in boldStyles && input.isNotEmpty()) bold += ref
                    }
                }
                row += rowRepeat
            }
            Sheet(table.getAttributeNS(TABLE, "name").ifBlank { "Feuille ${t + 1}" }, cells, bold, widths)
        }
        if (dom.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:drawing:1.0", "frame").length > 0) lost += "les images et graphiques"
        return Loaded(Book(sheets.ifEmpty { listOf(Sheet()) }), lost)
    }

    /** "2.54cm", "1in", "72pt", "120px" in pixels at 96 dpi. */
    private fun pixels(s: String): Int? {
        val m = Regex("([\\d.]+)(cm|mm|in|pt|px)").matchEntire(s.trim()) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "cm" -> v / 2.54 * 96
            "mm" -> v / 25.4 * 96
            "in" -> v * 96
            "pt" -> v / 72 * 96
            else -> v
        }.roundToInt()
    }
}
