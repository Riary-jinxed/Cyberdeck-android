package dev.netrunner.aska

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskaTest {

    @Test
    fun readsOllamaStream() {
        assertEquals(Ollama.Chunk("Salut", false), Ollama.parseChunk("""{"model":"gemma4","message":{"role":"assistant","content":"Salut"},"done":false}"""))
        assertEquals(Ollama.Chunk("", true), Ollama.parseChunk("""{"model":"gemma4","message":{"role":"assistant","content":""},"done":true,"total_duration":1}"""))
        assertEquals("model not found", Ollama.parseChunk("""{"error":"model not found"}""")?.error)
        assertNull(Ollama.parseChunk(""))
        assertNull(Ollama.parseChunk("pas du json"))
    }

    @Test
    fun readsPullProgress() {
        val p = Ollama.parseProgress("""{"status":"pulling 4c27e0f5","digest":"sha256:4c27","total":4000,"completed":1000}""")
        assertEquals(0.25f, p?.fraction)
        assertNull(Ollama.parseProgress("""{"status":"success"}""")?.fraction)
        assertEquals("pull model manifest: file does not exist", Ollama.parseProgress("""{"error":"pull model manifest: file does not exist"}""")?.error)
    }

    @Test
    fun coreChoicesFitTheDisk() {
        assertTrue(Core.choices.all { it.tag.startsWith("gemma4:") && it.bytes > 0 })
        assertEquals("4,3 Go", Core.gigabytes(4_300_000_000))
        assertTrue(Core.freeSpace(File("/inexistant/dossier/profond")) > 0)
    }

    @Test
    fun picksAModel() {
        val installed = Ollama.parseModels("""{"models":[{"name":"llama3.2:3b"},{"name":"gemma4:latest"}]}""")
        assertEquals(listOf("llama3.2:3b", "gemma4:latest"), installed)
        assertEquals("gemma4:latest", Ollama.pick("", installed))
        assertEquals("mistral", Ollama.pick("mistral", installed))
        assertEquals("llama3.2:3b", Ollama.pick("", listOf("llama3.2:3b")))
        assertNull(Ollama.pick("", emptyList()))
    }

    @Test
    fun posesFollowWhatMatters() {
        assertEquals(Pose.Thinking, Pose.pick(true, false, Pose.Shy, true, null, 12, Pose.Sun))
        assertEquals(Pose.Shy, Pose.pick(false, false, Pose.Shy, true, null, 12, Pose.Sun))
        assertEquals(Pose.Sleep, Pose.pick(false, false, null, true, Battery(5, false), 12, Pose.Sun))
        assertEquals(Pose.LowBattery, Pose.pick(false, false, null, false, Battery(5, false), 12, Pose.Sun))
        assertEquals(Pose.Night, Pose.pick(false, false, null, false, Battery(5, true), 23, Pose.Sun))
        assertEquals(Pose.Rain, Pose.pick(false, false, null, false, null, 15, Pose.Rain))
        assertEquals(Pose.Waiting, Pose.pick(false, false, null, false, null, 15, null))
    }

    @Test
    fun everyPoseHasItsSprite() {
        Pose.entries.forEach { assertTrue(javaClass.getResource("/sprites/${it.sprite}.png") != null, it.sprite) }
    }

    @Test
    fun weatherCodesToPoses() {
        assertEquals(Pose.Storm, Weather.read(95, 20.0, 10.0, true).pose)
        assertEquals(Pose.Snow, Weather.read(73, -2.0, 10.0, true).pose)
        assertEquals(Pose.Rain, Weather.read(61, 12.0, 10.0, true).pose)
        assertEquals(Pose.Fog, Weather.read(45, 8.0, 5.0, true).pose)
        assertEquals(Pose.Wind, Weather.read(3, 12.0, 55.0, true).pose)
        assertEquals(Pose.Heat, Weather.read(0, 33.0, 5.0, true).pose)
        assertEquals(Pose.Cold, Weather.read(1, -4.0, 5.0, true).pose)
        assertEquals(Pose.Cloudy, Weather.read(3, 14.0, 5.0, true).pose)
        assertEquals(Pose.Sun, Weather.read(0, 20.0, 5.0, true).pose)
    }

    @Test
    fun readsTheKit() {
        val dir = Files.createTempDirectory("kit").toFile()
        File(dir, "kit.json").writeText("""{"equipment":[{"id":"1","name":"Fluke"},{"id":"2","name":"Switch"}]}""")
        File(dir, "kit_stock.json").writeText(
            """{"stock":[{"id":"a","name":"RJ45","unit":"pcs","qty":3,"threshold":10},{"id":"b","name":"Colliers","qty":50,"threshold":10},{"id":"c","name":"Gaine","qty":0,"threshold":0}]}""",
        )
        File(dir, "kit_log.json").writeText("""{"interventions":[{"id":"x","title":"Baie","site":"Lyon","ended":0},{"id":"y","title":"Wifi","ended":1700000000000}]}""")
        val config = File(dir, "decklink.properties").apply { writeText("last=24/10 13:20\n") }
        val summary = DeckLink.read(dir, config)
        assertEquals(DeckLink.Summary("24/10 13:20", 2, listOf("RJ45 : 3 pcs"), listOf("Baie à Lyon")), summary)
        assertTrue(summary.note().contains("RJ45 : 3 pcs"))
        dir.deleteRecursively()
    }

    @Test
    fun hidesReasoning() {
        assertEquals("Coucou", Persona.visible("<think>bla\nbla</think>Coucou"))
        assertEquals("", Persona.visible("<think>en cours"))
    }

    @Test
    fun keepsAdultContentOut() {
        val prompt = Persona.systemPrompt("V")
        assertTrue(prompt.contains("Aucun contenu sexuel"))
        assertFalse(prompt.contains("explicite"))
    }

    @Test
    fun notesTheMoment() {
        val note = Persona.note(Persona.Moment(LocalDateTime.of(2026, 10, 25, 21, 5), app = "« Elden Ring » (eldenring)", weather = "pluie, 12 °C"))
        assertEquals("[il est dimanche 21:05 ; au premier plan : « Elden Ring » (eldenring) ; météo : pluie, 12 °C]", note)
    }

    @Test
    fun shellWindowsAreNotWorthARemark() {
        assertTrue(Foreground.isShell("explorer"))
        assertFalse(Foreground.isShell("eldenring"))
    }

    @Test
    fun handsTakeTurnsOnTheKeyboard() {
        (1L..200L).forEach { n ->
            val (row, col) = bongoKey(n)
            assertTrue(row in 0..2, "rang $row")
            if (n % 2L == 0L) assertTrue(col in 0..4, "touche $col à gauche") else assertTrue(col in 5..9, "touche $col à droite")
        }
        assertTrue((1L..40L).map { bongoKey(it) }.toSet().size > 10, "les touches varient")
    }
}
