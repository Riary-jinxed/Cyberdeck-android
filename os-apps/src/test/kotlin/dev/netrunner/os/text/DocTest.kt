package dev.netrunner.os.text

import dev.netrunner.os.text.Doc.Kind
import dev.netrunner.os.text.Doc.Span
import dev.netrunner.os.text.Doc.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocTest {
    @Test fun typingKeepsStylesInPlace() {
        // "Bonjour monde" with "monde" bold.
        var d = Doc.of("Bonjour monde").set(Style.Bold, 8, 13, true)
        // Typing plain text before it moves it.
        d = d.edit("Bonjour le monde", emptySet())
        assertEquals(listOf(Span(11, 16, Style.Bold)), d.spans)
        // Typing at its end with bold on extends it; without, it does not.
        assertEquals(listOf(Span(11, 17, Style.Bold)), d.edit("Bonjour le monde!", setOf(Style.Bold)).spans)
        assertEquals(listOf(Span(11, 16, Style.Bold)), d.edit("Bonjour le monde!", emptySet()).spans)
        // Deleting inside it shrinks it, deleting all of it drops it.
        assertEquals(listOf(Span(11, 14, Style.Bold)), d.edit("Bonjour le mde", emptySet()).spans)
        assertEquals(emptyList(), d.edit("Bonjour le ", emptySet()).spans)
        // Replacing a word inside takes the typing styles only.
        assertEquals(listOf(Span(11, 16, Style.Bold), Span(8, 10, Style.Italic)).sortedBy { it.start }, d.set(Style.Italic, 8, 10, true).spans.sortedBy { it.start })
        assertEquals("Bold", d.stylesAt(16).single().name)
        assertTrue(d.stylesAt(11).isEmpty())
    }

    @Test fun toggleAddsThenRemoves() {
        val d = Doc.of("abcdef").set(Style.Italic, 0, 3, true)
        assertFalse(d.has(Style.Italic, 0, 6))
        val all = d.toggle(Style.Italic, 0, 6)
        assertEquals(listOf(Span(0, 6, Style.Italic)), all.spans)
        assertEquals(listOf(Span(0, 2, Style.Italic), Span(4, 6, Style.Italic)), all.toggle(Style.Italic, 2, 4).spans)
    }

    @Test fun enterSplitsAndBackspaceMerges() {
        var d = Doc("Titre\nCorps", listOf(Doc.Para(Kind.Heading1), Doc.Para(Kind.Normal)))
        // Enter at the end of a heading: the new paragraph is normal text.
        d = d.edit("Titre\n\nCorps", emptySet())
        assertEquals(listOf(Kind.Heading1, Kind.Normal, Kind.Normal), d.paras.map { it.kind })
        // Enter in the middle of a heading: both halves stay headings.
        val split = Doc("TitreLong", listOf(Doc.Para(Kind.Heading2))).edit("Titre\nLong", emptySet())
        assertEquals(listOf(Kind.Heading2, Kind.Heading2), split.paras.map { it.kind })
        // A list goes on.
        val list = Doc("un", listOf(Doc.Para(Kind.Bullet))).edit("un\n", emptySet())
        assertEquals(listOf(Kind.Bullet, Kind.Bullet), list.paras.map { it.kind })
        // Backspace at the start of a paragraph merges it into the one above.
        d = d.edit("Titre\nCorps", emptySet())
        assertEquals(listOf(Kind.Heading1, Kind.Normal), d.paras.map { it.kind })
        // Pasting several lines in the middle.
        val pasted = Doc.of("ab").edit("a1\n2\n3b", emptySet())
        assertEquals(3, pasted.paras.size)
        assertEquals("2", pasted.paragraphText(1))
        assertEquals(5 until 7, pasted.range(2))
        assertEquals(2, pasted.paragraphAt(5))
    }

    @Test fun stats() {
        assertEquals("3 mots · 13 caractères · 2 paragraphes", Doc.of("Salut l'ami\nok").stats().replace("13", "13"))
    }
}
