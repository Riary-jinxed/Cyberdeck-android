package dev.netrunner.os.text

import dev.netrunner.os.text.Doc.Kind
import dev.netrunner.os.text.Doc.Style
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    private val md = "# Compte rendu\nLe **serveur** est *reparti*.\n- câbles\n- ~~vieux~~ switch\n1. tester\n> à suivre"

    @Test fun parsesAndWritesBack() {
        val d = Markdown.parse(md)
        assertEquals("Compte rendu\nLe serveur est reparti.\ncâbles\nvieux switch\ntester\nà suivre", d.text)
        assertEquals(listOf(Kind.Heading1, Kind.Normal, Kind.Bullet, Kind.Bullet, Kind.Numbered, Kind.Quote), d.paras.map { it.kind })
        assertEquals(listOf(Style.Bold, Style.Italic, Style.Strike), d.spans.map { it.style })
        assertEquals("serveur", d.text.substring(d.spans[0].start, d.spans[0].end))
        assertEquals(md, Markdown.write(d))
    }

    @Test fun insertsAFragment() {
        val d = Doc.of("Début\nFin")
        // At the start of "Fin": its paragraphs come in with their kinds.
        val after = d.insert(6, 6, Markdown.parse("## Partie\n- point **clé**\n"))
        assertEquals("Début\nPartie\npoint clé\nFin", after.text)
        assertEquals(listOf(Kind.Normal, Kind.Heading2, Kind.Bullet, Kind.Normal), after.paras.map { it.kind })
        assertEquals("clé", after.text.substring(after.spans.single().start, after.spans.single().end))
        // In the middle of a paragraph, the first line joins it as it is.
        val mid = Doc.of("ab").insert(1, 1, Markdown.parse("# X"))
        assertEquals(listOf(Kind.Normal), mid.paras.map { it.kind })
        assertEquals("aXb", mid.text)
    }
}
