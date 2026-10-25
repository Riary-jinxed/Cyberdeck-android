package dev.netrunner.os.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfTest {
    private fun sample(): File {
        val f = Files.createTempFile("doc", ".pdf").toFile()
        PDDocument().use { doc ->
            listOf("Bonjour du deck", "Le serveur de la compta", "Fin du document").forEachIndexed { i, line ->
                val page = PDPage(if (i == 1) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { s ->
                    s.beginText()
                    s.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                    s.newLineAtOffset(72f, 700f)
                    s.showText(line)
                    s.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    @Test fun readsSearchesAndRenders() {
        val pdf = Pdf.open(sample()).getOrThrow()
        try {
            assertEquals(3, pdf.pages)
            assertTrue(pdf.ratios[0] < 1f && pdf.ratios[1] > 1f)
            assertEquals(listOf(2), pdf.search("SERVEUR").map { it.first })
            assertTrue(pdf.search("serveur")[0].second.contains("compta"))
            assertTrue(pdf.read(1, 2).contains("Bonjour du deck") && !pdf.read(1, 2).contains("Fin du document"))
            assertTrue(pdf.read(3, 99).contains("Fin du document"))
            assertTrue(kotlin.math.abs(pdf.render(0, 300).width - 300) <= 1)
        } finally {
            pdf.close()
        }
    }

    @Test fun badFileSaysWhy() {
        val f = Files.createTempFile("bad", ".pdf").toFile().apply { writeText("pas un pdf") }
        assertTrue(Pdf.open(f).exceptionOrNull()!!.message!!.contains("pas un PDF lisible"))
        assertEquals("ecole", Pdf.fold("École"))
    }
}
