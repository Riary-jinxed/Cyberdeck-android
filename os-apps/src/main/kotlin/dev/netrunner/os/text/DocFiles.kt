package dev.netrunner.os.text

import dev.netrunner.os.text.Doc.Align
import dev.netrunner.os.text.Doc.Kind
import dev.netrunner.os.text.Doc.Style
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFStyle
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Netrunner Texte's files: Word (.docx, through Apache POI), OpenDocument (.odt, written and
 * read here) and plain text. What the editor has no room for yet (tables, pictures) becomes
 * text or is left out, and [Loaded.lost] says so.
 */
object DocFiles {
    class Loaded(val doc: Doc, val lost: List<String> = emptyList())

    /** What Texte opens; it saves in [Writable] (an old .doc is saved again as .docx). */
    val Extensions = setOf("docx", "doc", "odt", "txt", "md")
    val Writable = setOf("docx", "odt", "txt", "md")

    /** Where a file is saved: as it is, or as .docx when its kind cannot be written. */
    fun target(f: File): File = when (f.extension.lowercase()) {
        in Writable -> f
        in Extensions -> File(f.parentFile, f.nameWithoutExtension + ".docx")
        else -> File(f.path + ".docx")
    }

    fun read(file: File): Loaded = when (file.extension.lowercase()) {
        "docx" -> readDocx(file)
        "doc" -> readDoc(file)
        "odt" -> readOdt(file)
        "md" -> Loaded(Markdown.parse(file.readText()))
        else -> Loaded(Doc.of(file.readText().replace("\r\n", "\n").replace('\r', '\n')))
    }

    fun write(doc: Doc, file: File) {
        val tmp = File(file.parentFile, ".${file.name}.part")
        when (file.extension.lowercase()) {
            "docx" -> tmp.outputStream().use { writeDocx(doc).write(it) }
            "odt" -> tmp.writeBytes(writeOdt(doc))
            "md" -> tmp.writeText(Markdown.write(doc) + "\n")
            else -> tmp.writeText(doc.text)
        }
        // Written aside then swapped: a crash never leaves half a file.
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Impossible d'écrire ${file.path}" }
        }
    }

    /** Builds a Doc paragraph after paragraph. */
    private class Builder {
        val text = StringBuilder()
        val paras = mutableListOf<Doc.Para>()
        val spans = mutableListOf<Doc.Span>()

        fun paragraph(para: Doc.Para) {
            if (paras.isNotEmpty()) text.append('\n')
            paras += para
        }

        fun run(s: String, styles: Set<Style>) {
            val clean = s.replace("\r", "").replace('\n', ' ').replace('\u000B', ' ')
            val start = text.length
            text.append(clean)
            styles.forEach { spans += Doc.Span(start, text.length, it) }
        }

        fun doc(): Doc {
            if (paras.isEmpty()) paras += Doc.Para()
            return Doc(text.toString(), paras.toList(), Doc.normalize(spans))
        }
    }

    // ---- Word.

    private val HeadingIds = mapOf(Kind.Title to "Title", Kind.Heading1 to "Heading1", Kind.Heading2 to "Heading2", Kind.Heading3 to "Heading3", Kind.Quote to "Quote")

    private fun kindOfStyle(id: String?, name: String?): Kind {
        val k = (listOfNotNull(id, name).joinToString(" ")).lowercase().replace("_20_", "").replace(" ", "")
        return when {
            k.contains("title") || k.contains("titre") && !Regex("\\d").containsMatchIn(k) -> Kind.Title
            Regex("heading1|titre1").containsMatchIn(k) -> Kind.Heading1
            Regex("heading2|titre2").containsMatchIn(k) -> Kind.Heading2
            Regex("heading[3-9]|titre[3-9]").containsMatchIn(k) -> Kind.Heading3
            k.contains("quote") || k.contains("citation") -> Kind.Quote
            else -> Kind.Normal
        }
    }

    private fun readDocx(file: File): Loaded {
        val lost = mutableSetOf<String>()
        val b = Builder()
        file.inputStream().use { input ->
            XWPFDocument(input).use { doc ->
                fun paragraph(p: XWPFParagraph) {
                    val style = p.style?.let { doc.styles?.getStyle(it) }
                    var kind = kindOfStyle(p.style, style?.name)
                    if (p.numID != null && kind == Kind.Normal) kind = if (p.numFmt == "bullet") Kind.Bullet else Kind.Numbered
                    val align = when (p.alignment) {
                        ParagraphAlignment.CENTER -> Align.Center
                        ParagraphAlignment.RIGHT, ParagraphAlignment.END -> Align.End
                        ParagraphAlignment.BOTH, ParagraphAlignment.DISTRIBUTE -> Align.Justify
                        else -> Align.Start
                    }
                    b.paragraph(Doc.Para(kind, align))
                    for (r in p.runs) {
                        if (r.embeddedPictures.isNotEmpty()) lost += "images"
                        val styles = buildSet {
                            if (r.isBold) add(Style.Bold)
                            if (r.isItalic) add(Style.Italic)
                            if (r.underline != null && r.underline != UnderlinePatterns.NONE) add(Style.Underline)
                            if (r.isStrikeThrough || r.isDoubleStrikeThrough) add(Style.Strike)
                        }
                        b.run(r.text().orEmpty().replace('\t', ' '), styles)
                    }
                }
                for (element in doc.bodyElements) {
                    when (element) {
                        is XWPFParagraph -> paragraph(element)
                        is XWPFTable -> {
                            lost += "tableaux (gardés en texte)"
                            for (row in element.rows) {
                                b.paragraph(Doc.Para())
                                b.run(row.tableCells.joinToString("  |  ") { it.text.replace('\n', ' ') }, emptySet())
                            }
                        }
                    }
                }
            }
        }
        return Loaded(b.doc(), lost.toList())
    }

    /** Word 97-2003: text, headings, lists, alignments and styles of the characters. */
    private fun readDoc(file: File): Loaded {
        val lost = mutableSetOf<String>()
        val b = Builder()
        file.inputStream().use { HWPFDocument(it) }.use { doc ->
            val range = doc.range
            for (i in 0 until range.numParagraphs()) {
                val p = range.getParagraph(i)
                if (p.isInTable) lost += "tableaux (gardés en texte)"
                var kind = kindOfStyle(null, doc.styleSheet.getStyleDescription(p.styleIndex.toInt())?.name)
                if (p.isInList && kind == Kind.Normal) kind = Kind.Bullet
                val align = when (p.justification) { 1 -> Align.Center; 2 -> Align.End; 3 -> Align.Justify; else -> Align.Start }
                b.paragraph(Doc.Para(kind, align))
                for (k in 0 until p.numCharacterRuns()) {
                    val r = p.getCharacterRun(k)
                    // Field marks, cell marks and picture anchors are control characters.
                    val text = r.text().replace('\t', ' ').filter { it >= ' ' }
                    if (r.text().contains('\u0001')) lost += "images"
                    val styles = buildSet {
                        if (r.isBold) add(Style.Bold)
                        if (r.isItalic) add(Style.Italic)
                        if (r.underlineCode != 0) add(Style.Underline)
                        if (r.isStrikeThrough || r.isDoubleStrikeThrough) add(Style.Strike)
                    }
                    b.run(text, styles)
                }
            }
        }
        return Loaded(b.doc(), lost.toList())
    }

    private fun writeDocx(doc: Doc): XWPFDocument {
        val x = XWPFDocument()
        val styles = x.createStyles()
        fun style(id: String, name: String, sizeHalfPt: Int, bold: Boolean, italic: Boolean = false) {
            val ct = CTStyle.Factory.newInstance()
            ct.styleId = id
            ct.addNewName().`val` = name
            ct.type = STStyleType.PARAGRAPH
            ct.addNewBasedOn().`val` = "Normal"
            ct.addNewQFormat()
            if (id.startsWith("Heading")) ct.addNewPPr().addNewOutlineLvl().`val` = BigInteger.valueOf(id.last().digitToInt() - 1L)
            val rpr = ct.addNewRPr()
            rpr.addNewSz().`val` = BigInteger.valueOf(sizeHalfPt.toLong())
            if (bold) rpr.addNewB()
            if (italic) rpr.addNewI()
            styles.addStyle(XWPFStyle(ct, styles))
        }
        style("Title", "Title", 52, true)
        style("Heading1", "heading 1", 36, true)
        style("Heading2", "heading 2", 30, true)
        style("Heading3", "heading 3", 26, true)
        style("Quote", "Quote", 24, false, italic = true)

        val numbering = x.createNumbering()
        fun list(bullet: Boolean): BigInteger {
            val ct = CTAbstractNum.Factory.newInstance()
            ct.abstractNumId = BigInteger.valueOf(if (bullet) 0 else 1)
            val lvl = ct.addNewLvl()
            lvl.ilvl = BigInteger.ZERO
            lvl.addNewStart().`val` = BigInteger.ONE
            lvl.addNewNumFmt().`val` = if (bullet) STNumberFormat.BULLET else STNumberFormat.DECIMAL
            lvl.addNewLvlText().`val` = if (bullet) "•" else "%1."
            lvl.addNewPPr().addNewInd().apply { left = BigInteger.valueOf(720); hanging = BigInteger.valueOf(360) }
            return numbering.addNum(numbering.addAbstractNum(XWPFAbstractNum(ct, numbering)))
        }
        val bullets by lazy { list(true) }
        val numbers by lazy { list(false) }

        for (i in doc.paras.indices) {
            val para = doc.paras[i]
            val p = x.createParagraph()
            HeadingIds[para.kind]?.let { p.style = it }
            if (para.kind.list) {
                p.numID = if (para.kind == Kind.Bullet) bullets else numbers
                val num = p.ctp.pPr.numPr
                (num.ilvl ?: num.addNewIlvl()).`val` = BigInteger.ZERO
            }
            p.alignment = when (para.align) {
                Align.Start -> ParagraphAlignment.LEFT
                Align.Center -> ParagraphAlignment.CENTER
                Align.End -> ParagraphAlignment.RIGHT
                Align.Justify -> ParagraphAlignment.BOTH
            }
            val range = doc.range(i)
            for ((start, end) in pieces(doc, range.first, range.last + 1)) {
                val r = p.createRun()
                r.setText(doc.text.substring(start, end))
                val on = stylesOn(doc, start, end)
                if (Style.Bold in on) r.isBold = true
                if (Style.Italic in on) r.isItalic = true
                if (Style.Underline in on) r.underline = UnderlinePatterns.SINGLE
                if (Style.Strike in on) r.isStrikeThrough = true
            }
        }
        return x
    }

    /** [start]..[end] cut wherever a style starts or stops: each piece has one set of styles. */
    fun pieces(doc: Doc, start: Int, end: Int): List<Pair<Int, Int>> {
        if (start >= end) return emptyList()
        val cuts = (doc.spans.flatMap { listOf(it.start, it.end) }.filter { it in (start + 1) until end } + start + end).distinct().sorted()
        return cuts.zipWithNext()
    }

    /** The styles on all of [start]..[end] (a piece from [pieces]). */
    fun stylesOn(doc: Doc, start: Int, end: Int): Set<Style> =
        doc.spans.filter { it.start <= start && it.end >= end }.map { it.style }.toSet()

    // ---- OpenDocument.

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun writeOdt(doc: Doc): ByteArray {
        val styleSets = mutableListOf<Set<Style>>()
        val body = StringBuilder()
        var openList: Kind? = null
        for (i in doc.paras.indices) {
            val para = doc.paras[i]
            if (openList != null && openList != para.kind) { body.append("</text:list>"); openList = null }
            if (para.kind.list && openList == null) {
                body.append("<text:list text:style-name=\"${if (para.kind == Kind.Bullet) "LBullet" else "LNumber"}\">")
                openList = para.kind
            }
            val pStyle = "P${para.kind.name}${para.align.name}"
            val range = doc.range(i)
            val inner = StringBuilder()
            for ((s, e) in pieces(doc, range.first, range.last + 1)) {
                val on = stylesOn(doc, s, e)
                val t = esc(doc.text.substring(s, e))
                if (on.isEmpty()) inner.append(t) else {
                    if (on !in styleSets) styleSets += on
                    inner.append("<text:span text:style-name=\"T${styleSets.indexOf(on)}\">$t</text:span>")
                }
            }
            val level = when (para.kind) { Kind.Heading1 -> 1; Kind.Heading2 -> 2; Kind.Heading3 -> 3; else -> 0 }
            val element = if (level > 0) "<text:h text:style-name=\"$pStyle\" text:outline-level=\"$level\">$inner</text:h>" else "<text:p text:style-name=\"$pStyle\">$inner</text:p>"
            body.append(if (para.kind.list) "<text:list-item>$element</text:list-item>" else element)
        }
        if (openList != null) body.append("</text:list>")

        val auto = StringBuilder()
        for (kind in Kind.entries) for (align in Align.entries) {
            val parent = when (kind) { Kind.Title -> "Title"; Kind.Heading1 -> "Heading_20_1"; Kind.Heading2 -> "Heading_20_2"; Kind.Heading3 -> "Heading_20_3"; Kind.Quote -> "Quotations"; else -> "Standard" }
            val fo = when (align) { Align.Start -> "start"; Align.Center -> "center"; Align.End -> "end"; Align.Justify -> "justify" }
            auto.append("<style:style style:name=\"P${kind.name}${align.name}\" style:family=\"paragraph\" style:parent-style-name=\"$parent\"><style:paragraph-properties fo:text-align=\"$fo\"/></style:style>")
        }
        styleSets.forEachIndexed { i, set ->
            val props = buildString {
                if (Style.Bold in set) append(" fo:font-weight=\"bold\"")
                if (Style.Italic in set) append(" fo:font-style=\"italic\"")
                if (Style.Underline in set) append(" style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"")
                if (Style.Strike in set) append(" style:text-line-through-style=\"solid\"")
            }
            auto.append("<style:style style:name=\"T$i\" style:family=\"text\"><style:text-properties$props/></style:style>")
        }
        auto.append("<text:list-style style:name=\"LBullet\"><text:list-level-style-bullet text:level=\"1\" text:bullet-char=\"•\"><style:list-level-properties text:space-before=\"0.4cm\" text:min-label-width=\"0.6cm\"/></text:list-level-style-bullet></text:list-style>")
        auto.append("<text:list-style style:name=\"LNumber\"><text:list-level-style-number text:level=\"1\" style:num-suffix=\".\" style:num-format=\"1\"><style:list-level-properties text:space-before=\"0.4cm\" text:min-label-width=\"0.6cm\"/></text:list-level-style-number></text:list-style>")

        val ns = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
            "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
        val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $ns office:version=\"1.3\">" +
            "<office:automatic-styles>$auto</office:automatic-styles><office:body><office:text>$body</office:text></office:body></office:document-content>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles $ns office:version=\"1.3\"><office:styles>" +
            "<style:style style:name=\"Standard\" style:family=\"paragraph\"><style:text-properties fo:font-size=\"12pt\"/></style:style>" +
            "<style:style style:name=\"Title\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:text-properties fo:font-size=\"26pt\" fo:font-weight=\"bold\"/></style:style>" +
            "<style:style style:name=\"Heading_20_1\" style:display-name=\"Heading 1\" style:family=\"paragraph\" style:parent-style-name=\"Standard\" style:default-outline-level=\"1\"><style:text-properties fo:font-size=\"18pt\" fo:font-weight=\"bold\"/></style:style>" +
            "<style:style style:name=\"Heading_20_2\" style:display-name=\"Heading 2\" style:family=\"paragraph\" style:parent-style-name=\"Standard\" style:default-outline-level=\"2\"><style:text-properties fo:font-size=\"15pt\" fo:font-weight=\"bold\"/></style:style>" +
            "<style:style style:name=\"Heading_20_3\" style:display-name=\"Heading 3\" style:family=\"paragraph\" style:parent-style-name=\"Standard\" style:default-outline-level=\"3\"><style:text-properties fo:font-size=\"13pt\" fo:font-weight=\"bold\"/></style:style>" +
            "<style:style style:name=\"Quotations\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:margin-left=\"1cm\"/><style:text-properties fo:font-style=\"italic\"/></style:style>" +
            "</office:styles></office:document-styles>"
        val manifest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">" +
            "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>" +
            "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>" +
            "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/></manifest:manifest>"

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // The mimetype comes first, uncompressed: that is how readers recognise the format.
            val mime = "application/vnd.oasis.opendocument.text".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            for ((name, xml) in listOf("content.xml" to content, "styles.xml" to styles, "META-INF/manifest.xml" to manifest)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"

    private fun readOdt(file: File): Loaded {
        val lost = mutableSetOf<String>()
        fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(bytes.inputStream())
        val (content, styles) = ZipFile(file).use { zip ->
            fun entry(name: String) = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
            parse(entry("content.xml") ?: error("${file.name} n'a pas de content.xml")) to entry("styles.xml")?.let(::parse)
        }
        // Every named style (automatic and common): its parent, text properties, alignment, list kind.
        class Info(val parent: String?, val text: Set<Style>, val align: Align?, val numbered: Boolean?)
        val infos = mutableMapOf<String, Info>()
        for (d in listOfNotNull(content, styles)) {
            val nodes = d.getElementsByTagNameNS(STYLE, "style")
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                val tp = e.getElementsByTagNameNS(STYLE, "text-properties").item(0) as? Element
                val pp = e.getElementsByTagNameNS(STYLE, "paragraph-properties").item(0) as? Element
                val text = buildSet {
                    if (tp?.getAttributeNS(FO, "font-weight") == "bold") add(Style.Bold)
                    if (tp?.getAttributeNS(FO, "font-style") == "italic") add(Style.Italic)
                    tp?.getAttributeNS(STYLE, "text-underline-style")?.let { if (it.isNotEmpty() && it != "none") add(Style.Underline) }
                    tp?.getAttributeNS(STYLE, "text-line-through-style")?.let { if (it.isNotEmpty() && it != "none") add(Style.Strike) }
                }
                val align = when (pp?.getAttributeNS(FO, "text-align")) {
                    "center" -> Align.Center
                    "end", "right" -> Align.End
                    "justify" -> Align.Justify
                    "start", "left" -> Align.Start
                    else -> null
                }
                infos[e.getAttributeNS(STYLE, "name")] = Info(e.getAttributeNS(STYLE, "parent-style-name").ifEmpty { null }, text, align, null)
            }
            val lists = d.getElementsByTagNameNS(TEXT, "list-style")
            for (i in 0 until lists.length) {
                val e = lists.item(i) as Element
                infos[e.getAttributeNS(STYLE, "name")] = Info(null, emptySet(), null, e.getElementsByTagNameNS(TEXT, "list-level-style-number").length > 0)
            }
        }
        fun chain(name: String?): List<String> = generateSequence(name) { infos[it]?.parent }.take(8).toList()
        fun textStyles(name: String?) = chain(name).flatMap { infos[it]?.text.orEmpty() }.toSet()
        fun align(name: String?) = chain(name).firstNotNullOfOrNull { infos[it]?.align } ?: Align.Start
        fun kind(name: String?): Kind {
            for (n in chain(name)) {
                val k = kindOfStyle(n, null)
                if (k != Kind.Normal) return k
            }
            return Kind.Normal
        }

        val b = Builder()
        fun inline(node: Node, styles: Set<Style>) {
            var child = node.firstChild
            while (child != null) {
                when {
                    child.nodeType == Node.TEXT_NODE -> b.run(child.nodeValue, styles)
                    child is Element && child.namespaceURI == TEXT -> when (child.localName) {
                        "span", "a" -> inline(child, styles + textStyles(child.getAttributeNS(TEXT, "style-name")))
                        "s" -> b.run(" ".repeat(child.getAttributeNS(TEXT, "c").toIntOrNull() ?: 1), styles)
                        "tab", "line-break" -> b.run(" ", styles)
                        else -> inline(child, styles)
                    }
                    child is Element && child.localName == "frame" -> lost += "images"
                }
                child = child.nextSibling
            }
        }
        fun block(node: Node, list: Kind?) {
            var child = node.firstChild
            while (child != null) {
                if (child is Element) when {
                    child.namespaceURI == TEXT && (child.localName == "p" || child.localName == "h") -> {
                        val style = child.getAttributeNS(TEXT, "style-name")
                        var k = list ?: kind(style)
                        if (list == null && child.localName == "h") {
                            k = when (child.getAttributeNS(TEXT, "outline-level")) { "1" -> Kind.Heading1; "2" -> Kind.Heading2; else -> Kind.Heading3 }
                        }
                        b.paragraph(Doc.Para(k, align(style)))
                        // A heading's or a quote's look comes with its kind; a normal paragraph made bold keeps it.
                        inline(child, if (k.heading || k == Kind.Quote) emptySet() else textStyles(style))
                    }
                    child.namespaceURI == TEXT && child.localName == "list" -> {
                        val numbered = infos[child.getAttributeNS(TEXT, "style-name")]?.numbered
                        block(child, list ?: if (numbered == true) Kind.Numbered else Kind.Bullet)
                    }
                    child.localName == "table" -> {
                        lost += "tableaux (gardés en texte)"
                        block(child, list)
                    }
                    else -> block(child, list)
                }
                child = child.nextSibling
            }
        }
        val text = content.getElementsByTagNameNS(OFFICE, "text").item(0) ?: error("${file.name} n'a pas de texte")
        block(text, null)
        return Loaded(b.doc(), lost.toList())
    }
}
