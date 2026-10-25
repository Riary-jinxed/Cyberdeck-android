package dev.netrunner.aska

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpersTest {

    private val now = LocalDateTime.of(2026, 10, 25, 17, 0)

    @Test
    fun readsWhenToRemind() {
        assertEquals(now.plusMinutes(20), Reminders.parseWhen("20 min", now))
        assertEquals(now.plusMinutes(20), Reminders.parseWhen("dans 20 minutes", now))
        assertEquals(now.plusMinutes(15), Reminders.parseWhen("15", now))
        assertEquals(now.plusMinutes(90), Reminders.parseWhen("1h30", now))
        assertEquals(now.plusHours(2), Reminders.parseWhen("2 heures", now))
        assertEquals(now.plusSeconds(45), Reminders.parseWhen("45s", now))
        assertEquals(LocalDateTime.of(2026, 10, 25, 18, 30), Reminders.parseWhen("18:30", now))
        assertEquals(LocalDateTime.of(2026, 10, 25, 18, 0), Reminders.parseWhen("à 18h", now))
        // Already past today: tomorrow.
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 15), Reminders.parseWhen("9h15", now))
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), Reminders.parseWhen("demain", now))
        assertEquals(LocalDateTime.of(2026, 10, 26, 7, 0), Reminders.parseWhen("demain 7h", now))
        assertNull(Reminders.parseWhen("sortir le linge", now))
        assertNull(Reminders.parseWhen("25:00", now))
    }

    @Test
    fun splitsTheTimeFromWhatToRemind() {
        val (at, what) = assertNotNull(Reminders.split("20 min sortir le linge", now))
        assertEquals(now.plusMinutes(20), at)
        assertEquals("sortir le linge", what)
        assertEquals("appeler Léa", Reminders.split("demain 9h appeler Léa", now)?.second)
    }

    @Test
    fun commandsCarryTheirArguments() {
        val reminder = assertNotNull(Tools.parseCommand("/rappel 1h30 lancer le rendu"))
        assertEquals("set_reminder", reminder.tool.name)
        assertEquals("1h30", reminder.args["when"])
        assertEquals("lancer le rendu", reminder.args["text"])
        assertEquals("start", Tools.parseCommand("/pomodoro 50")?.args?.get("action"))
        assertEquals("50", Tools.parseCommand("/pomodoro 50")?.args?.get("minutes"))
        assertEquals("stop", Tools.parseCommand("/pomodoro stop")?.args?.get("action"))
        assertEquals("Warframe", Tools.parseCommand("/lance Warframe")?.args?.get("name"))
        val translate = assertNotNull(Tools.parseCommand("/traduis"))
        assertEquals("read_clipboard", translate.tool.name)
        assertTrue(translate.instruction.startsWith("Traduis"))
        assertEquals(Tools.EXPLAIN, Tools.parseCommand("/sante")?.instruction)
        assertEquals(Tools.all.size, Tools.all.map { it.command }.toSet().size)
    }

    @Test
    fun pomodoroAlternatesWorkAndBreaks() {
        val start = 0L
        val f = Focus(workMinutes = 25, start = start)
        assertNull(f.step(start + 60_000))
        assertTrue(f.step(start + 25 * 60_000L)!!.contains("Pause de 5 min"))
        assertEquals(false, f.working)
        assertTrue(f.step(start + 30 * 60_000L)!!.contains("on repart"))
        repeat(3) { i ->
            val base = start + 30 * 60_000L + i * 30 * 60_000L
            f.step(base + 25 * 60_000L)
            if (i < 2) f.step(base + 30 * 60_000L)
        }
        assertEquals(4, f.rounds)
    }

    @Test
    fun worriesOnlyWhenItMatters() {
        val fine = Health(20, 8_000_000_000, 32_000_000_000, listOf(Health.Disk("C:", 200_000_000_000, 1_000_000_000_000)), Health.Gpu("RTX 3070 Ti", 60, 10, 1, 8), null)
        assertTrue(fine.alerts().isEmpty())
        val hot = fine.copy(ramUsed = 31_000_000_000, gpu = fine.gpu!!.copy(temp = 88), disks = listOf(Health.Disk("C:", 3_000_000_000, 1_000_000_000_000)))
        assertEquals(listOf("ram", "diskC:", "gpu"), hot.alerts().map { it.substringBefore("|") })
        assertTrue(hot.text().contains("RTX 3070 Ti : 88 °C"))
    }

    @Test
    fun findsProgramsByName() {
        val dir = File("x")
        val all = listOf("Discord", "Steam", "Warframe", "Microsoft Edge", "Visual Studio Code", "ComfyUI Desktop").map { Launcher.Entry(it, dir) }
        assertEquals(listOf("Steam"), Launcher.find("steam", all).map { it.name })
        assertEquals(listOf("Visual Studio Code"), Launcher.find("code", all).map { it.name })
        assertEquals(listOf("ComfyUI Desktop"), Launcher.find("comfy", all).map { it.name })
        assertTrue(Launcher.find("photoshop", all).isEmpty())
    }

    @Test
    fun readsSongsAndUnreadFromTitles() {
        val windows = listOf(
            OpenWindow("Spotify Premium", "Spotify"),
            OpenWindow("Daft Punk - Veridis Quo", "Spotify"),
            OpenWindow("(3) Discord | #général | Netrunners", "Discord"),
            OpenWindow("Telegram (12)", "Telegram"),
            OpenWindow("Boîte de réception (2) - moi@gmail.com - Gmail - Google Chrome", "chrome"),
            OpenWindow("Bloc-notes", "notepad"),
        )
        assertEquals("Daft Punk - Veridis Quo", OpenWindows.song(windows)?.title)
        assertNull(OpenWindows.song(listOf(OpenWindow("Spotify Premium", "Spotify"))))
        assertEquals("Get Lucky - Daft Punk", OpenWindows.song(listOf(OpenWindow("Get Lucky - Daft Punk - YouTube Music - Google Chrome", "chrome")))?.title)
        assertEquals(mapOf("Discord" to 3, "Telegram" to 12, "Gmail" to 2), OpenWindows.unread(windows))
    }

    @Test
    fun readsSteamFiles() {
        val vdf = Vdf.parse(
            """
            "UserLocalConfigStore"
            {
                "Software" { "valve" { "Steam" { "apps" {
                    "230410" { "LastPlayed" "1761400000" "Playtime" "6123" "Playtime2wks" "300" }
                } } } }
            }
            """,
        )
        val apps = assertNotNull(vdf.path("UserLocalConfigStore", "Software", "Valve", "Steam", "apps"))
        assertEquals("6123", (apps["230410"] as Map<*, *>)["Playtime"])
        assertEquals("102 h 03", Steam.hours(6123))
        assertEquals("45 min", Steam.hours(45))
    }

    @Test
    fun announcesFilesOnceTheyStopGrowing() {
        val dir = Files.createTempDirectory("aska-watch").toFile()
        File(dir, "old.zip").writeText("old")
        val watch = FolderWatch()
        assertTrue(watch.check(listOf(dir)).isEmpty())
        File(dir, "lora.safetensors").writeText("weights")
        File(dir, "film.mkv.part").writeText("half")
        assertTrue(watch.check(listOf(dir)).isEmpty())
        assertEquals(listOf("lora.safetensors"), watch.check(listOf(dir)).map { it.name })
        assertTrue(watch.check(listOf(dir)).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun spotsGamesInFront() {
        assertTrue(Foreground.isGame(Foreground("Elden Ring", "eldenring", true, "D:\\SteamLibrary\\steamapps\\common\\ELDEN RING\\Game\\eldenring.exe")))
        assertTrue(Foreground.isGame(Foreground("Warframe", "Warframe.x64", false)))
        assertTrue(!Foreground.isGame(Foreground("Doc", "WINWORD", false, "C:\\Program Files\\Microsoft Office\\WINWORD.EXE")))
    }

    @Test
    fun shrinksPicturesForHerEyes() {
        val big = java.awt.image.BufferedImage(3840, 2160, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val back = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(Screen.encode(big))))
        assertEquals(1536, back.width)
        assertEquals(864, back.height)
    }

    @Test
    fun fillsComfyWorkflows() {
        val marked = """{"6": {"class_type": "CLIPTextEncode", "inputs": {"text": "POSITIVE_PROMPT", "clip": ["4", 0]}},
            "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "NEGATIVE_PROMPT"}},
            "3": {"class_type": "KSampler", "inputs": {"seed": "SEED", "positive": ["6", 0], "negative": ["7", 0]}}}"""
        val a = Studio.fill(marked, "ashka_chibi, 1girl", "red eyes", 42)
        assertTrue(a.toString().contains("\"text\":\"ashka_chibi, 1girl\""))
        assertTrue(a.toString().contains("\"seed\":42"))
        val plain = marked.replace("POSITIVE_PROMPT", "old").replace("NEGATIVE_PROMPT", "bad").replace("\"SEED\"", "1")
        val b = Studio.fill(plain, "new prompt", "worse", 7).toString()
        assertTrue(b.contains("\"text\":\"new prompt\""))
        assertTrue(b.contains("\"text\":\"worse\""))
        assertTrue(b.contains("\"seed\":7"))
    }
}
