package dev.netrunner.os.web

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacesTest {
    @Test fun addresses() {
        assertEquals("https://wikipedia.org", Address.resolve("wikipedia.org"))
        assertEquals("https://fr.wikipedia.org/wiki/Linux", Address.resolve(" fr.wikipedia.org/wiki/Linux "))
        assertEquals("https://exemple.fr", Address.resolve("https://exemple.fr"))
        assertEquals("http://localhost:8080/x", Address.resolve("localhost:8080/x"))
        assertEquals("http://192.168.1.10:47322", Address.resolve("192.168.1.10:47322"))
        assertEquals("file:///home/netrunner/a.html", Address.resolve("/home/netrunner/a.html"))
        assertEquals("about:blank", Address.resolve("about:blank"))
        assertEquals(Address.search("météo Paris"), Address.resolve("météo Paris"))
        assertEquals(Address.search("linux"), Address.resolve("linux"))
        assertTrue(Address.search("a&b").endsWith("q=a%26b"))
        assertEquals("exemple.fr/page", Address.short("https://www.exemple.fr/page/"))
    }

    @Test fun historyAndBookmarksPersist() {
        val dir = Files.createTempDirectory("web").toFile()
        val p = Places(dir)
        p.visit("https://a.fr/", "Été à Paris", 1)
        p.visit("https://b.fr/", "B", 2)
        p.visit("https://a.fr/", "", 3)
        p.visit("about:blank", "rien", 4)
        assertEquals(listOf("https://a.fr/", "https://b.fr/"), p.history.map { it.url })
        assertEquals("Été à Paris", p.history.first().title)
        assertTrue(p.toggleBookmark("https://b.fr/", "B"))
        val again = Places(dir)
        assertEquals(p.history, again.history)
        assertTrue(again.isBookmarked("https://b.fr/"))
        assertEquals(listOf("https://a.fr/"), again.search("ete paris").map { it.url })
        assertFalse(again.toggleBookmark("https://b.fr/", "B"))
        assertFalse(Places(dir).isBookmarked("https://b.fr/"))
    }
}
