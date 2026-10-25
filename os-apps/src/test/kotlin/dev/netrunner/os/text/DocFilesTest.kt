package dev.netrunner.os.text

import dev.netrunner.os.text.Doc.Align
import dev.netrunner.os.text.Doc.Kind
import dev.netrunner.os.text.Doc.Style
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocFilesTest {
    private val sample = Doc(
        "Rapport\nIntroduction\nLe serveur est en panne.\nRedémarrer\nVérifier & noter\nFin <ok>",
        listOf(
            Doc.Para(Kind.Title, Align.Center), Doc.Para(Kind.Heading1), Doc.Para(Kind.Normal, Align.Justify),
            Doc.Para(Kind.Bullet), Doc.Para(Kind.Bullet), Doc.Para(Kind.Quote, Align.End),
        ),
    ).set(Style.Bold, 24, 34, true).set(Style.Italic, 27, 34, true).set(Style.Underline, 59, 68, true).set(Style.Strike, 69, 73, true)

    private fun roundTrip(ext: String): Doc {
        val f = File(Files.createTempDirectory("texte").toFile(), "rapport.$ext")
        DocFiles.write(sample, f)
        return DocFiles.read(f).doc
    }

    @Test fun docxKeepsEverything() {
        val back = roundTrip("docx")
        assertEquals(sample.text, back.text)
        assertEquals(sample.paras, back.paras)
        assertEquals(sample.spans, back.spans)
    }

    @Test fun odtKeepsEverything() {
        val back = roundTrip("odt")
        assertEquals(sample.text, back.text)
        assertEquals(sample.paras, back.paras)
        assertEquals(sample.spans, back.spans)
    }

    @Test fun odtStartsWithItsMimetype() {
        val f = File(Files.createTempDirectory("texte").toFile(), "a.odt")
        DocFiles.write(sample, f)
        ZipFile(f).use { assertEquals("mimetype", it.entries().nextElement().name) }
    }

    @Test fun plainText() {
        val back = roundTrip("txt")
        assertEquals(sample.text, back.text)
        assertTrue(back.spans.isEmpty())
    }
}
