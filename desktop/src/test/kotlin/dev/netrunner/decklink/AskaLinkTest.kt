package dev.netrunner.decklink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AskaLinkTest {

    @Test
    fun acceptsKnownFieldsOnly() {
        assertEquals(
            Triple("stock", "s1", mapOf<String, Any>("qty" to 7L)),
            AskaLink.parse("""{"kind":"stock","id":"s1","fields":{"qty":"7"}}"""),
        )
        assertEquals(
            Triple("cable", "c1", mapOf<String, Any>("vlan" to "30", "notes" to "testé")),
            AskaLink.parse("""{"kind":"cable","id":"c1","fields":{"vlan":"30","notes":"testé"}}"""),
        )
        // Unknown kind, unknown field, a word for a number, no id, nothing to change.
        assertNull(AskaLink.parse("""{"kind":"photo","id":"p","fields":{"name":"x"}}"""))
        assertNull(AskaLink.parse("""{"kind":"stock","id":"s1","fields":{"updated":"1"}}"""))
        assertNull(AskaLink.parse("""{"kind":"stock","id":"s1","fields":{"qty":"beaucoup"}}"""))
        assertNull(AskaLink.parse("""{"kind":"stock","fields":{"qty":"1"}}"""))
        assertNull(AskaLink.parse("""{"kind":"stock","id":"s1","fields":{}}"""))
    }
}
