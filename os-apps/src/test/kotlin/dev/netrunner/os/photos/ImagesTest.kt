package dev.netrunner.os.photos

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImagesTest {
    /** A JPEG start with only an EXIF block holding [orientation], in either byte order. */
    private fun jpeg(orientation: Int, little: Boolean): ByteArray {
        fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (little) u16(v) + u16(v shr 16) else u16(v shr 16) + u16(v)
        val tiff = (if (little) "II".toByteArray() else "MM".toByteArray()) + u16(42) + u32(8) +
            u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + u32(0)
        val app1 = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = app1.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + app1 +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte())
    }

    @Test fun exifOrientationBecomesRotation() {
        for (little in listOf(true, false)) {
            assertEquals(90, Images.exifRotation(jpeg(6, little)))
            assertEquals(180, Images.exifRotation(jpeg(3, little)))
            assertEquals(270, Images.exifRotation(jpeg(8, little)))
            assertEquals(0, Images.exifRotation(jpeg(1, little)))
        }
        assertEquals(0, Images.exifRotation(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals(0, Images.exifRotation(jpeg(6, true).copyOf(20)))
    }

    @Test fun foldersListPhotosInNaturalOrder() {
        val dir = Files.createTempDirectory("photos").toFile()
        listOf("IMG_10.jpg", "IMG_2.JPG", "img_1.png", "notes.txt", ".hidden.jpg").forEach { File(dir, it).writeText("x") }
        File(dir, "Vacances").mkdir()
        File(dir, ".cache").mkdir()
        val folder = Images.list(dir)
        assertEquals(listOf("img_1.png", "IMG_2.JPG", "IMG_10.jpg"), folder.images.map { it.name })
        assertEquals(listOf("Vacances"), folder.subfolders.map { it.name })
        val start = Images.start(listOf(File(dir, "IMG_2.JPG").path))
        assertEquals(dir.canonicalFile, start.dir)
        assertEquals(1, folder.images.indexOf(start.file))
        assertNull(Images.start(listOf(dir.path)).file)
    }

    @Test fun unreadableFileGivesNoImage() {
        val f = Files.createTempFile("bad", ".jpg").toFile().apply { writeText("pas une image") }
        assertNull(Images.decode(f, 100).image)
    }
}
