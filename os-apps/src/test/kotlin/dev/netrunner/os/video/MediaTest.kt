package dev.netrunner.os.video

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaTest {
    @Test fun timesBothWays() {
        assertEquals("0:05", Media.time(5_400))
        assertEquals("12:34", Media.time(754_000))
        assertEquals("1:02:03", Media.time(3_723_000))
        assertEquals(83_000L, Media.parseTime("83"))
        assertEquals(83_000L, Media.parseTime("1:23"))
        assertEquals(3_723_000L, Media.parseTime("1:02:03"))
        assertEquals(150_000L, Media.parseTime("2m30"))
        assertEquals(150_000L, Media.parseTime("2 min 30"))
        assertEquals(3_900_000L, Media.parseTime("1h05"))
        assertEquals(3_723_000L, Media.parseTime("1h2m3s"))
        assertNull(Media.parseTime("bientôt"))
        assertNull(Media.parseTime(""))
    }

    @Test fun folderIsThePlaylist() {
        val dir = Files.createTempDirectory("videos").toFile()
        listOf("ep10.mkv", "ep2.mp4", "ep1.MP4", "cover.jpg", "theme.mp3").forEach { File(dir, it).writeText("x") }
        val (list, at) = Media.playlist(File(dir, "ep2.mp4"))
        assertEquals(listOf("ep1.MP4", "ep2.mp4", "ep10.mkv", "theme.mp3"), list.map { it.name })
        assertEquals(1, at)
        assertEquals(0, Media.playlist(dir).second)
    }
}
