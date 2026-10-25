package dev.netrunner.bridge

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeTest {
    private class Counter : Bridge.App {
        var n = 0
        override val id = "test"
        override val title = "Compteur"
        override fun context() = "Le compteur vaut $n."
        override fun tools() = listOf(
            Bridge.Tool("test_add", "Ajoute au compteur.", listOf(Bridge.Param("by", "combien", "integer"))),
            Bridge.Tool("test_reset", "Remet à zéro.", confirm = true),
        )
        override fun run(tool: String, args: Map<String, String>): String = when (tool) {
            "test_add" -> { n += args["by"]?.toIntOrNull() ?: 1; "Compteur à $n" }
            else -> { n = 0; "Remis à zéro" }
        }
    }

    @Test fun appIsFoundAndDriven() {
        val dir = Files.createTempDirectory("bridge").toFile()
        val app = Counter()
        val stop = Bridge.serve(app, dir)
        try {
            val found = Bridge.discover(dir).single()
            assertEquals("test", found.app)
            assertEquals("Le compteur vaut 0.", found.context)
            assertEquals(listOf("test_add", "test_reset"), found.tools.map { it.name })
            assertEquals("integer", found.tools[0].params.single().type)
            assertTrue(found.tools[1].confirm)
            assertEquals("Compteur à 3", Bridge.run(found.socket, "test_add", mapOf("by" to "3")))
            assertTrue(Bridge.run(found.socket, "photos_next", emptyMap()).startsWith("Outil inconnu"))
            assertEquals("Le compteur vaut 3.", Bridge.discover(dir).single().context)
        } finally {
            stop()
        }
        // Closed app: nothing left to find.
        assertEquals(0, Bridge.discover(dir).size)
    }
}
