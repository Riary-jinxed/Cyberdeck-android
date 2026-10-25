package dev.netrunner.os.montage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MontageTest {
    private val sample = Project(
        listOf(
            Clip(Clip.Kind.Title, text = "Vacances\n2026", end = 2000),
            Clip(Clip.Kind.Video, "/v/plage.mp4", 1000, 6000, sourceLength = 20_000),
            Clip(Clip.Kind.Image, "/p/coucher.jpg", 0, 3000),
        ),
        Music("/m/musique.mp3", 40),
    )

    @Test fun timeline() {
        assertEquals(10_000, sample.duration)
        assertEquals(7000, sample.startOf(2))
        assertEquals(1 to 500L, sample.at(2500))
        assertEquals(2 to 3000L, sample.at(99_000))
        val cut = sample.split(4000)
        assertEquals(4, cut.clips.size)
        assertEquals(Clip(Clip.Kind.Video, "/v/plage.mp4", 1000, 3000, sourceLength = 20_000), cut.clips[1])
        assertEquals(3000L, cut.clips[2].start)
        assertEquals(sample.duration, cut.duration)
        assertEquals(sample, sample.split(2000), "on a cut already")
        assertEquals("plage.mp4", sample.move(1, 0).clips[0].name)
    }

    @Test fun trimStaysInside() {
        val c = sample.clips[1]
        assertEquals(19_800L to 20_000L, c.trim(25_000, 30_000).let { it.start to it.end })
        assertEquals(0L to 200L, c.trim(-5, 10).let { it.start to it.end })
        assertEquals(0L to 4500L, sample.clips[2].trim(500, 5000).let { it.start to it.end })
    }

    @Test fun clocks() {
        assertEquals("1:15,5", clock(75_500))
        assertEquals("1:02:03,0", clock(3_723_000))
        assertEquals(75_500L, parseClock("1:15,5"))
        assertEquals(75_000L, parseClock("75"))
        assertNull(parseClock("abc"))
    }

    @Test fun projectFileRoundTrip() {
        val f = File(Files.createTempDirectory("montage").toFile(), "vacances.montage")
        Project.write(sample, f)
        assertEquals(sample, Project.read(f))
    }

    private fun ffmpeg(vararg args: String) {
        val p = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { out }
    }

    /** A real export, when ffmpeg is there: two videos (one silent), a photo, a title, music. */
    @Test fun rendersWithFfmpeg() {
        if (runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() }.getOrNull() != 0) return
        val dir = Files.createTempDirectory("rendu").toFile()
        val a = File(dir, "a.mp4"); ffmpeg("-f", "lavfi", "-i", "testsrc=s=640x360:r=25:d=4", "-f", "lavfi", "-i", "sine=f=440:d=4", "-shortest", "-c:v", "libx264", "-c:a", "aac", a.path)
        val b = File(dir, "b.mp4"); ffmpeg("-f", "lavfi", "-i", "testsrc2=s=480x640:r=30:d=3", "-c:v", "libx264", b.path)
        val img = File(dir, "photo.png"); ffmpeg("-f", "lavfi", "-i", "color=c=red:s=800x600", "-frames:v", "1", img.path)
        val mus = File(dir, "musique.wav"); ffmpeg("-f", "lavfi", "-i", "sine=f=220:d=2", mus.path)
        val font = listOf("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf").map(::File).firstOrNull { it.isFile } ?: return
        val probe = Probe.of(a)!!
        assertTrue(probe.audio && probe.video && probe.duration in 3900..4200, probe.toString())
        val p = Project(
            listOf(
                Clip(Clip.Kind.Title, text = "Été 2026 : « ça tourne » 100%", end = 1500),
                Clip(Clip.Kind.Video, a.path, 1000, 3000, sourceLength = probe.duration),
                Clip(Clip.Kind.Video, b.path, 0, 2000, sourceLength = 3000),
                Clip(Clip.Kind.Image, img.path, 0, 1000),
            ),
            Music(mus.path, 50),
            width = 1280, height = 720,
        )
        val out = File(dir, "film.mp4")
        var last = 0f
        val error = Render.run(Render.command(p, out, Render.Quality.Small, font, dir), p.duration, { last = it })
        assertNull(error)
        assertEquals(1f, last)
        val made = Probe.of(out)!!
        assertTrue(made.video && made.audio, made.toString())
        assertTrue(made.duration in 6300..6800, "durée ${made.duration}")
        assertEquals(852 to 480, made.width to made.height)
    }
}
