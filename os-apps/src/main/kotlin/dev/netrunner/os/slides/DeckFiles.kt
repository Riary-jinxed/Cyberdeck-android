package dev.netrunner.os.slides

import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.xslf.usermodel.SlideLayout
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.sl.usermodel.AutoShape
import org.apache.poi.sl.usermodel.GroupShape
import org.apache.poi.sl.usermodel.PictureShape
import org.apache.poi.sl.usermodel.Shape
import org.apache.poi.sl.usermodel.SlideShowFactory
import org.apache.poi.sl.usermodel.TableShape
import org.apache.poi.sl.usermodel.TextShape
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.w3c.dom.Element
import java.awt.Color
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Presentations on disk: PowerPoint (.pptx, with POI) and OpenDocument (.odp, written and read
 * here). Slides are laid out on a 16:9 page the same way the editor draws them ([Geometry]).
 */
object DeckFiles {
    /** What Présentation opens; it saves in [Writable] (an old .ppt is saved again as .pptx). */
    val Extensions = listOf("pptx", "ppt", "odp")
    val Writable = listOf("pptx", "odp")

    /** Where a file is saved: as it is, or as .pptx when its kind cannot be written. */
    fun target(f: File): File = when (f.extension.lowercase()) {
        in Writable -> f
        in Extensions -> File(f.parentFile, f.nameWithoutExtension + ".pptx")
        else -> File(f.path + ".pptx")
    }

    class Loaded(val deck: Deck, val lost: Set<String>)

    fun read(file: File): Loaded = when (file.extension.lowercase()) {
        "odp" -> readOdp(file)
        else -> readPptx(file)
    }

    fun write(deck: Deck, file: File) {
        val bytes = when (file.extension.lowercase()) {
            "odp" -> writeOdp(deck)
            else -> ByteArrayOutputStream().also { out -> writePptx(deck).use { it.write(out) } }.toByteArray()
        }
        // Written next to it, then moved: a failure halfway never leaves half a file.
        val tmp = File(file.parentFile ?: File("."), ".${file.name}.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.writeBytes(bytes); tmp.delete() }
    }

    /** Where things go on a slide, as fractions of its width and height. */
    object Geometry {
        class Box(val x: Double, val y: Double, val w: Double, val h: Double)

        fun title(s: Slide) = when (s.layout) {
            Layout.Title -> Box(0.08, 0.28, 0.84, 0.24)
            Layout.Section -> Box(0.08, 0.36, 0.84, 0.28)
            Layout.Content -> Box(0.06, 0.06, 0.88, 0.16)
        }

        fun body(s: Slide) = when (s.layout) {
            Layout.Title -> Box(0.12, 0.56, 0.76, 0.18)
            Layout.Section -> Box(0.08, 0.66, 0.84, 0.12)
            Layout.Content -> if (s.picture != null) Box(0.06, 0.26, 0.46, 0.66) else Box(0.06, 0.26, 0.88, 0.66)
        }

        fun picture(s: Slide) = if (s.layout == Layout.Content && s.body.isNotEmpty()) Box(0.55, 0.26, 0.39, 0.66) else Box(0.2, 0.26, 0.6, 0.66)

        /** Font sizes in points on a 960 × 540 page. */
        fun titleSize(s: Slide) = when (s.layout) { Layout.Title -> 44.0; Layout.Section -> 40.0; Layout.Content -> 32.0 }
        fun bodySize(s: Slide, level: Int) = (if (s.layout == Layout.Title) 22.0 else 24.0) - 3.0 * level
    }

    private const val W = 960.0
    private const val H = 540.0

    // ---- PowerPoint.

    private fun color(rgb: Int) = Color(rgb)

    private fun writePptx(deck: Deck): XMLSlideShow {
        val ppt = XMLSlideShow()
        ppt.pageSize = Dimension(W.toInt(), H.toInt())
        val master = ppt.slideMasters[0]
        val t = deck.theme
        for (s in deck.slides) {
            val layout = master.getLayout(when (s.layout) { Layout.Title -> SlideLayout.TITLE; Layout.Section -> SlideLayout.SECTION_HEADER; Layout.Content -> SlideLayout.TITLE_AND_CONTENT })
                ?: master.getLayout(SlideLayout.TITLE_AND_CONTENT)
            val slide = ppt.createSlide(layout)
            slide.background.fillColor = color(t.background)
            val holders = slide.placeholders.toList()
            val titleShape = holders.firstOrNull { it.placeholder == Placeholder.TITLE || it.placeholder == Placeholder.CENTERED_TITLE }
            val bodyShape = holders.firstOrNull { it != titleShape && (it.placeholder == Placeholder.BODY || it.placeholder == Placeholder.SUBTITLE || it.placeholder == Placeholder.CONTENT) }
            holders.filter { it != titleShape && it != bodyShape }.forEach { slide.removeShape(it) }
            fun place(shape: XSLFTextShape, b: Geometry.Box) { shape.anchor = Rectangle2D.Double(b.x * W, b.y * H, b.w * W, b.h * H) }

            val title = titleShape ?: slide.createTextBox()
            place(title, Geometry.title(s))
            title.clearText()
            title.addNewTextParagraph().apply {
                textAlign = if (s.layout == Layout.Content) org.apache.poi.sl.usermodel.TextParagraph.TextAlign.LEFT else org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER
                addNewTextRun().apply { setText(s.title); setFontColor(color(t.title)); fontSize = Geometry.titleSize(s); isBold = true }
            }
            if (s.body.isNotEmpty()) {
                val body = bodyShape ?: slide.createTextBox()
                place(body, Geometry.body(s))
                body.clearText()
                for (l in s.body) body.addNewTextParagraph().apply {
                    indentLevel = l.level
                    if (s.layout != Layout.Content) { isBullet = false; textAlign = org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER }
                    addNewTextRun().apply { setText(l.text); setFontColor(color(if (s.layout == Layout.Content) t.text else t.accent)); fontSize = Geometry.bodySize(s, l.level) }
                }
            } else bodyShape?.let { slide.removeShape(it) }
            s.picture?.let { p ->
                val type = when (p.type) { "png" -> PictureData.PictureType.PNG; "gif" -> PictureData.PictureType.GIF; "bmp" -> PictureData.PictureType.BMP; else -> PictureData.PictureType.JPEG }
                val shape = slide.createPicture(ppt.addPicture(p.bytes, type))
                val b = Geometry.picture(s)
                shape.anchor = fit(p, Rectangle2D.Double(b.x * W, b.y * H, b.w * W, b.h * H))
            }
            if (s.notes.isNotEmpty()) {
                val notes = ppt.getNotesSlide(slide)
                notes.placeholders.firstOrNull { it.placeholder == Placeholder.BODY }?.let { it.text = s.notes }
            }
        }
        return ppt
    }

    /** The picture's box inside [box], keeping its proportions. */
    private fun fit(p: Picture, box: Rectangle2D.Double): Rectangle2D.Double {
        val size = runCatching { javax.imageio.ImageIO.read(p.bytes.inputStream())?.let { it.width.toDouble() to it.height.toDouble() } }.getOrNull() ?: return box
        val scale = minOf(box.width / size.first, box.height / size.second)
        val w = size.first * scale
        val h = size.second * scale
        return Rectangle2D.Double(box.x + (box.width - w) / 2, box.y + (box.height - h) / 2, w, h)
    }

    /** PowerPoint, new (.pptx) or 97-2003 (.ppt): POI reads both through the same interfaces. */
    private fun readPptx(file: File): Loaded {
        val lost = mutableSetOf<String>()
        var theme = Theme.Clair
        val slides = SlideShowFactory.create(file, null, true).use { ppt ->
            ppt.slides.mapIndexed { i, slide ->
                if (i == 0 && slide is XSLFSlide) theme = guessTheme(slide)
                var title = ""
                // The text of each other shape, with how far down the slide it starts.
                val blocks = mutableListOf<Pair<Double, List<Line>>>()
                var centered = false
                var picture: Picture? = null
                fun shapes(list: List<Shape<*, *>>) {
                    for (sh in list) when (sh) {
                        is GroupShape<*, *> -> shapes(sh.shapes)
                        is PictureShape<*, *> -> if (picture == null) picture = Picture(sh.pictureData.data, sh.pictureData.type?.extension?.removePrefix(".")?.let { if (it == "jpg") "jpeg" else it } ?: "png") else lost += "des images en plus de la première"
                        is TableShape<*, *> -> lost += "les tableaux"
                        is TextShape<*, *> -> {
                            val ph = runCatching { sh.placeholder }.getOrNull()
                            if ((ph == Placeholder.TITLE || ph == Placeholder.CENTERED_TITLE) && title.isEmpty()) {
                                title = sh.text.orEmpty().trim().replace(Regex("\\s*[\n\r\u000B]\\s*"), " ")
                                if (ph == Placeholder.CENTERED_TITLE) centered = true
                            } else if (ph in listOf(Placeholder.DATETIME, Placeholder.FOOTER, Placeholder.SLIDE_NUMBER, Placeholder.HEADER)) {
                                // Page furniture, drawn by the master.
                            } else {
                                if (ph == Placeholder.SUBTITLE) centered = true
                                if (ph == null && sh !is AutoShape<*, *>) lost += "la mise en page libre"
                                val lines = sh.textParagraphs.mapNotNull { p ->
                                    val text = p.textRuns.joinToString("") { it.rawText.orEmpty() }.replace('\u000B', ' ').trim()
                                    if (text.isEmpty()) null else Line(text, p.indentLevel.coerceIn(0, 4))
                                }
                                if (lines.isNotEmpty()) blocks += (runCatching { sh.anchor.y }.getOrNull() ?: 0.0) to lines
                            }
                        }
                        else -> lost += "les formes et graphiques"
                    }
                }
                shapes(slide.shapes)
                // No title placeholder (free layout, some old .ppt): the highest one-line text is the title.
                if (title.isEmpty()) blocks.filter { it.second.size == 1 }.minByOrNull { it.first }?.let { b ->
                    title = b.second[0].text
                    blocks.remove(b)
                }
                val body = blocks.flatMap { it.second }
                val notes = slide.notes?.shapes?.filterIsInstance<TextShape<*, *>>()
                    ?.filter { runCatching { it.placeholder }.getOrNull() == Placeholder.BODY }
                    ?.joinToString("\n") { it.text.orEmpty().trim() }.orEmpty()
                val layout = when {
                    centered -> Layout.Title
                    body.isEmpty() && picture == null -> Layout.Section
                    else -> Layout.Content
                }
                Slide(layout, title, body, notes.replace('\r', '\n').trim(), picture)
            }
        }
        return Loaded(Deck(slides.ifEmpty { listOf(Slide()) }, theme), lost)
    }

    /** The theme closest to the first slide's background. */
    private fun guessTheme(slide: XSLFSlide): Theme {
        val c = runCatching { slide.background.fillColor }.getOrNull() ?: return Theme.Clair
        return Theme.entries.minBy { t ->
            val r = (t.background shr 16) and 255
            val g = (t.background shr 8) and 255
            val b = t.background and 255
            (r - c.red) * (r - c.red) + (g - c.green) * (g - c.green) + (b - c.blue) * (b - c.blue)
        }
    }

    // ---- OpenDocument.

    private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val XLINK = "http://www.w3.org/1999/xlink"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val DRAWNS = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun hex(rgb: Int) = "#%06X".format(rgb)
    private fun cm(f: Double, total: Double) = "%.3fcm".format(java.util.Locale.ROOT, f * total)

    private const val PW = 28.0
    private const val PH = 15.75

    private fun frame(b: Geometry.Box, cls: String, style: String, inner: String) =
        "<draw:frame presentation:class=\"$cls\" draw:style-name=\"$style\" svg:x=\"${cm(b.x, PW)}\" svg:y=\"${cm(b.y, PH)}\" svg:width=\"${cm(b.w, PW)}\" svg:height=\"${cm(b.h, PH)}\"><draw:text-box>$inner</draw:text-box></draw:frame>"

    private fun writeOdp(deck: Deck): ByteArray {
        val t = deck.theme
        val pictures = mutableListOf<Pair<String, Picture>>()
        val body = StringBuilder()
        val sizes = sortedSetOf<Int>()
        deck.slides.forEachIndexed { i, s ->
            body.append("<draw:page draw:name=\"page${i + 1}\" draw:style-name=\"dp1\" draw:master-page-name=\"Default\">")
            val ts = Geometry.titleSize(s).toInt().also { sizes += it }
            val tAlign = if (s.layout == Layout.Content) "start" else "center"
            body.append(frame(Geometry.title(s), "title", "grTitle", "<text:p text:style-name=\"P$tAlign\"><text:span text:style-name=\"Tt$ts\">${esc(s.title)}</text:span></text:p>"))
            if (s.body.isNotEmpty()) {
                if (s.layout == Layout.Content) {
                    // One list per line, nested as deep as its level: how OpenDocument writes indentation.
                    val inner = s.body.joinToString("") { l ->
                        val size = Geometry.bodySize(s, l.level).toInt().also { sizes += it }
                        "<text:list text:style-name=\"L1\"><text:list-item>" + "<text:list><text:list-item>".repeat(l.level) +
                            "<text:p><text:span text:style-name=\"Tb$size\">${esc(l.text)}</text:span></text:p>" +
                            "</text:list-item></text:list>".repeat(l.level) + "</text:list-item></text:list>"
                    }
                    body.append(frame(Geometry.body(s), "outline", "grBody", inner))
                } else {
                    val size = Geometry.bodySize(s, 0).toInt().also { sizes += it }
                    body.append(frame(Geometry.body(s), "subtitle", "grBody", s.body.joinToString("") { "<text:p text:style-name=\"Pcenter\"><text:span text:style-name=\"Ta$size\">${esc(it.text)}</text:span></text:p>" }))
                }
            }
            s.picture?.let { p ->
                val name = "Pictures/image${pictures.size + 1}.${if (p.type == "jpeg") "jpg" else p.type}"
                pictures += name to p
                val b = Geometry.picture(s)
                body.append("<draw:frame draw:style-name=\"grPicture\" svg:x=\"${cm(b.x, PW)}\" svg:y=\"${cm(b.y, PH)}\" svg:width=\"${cm(b.w, PW)}\" svg:height=\"${cm(b.h, PH)}\">" +
                    "<draw:image xlink:href=\"$name\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/></draw:frame>")
            }
            if (s.notes.isNotEmpty()) {
                body.append("<presentation:notes><draw:frame presentation:class=\"notes\" svg:x=\"2cm\" svg:y=\"14cm\" svg:width=\"17cm\" svg:height=\"12cm\"><draw:text-box>")
                s.notes.split('\n').forEach { body.append("<text:p>${esc(it)}</text:p>") }
                body.append("</draw:text-box></draw:frame></presentation:notes>")
            }
            body.append("</draw:page>")
        }
        val auto = StringBuilder()
        auto.append("<style:style style:name=\"dp1\" style:family=\"drawing-page\"><style:drawing-page-properties draw:fill=\"solid\" draw:fill-color=\"${hex(t.background)}\" draw:background-size=\"full\"/></style:style>")
        for (g in listOf("grTitle", "grBody", "grPicture")) auto.append("<style:style style:name=\"$g\" style:family=\"graphic\"><style:graphic-properties draw:stroke=\"none\" draw:fill=\"none\"/></style:style>")
        auto.append("<style:style style:name=\"Pstart\" style:family=\"paragraph\"><style:paragraph-properties fo:text-align=\"start\"/></style:style>")
        auto.append("<style:style style:name=\"Pcenter\" style:family=\"paragraph\"><style:paragraph-properties fo:text-align=\"center\"/></style:style>")
        for (size in sizes) {
            auto.append("<style:style style:name=\"Tt$size\" style:family=\"text\"><style:text-properties fo:font-size=\"${size}pt\" fo:font-weight=\"bold\" fo:color=\"${hex(t.title)}\"/></style:style>")
            auto.append("<style:style style:name=\"Tb$size\" style:family=\"text\"><style:text-properties fo:font-size=\"${size}pt\" fo:color=\"${hex(t.text)}\"/></style:style>")
            auto.append("<style:style style:name=\"Ta$size\" style:family=\"text\"><style:text-properties fo:font-size=\"${size}pt\" fo:color=\"${hex(t.accent)}\"/></style:style>")
        }
        auto.append("<text:list-style style:name=\"L1\">")
        for (lv in 1..5) auto.append("<text:list-level-style-bullet text:level=\"$lv\" text:bullet-char=\"${if (lv == 1) "•" else "–"}\"><style:list-level-properties text:space-before=\"${(lv - 1) * 0.9}cm\" text:min-label-width=\"0.7cm\"/><style:text-properties fo:color=\"${hex(t.accent)}\"/></text:list-level-style-bullet>")
        auto.append("</text:list-style>")

        val ns = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:style=\"$STYLE\" xmlns:text=\"$TEXT\" xmlns:draw=\"$DRAW\" " +
            "xmlns:presentation=\"$PRESENTATION\" xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" xmlns:fo=\"$FO\" xmlns:xlink=\"$XLINK\""
        val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $ns office:version=\"1.3\">" +
            "<office:automatic-styles>$auto</office:automatic-styles><office:body><office:presentation>$body</office:presentation></office:body></office:document-content>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles $ns office:version=\"1.3\"><office:styles/>" +
            "<office:automatic-styles><style:page-layout style:name=\"PM1\"><style:page-layout-properties fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\" fo:margin-left=\"0cm\" fo:margin-right=\"0cm\" fo:page-width=\"${PW}cm\" fo:page-height=\"${PH}cm\" style:print-orientation=\"landscape\"/></style:page-layout>" +
            "<style:style style:name=\"Mdp1\" style:family=\"drawing-page\"><style:drawing-page-properties draw:fill=\"solid\" draw:fill-color=\"${hex(t.background)}\"/></style:style></office:automatic-styles>" +
            "<office:master-styles><style:master-page style:name=\"Default\" style:page-layout-name=\"PM1\" draw:style-name=\"Mdp1\"/></office:master-styles></office:document-styles>"
        val manifest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">" +
            "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.presentation\"/>" +
            "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>" +
            "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>" +
            pictures.joinToString("") { (name, p) -> "<manifest:file-entry manifest:full-path=\"$name\" manifest:media-type=\"image/${p.type}\"/>" } +
            "</manifest:manifest>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // The mimetype comes first, uncompressed: that is how readers recognise the format.
            val mime = "application/vnd.oasis.opendocument.presentation".toByteArray()
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
            for ((name, p) in pictures) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(p.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readOdp(file: File): Loaded {
        val lost = mutableSetOf<String>()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return ZipFile(file).use { zip ->
            val dom = zip.getInputStream(zip.getEntry("content.xml") ?: error("pas de content.xml")).use { factory.newDocumentBuilder().parse(it) }
            fun kids(e: Element) = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()
            // Page background of the first slide, for the theme.
            val fills = HashMap<String, Int>()
            val styles = dom.getElementsByTagNameNS(STYLE, "style")
            for (i in 0 until styles.length) {
                val st = styles.item(i) as Element
                val props = st.getElementsByTagNameNS(STYLE, "drawing-page-properties").item(0) as Element? ?: continue
                props.getAttributeNS(DRAWNS, "fill-color").removePrefix("#").toIntOrNull(16)?.let { fills[st.getAttributeNS(STYLE, "name")] = it }
            }
            var theme = Theme.Clair
            val pages = dom.getElementsByTagNameNS(DRAW, "page")
            val slides = (0 until pages.length).map { i ->
                val page = pages.item(i) as Element
                if (i == 0) fills[page.getAttributeNS(DRAW, "style-name")]?.let { bg ->
                    theme = Theme.entries.minBy { t -> listOf(16, 8, 0).sumOf { sh -> (((t.background shr sh) and 255) - ((bg shr sh) and 255)).let { it * it } } }
                }
                var title = ""
                val body = mutableListOf<Line>()
                var notes = ""
                var picture: Picture? = null
                var centered = false
                fun lines(e: Element, level: Int) {
                    for (k in kids(e)) when {
                        k.namespaceURI == TEXT && (k.localName == "p" || k.localName == "h") -> k.textContent.trim().takeIf { it.isNotEmpty() }?.let { body += Line(it, level.coerceIn(0, 4)) }
                        k.namespaceURI == TEXT && k.localName == "list" -> lines(k, if (e.localName == "list-item") level + 1 else level)
                        k.namespaceURI == TEXT && k.localName == "list-item" -> lines(k, level)
                    }
                }
                for (f in kids(page)) {
                    if (f.namespaceURI == PRESENTATION && f.localName == "notes") {
                        notes = f.getElementsByTagNameNS(TEXT, "p").let { ps -> (0 until ps.length).joinToString("\n") { ps.item(it).textContent.trim() } }.trim()
                        continue
                    }
                    if (f.namespaceURI != DRAW) continue
                    if (f.localName != "frame") { lost += "les formes"; continue }
                    val cls = f.getAttributeNS(PRESENTATION, "class")
                    val image = f.getElementsByTagNameNS(DRAW, "image").item(0) as Element?
                    val box = f.getElementsByTagNameNS(DRAW, "text-box").item(0) as Element?
                    when {
                        cls == "title" && box != null -> title = box.textContent.trim().replace(Regex("\\s+"), " ")
                        box != null -> {
                            if (cls == "subtitle") centered = true
                            lines(box, 0)
                        }
                        image != null -> {
                            val href = image.getAttributeNS(XLINK, "href")
                            val entry = zip.getEntry(href)
                            if (entry != null && picture == null) picture = Picture(zip.getInputStream(entry).use { it.readBytes() }, href.substringAfterLast('.').lowercase().let { if (it == "jpg") "jpeg" else it })
                            else if (entry != null) lost += "des images en plus de la première"
                        }
                        else -> lost += "les tableaux et graphiques"
                    }
                }
                val layout = when {
                    centered -> Layout.Title
                    body.isEmpty() && picture == null -> Layout.Section
                    else -> Layout.Content
                }
                Slide(layout, title, body, notes, picture)
            }
            Loaded(Deck(slides.ifEmpty { listOf(Slide()) }, theme), lost)
        }
    }
}
