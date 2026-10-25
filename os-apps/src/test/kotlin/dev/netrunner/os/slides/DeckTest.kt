package dev.netrunner.os.slides

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckTest {
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private val sample = Deck(
        listOf(
            Slide(Layout.Title, "Bilan 2026", listOf(Line("Service informatique"))),
            Slide(Layout.Content, "Ce qui marche", listOf(Line("Inventaire"), Line("Deck Probe sur 40 PC", 1), Line("Sauvegardes & restauration")), notes = "Insister sur Deck Probe.\nDeuxième ligne"),
            Slide(Layout.Section, "Questions ?"),
            Slide(Layout.Content, "Le parc", listOf(Line("Écrans <24\">")), picture = Picture(png, "png")),
        ),
        Theme.Ardoise,
    )

    @Test fun markdownRoundTrip() {
        val md = SlideMarkdown.write(sample.slides)
        val back = SlideMarkdown.parse(md)
        assertEquals(sample.slides.map { it.copy(picture = null) }, back)
    }

    @Test fun markdownFromAska() {
        val slides = SlideMarkdown.parse("# Plan\n1. Contexte\n2. Chiffres\n   * détail\n---\n# Merci\nNotes : finir à l'heure")
        assertEquals(2, slides.size)
        assertEquals(listOf(Line("Contexte"), Line("Chiffres"), Line("détail", 1)), slides[0].body)
        assertEquals(Layout.Section, slides[1].layout)
        assertEquals("finir à l'heure", slides[1].notes)
    }

    @Test fun editorLines() {
        val s = Slide(body = Slide.lines("Un\n  Deux\n\tTrois\nQuatre\n"))
        assertEquals(listOf(Line("Un"), Line("Deux", 1), Line("Trois", 1), Line("Quatre")), s.body)
        assertEquals("Un\n  Deux\n  Trois\nQuatre", s.bodyText)
    }

    private fun roundTrip(ext: String): Deck {
        val f = File(Files.createTempDirectory("presentation").toFile(), "bilan.$ext")
        DeckFiles.write(sample, f)
        return DeckFiles.read(f).deck
    }

    @Test fun pptxKeepsSlides() {
        val back = roundTrip("pptx")
        assertEquals(sample.slides, back.slides)
        assertEquals(Theme.Ardoise, back.theme)
    }

    @Test fun odpKeepsSlides() {
        val back = roundTrip("odp")
        assertEquals(sample.slides, back.slides)
        assertEquals(Theme.Ardoise, back.theme)
    }

    @Test fun moveAndRemove() {
        val d = sample.move(0, 3)
        assertEquals("Bilan 2026", d.slides[3].title)
        assertTrue(Deck(listOf(Slide())).remove(0).slides.size == 1)
    }
}

class OldPowerPointTest {
    @Test fun pptReads() {
        val f = File(Files.createTempDirectory("ppt").toFile(), "ancien.ppt")
        org.apache.poi.hslf.usermodel.HSLFSlideShow().use { ss ->
            val s = ss.createSlide()
            s.addTitle().text = "Ancien format"
            s.createTextBox().apply { text = "Point un"; anchor = java.awt.Rectangle(50, 150, 500, 100) }
            f.outputStream().use { ss.write(it) }
        }
        val d = DeckFiles.read(f).deck
        assertEquals("Ancien format", d.slides[0].title)
        assertEquals(listOf(Line("Point un")), d.slides[0].body)
        assertEquals(File(f.parentFile, "ancien.pptx"), DeckFiles.target(f))
    }
}
