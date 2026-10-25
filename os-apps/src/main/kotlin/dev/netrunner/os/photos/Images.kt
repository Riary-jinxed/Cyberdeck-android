package dev.netrunner.os.photos

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Folders, photos and their decoding, for Netrunner Photos. No UI here. */
object Images {
    val Extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun isImage(f: File) = f.isFile && f.extension.lowercase() in Extensions && !f.name.startsWith(".")

    class Folder(val dir: File, val subfolders: List<File>, val images: List<File>)

    /** [dir]'s visible subfolders and photos, both in natural order (IMG_2 before IMG_10). */
    fun list(dir: File): Folder {
        val all = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
        return Folder(
            dir,
            all.filter { it.isDirectory }.sortedWith(Natural),
            all.filter { isImage(it) }.map { it.canonicalFile }.sortedWith(Natural),
        )
    }

    /** Compares names with their numbers as numbers, ignoring case. */
    val Natural = Comparator<File> { a, b -> natural(a.name, b.name) }

    fun natural(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val pa = re.findAll(a.lowercase()).map { it.value }.toList()
        val pb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
            } else {
                x.compareTo(y)
            }
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    /** Where to start: the folder of a photo given on the command line (shown first), a folder, or Images. */
    class Start(val dir: File, val file: File?)

    fun start(args: List<String>, home: File = File(System.getProperty("user.home"))): Start {
        val arg = args.firstOrNull()?.let {
            if (it.startsWith("file://")) File(java.net.URI(it)) else File(it)
        }
        return when {
            arg != null && arg.isDirectory -> Start(arg.canonicalFile, null)
            arg != null && arg.isFile -> Start(arg.canonicalFile.parentFile, arg.canonicalFile)
            else -> Start(pictures(home), null)
        }
    }

    /** The user's pictures folder (xdg-user-dir), else ~/Images, ~/Pictures or home. */
    fun pictures(home: File): File {
        val xdg = runCatching {
            val p = ProcessBuilder("xdg-user-dir", "PICTURES").redirectErrorStream(true).start()
            val out = p.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).trim()
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) File(out) else null
        }.getOrNull()
        return listOfNotNull(xdg, File(home, "Images"), File(home, "Pictures")).firstOrNull { it.isDirectory && it != home } ?: home
    }

    /**
     * The EXIF orientation of a JPEG, as the clockwise rotation (0, 90, 180, 270) that shows it
     * upright: phones save photos sideways and say so here. Mirrored orientations count as 0.
     */
    fun exifRotation(b: ByteArray): Int {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return 0
        var i = 2
        while (i + 4 <= b.size && u8(i) == 0xFF) {
            val marker = u8(i + 1)
            if (marker == 0xD9 || marker == 0xDA) return 0
            val len = (u8(i + 2) shl 8) or u8(i + 3)
            if (marker == 0xE1 && i + 10 <= b.size && String(b, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                return tiffRotation(b, i + 10, minOf(b.size, i + 2 + len))
            }
            i += 2 + len
        }
        return 0
    }

    private fun tiffRotation(b: ByteArray, t: Int, end: Int): Int {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        if (t + 8 > end) return 0
        val little = u8(t) == 'I'.code
        fun u16(i: Int) = if (little) u8(i) or (u8(i + 1) shl 8) else (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int) = if (little) u16(i).toLong() or (u16(i + 2).toLong() shl 16) else (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        val ifd = t + u32(t + 4).toInt()
        if (ifd + 2 > end || ifd < t) return 0
        for (n in 0 until u16(ifd)) {
            val e = ifd + 2 + n * 12
            if (e + 12 > end) return 0
            if (u16(e) == 0x0112) {
                return when (u16(e + 8)) {
                    3 -> 180
                    6 -> 90
                    8 -> 270
                    else -> 0
                }
            }
        }
        return 0
    }

    /** A decoded photo, at most [maxSide] pixels on its long side; [width] × [height] are the file's. */
    class Picture(val image: ImageBitmap?, val rotation: Int, val width: Int, val height: Int)

    /** Never throws: a file Skia cannot read (HEIC, damaged…) gives a Picture without image. */
    fun decode(file: File, maxSide: Int): Picture = runCatching {
        val bytes = file.readBytes()
        val rotation = exifRotation(bytes)
        val image = Image.makeFromEncoded(bytes)
        try {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(image.width, image.height))
            if (scale >= 1f) return@runCatching Picture(image.toComposeImageBitmap(), rotation, image.width, image.height)
            val w = maxOf(1, (image.width * scale).toInt())
            val h = maxOf(1, (image.height * scale).toInt())
            val surface = Surface.makeRasterN32Premul(w, h)
            try {
                surface.canvas.drawImageRect(
                    image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
                    SamplingMode.MITCHELL, null, true,
                )
                val small = surface.makeImageSnapshot()
                Picture(small.toComposeImageBitmap(), rotation, image.width, image.height).also { small.close() }
            } finally {
                surface.close()
            }
        } finally {
            image.close()
        }
    }.getOrElse { Picture(null, 0, 0, 0) }

    /** Thumbnails decoded three at a time, the latest 600 kept in memory. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val thumbnails = Dispatchers.IO.limitedParallelism(3)
    private val cache = object : LinkedHashMap<String, Picture>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Picture>?) = size > 600
    }

    fun thumbnail(file: File): Picture {
        val key = file.path + "@" + file.lastModified()
        synchronized(cache) { cache[key] }?.let { return it }
        return decode(file, 320).also { synchronized(cache) { cache[key] = it } }
    }

    /** Sends [file] to the desktop's trash (gio, as the file manager does). */
    fun trash(file: File): Boolean = runCatching {
        val p = ProcessBuilder("gio", "trash", file.path).redirectErrorStream(true).start()
        p.inputStream.use { it.readBytes() }
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    /** "4032 × 3024 · 2,4 Mo · 12/08/2026 14:32". */
    fun describe(file: File, p: Picture?): String {
        val parts = mutableListOf<String>()
        if (p != null && p.width > 0) {
            parts += if (p.rotation % 180 == 0) "${p.width} × ${p.height}" else "${p.height} × ${p.width}"
        }
        parts += size(file.length())
        parts += SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(file.lastModified()))
        return parts.joinToString(" · ")
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.FRANCE, "%.1f Mo", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "${bytes shr 10} Ko"
        else -> "$bytes o"
    }
}
