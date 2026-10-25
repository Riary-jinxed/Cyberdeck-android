package dev.netrunner.aska

import dev.netrunner.bridge.Bridge
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsAppsTest {
    private class Album : Bridge.App {
        var trashed = 0
        override val id = "photos"
        override val title = "Netrunner Photos"
        override fun context() = "affiche la photo chat.jpg"
        override fun tools() = listOf(
            Bridge.Tool("photos_next", "Passe à la photo suivante."),
            Bridge.Tool("photos_trash", "Met la photo affichée à la corbeille. Demande d'abord.", confirm = true),
        )
        override fun run(tool: String, args: Map<String, String>) = if (tool == "photos_trash") "corbeille ${++trashed}" else "suivante"
    }

    @Test fun openAppsBecomeToolsAndConfirmationsWait() {
        val dir = Files.createTempDirectory("bridge").toFile()
        val album = Album()
        val stop = Bridge.serve(album, dir)
        try {
            val apps = Bridge.discover(dir)
            assertTrue(OsApps.prompt(apps)!!.contains("Netrunner Photos : affiche la photo chat.jpg"))
            assertNull(OsApps.prompt(emptyList()))
            val defs = OsApps.definitions(apps)
            assertEquals(listOf("photos_next", "photos_trash"), defs.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
            assertEquals("suivante", OsApps.run(OsApps.find(apps, "photos_next")!!, emptyMap()) { error("no confirmation needed") })
            var held: OsApps.Pending? = null
            val answer = OsApps.run(OsApps.find(apps, "photos_trash")!!, emptyMap()) { held = it }
            assertTrue(answer.startsWith("Prêt, mais PAS encore fait"), answer)
            assertEquals(0, album.trashed)
            val pending = assertNotNull(held)
            assertEquals("corbeille 1", Bridge.run(pending.socket, pending.tool, pending.args))
            assertNull(OsApps.find(apps, "ping"))
        } finally {
            stop()
        }
    }
}
