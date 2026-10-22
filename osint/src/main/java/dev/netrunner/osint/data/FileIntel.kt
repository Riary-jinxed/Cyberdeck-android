package dev.netrunner.osint.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.netrunner.osint.data.Report.Flag
import dev.netrunner.osint.data.Report.Link
import dev.netrunner.osint.data.Report.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** What a file says about itself: its EXIF metadata and its hashes. Everything runs on the phone. */
object FileIntel {

    data class Picked(val uri: Uri, val name: String, val size: Long, val mime: String)

    fun describe(context: Context, uri: Uri): Picked {
        var name = uri.lastPathSegment ?: "fichier"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return Picked(uri, name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    /** The photo as stored, GPS included: Android strips the position unless asked for the original. */
    private fun open(context: Context, uri: Uri): InputStream? {
        val original = runCatching { MediaStore.setRequireOriginal(MediaStore.getMediaUri(context, uri) ?: uri) }.getOrNull()
        if (original != null) runCatching { context.contentResolver.openInputStream(original) }.getOrNull()?.let { return it }
        return context.contentResolver.openInputStream(uri)
    }

    /** Tag names as written in the file, so tags newer than the platform's constants are read too. */
    private val TAGS = listOf(
        "Make" to "Fabricant",
        "Model" to "Modèle",
        "Software" to "Logiciel",
        "DateTimeOriginal" to "Prise le",
        "OffsetTimeOriginal" to "Fuseau",
        "DateTime" to "Modifiée le",
        "Artist" to "Auteur",
        "Copyright" to "Copyright",
        "ImageDescription" to "Description",
        "UserComment" to "Commentaire",
        "CameraOwnerName" to "Propriétaire",
        "BodySerialNumber" to "N° de série boîtier",
        "LensMake" to "Objectif (marque)",
        "LensModel" to "Objectif",
        "ImageUniqueID" to "ID unique",
        "PixelXDimension" to "Largeur",
        "PixelYDimension" to "Hauteur",
        ExifInterface.TAG_ORIENTATION to "Orientation",
        "ExposureTime" to "Exposition (s)",
        "FNumber" to "Ouverture f/",
        "ISOSpeedRatings" to "ISO",
        "FocalLength" to "Focale",
        "Flash" to "Flash",
        "GPSDateStamp" to "Date GPS",
        "GPSProcessingMethod" to "Méthode GPS",
    )

    /** The EXIF of an image, with the GPS position (and a map link) when there is one. */
    suspend fun exif(context: Context, file: Picked): Report = withContext(Dispatchers.IO) {
        val exif = open(context, file.uri)?.use { ExifInterface(it) } ?: return@withContext Report.failed("EXIF", file.name, "Fichier illisible.")
        val rows = mutableListOf<Row>()
        val links = mutableListOf<Link>()
        val latLong = FloatArray(2)
        if (exif.getLatLong(latLong)) {
            val (lat, lon) = latLong[0] to latLong[1]
            val alt = exif.getAltitude(Double.NaN).takeIf { !it.isNaN() }
            rows += Row("Position GPS", "%.6f, %.6f".format(Locale.ROOT, lat, lon) + (alt?.let { " · %.0f m".format(Locale.ROOT, it) } ?: ""), Flag.Warn)
            links += Link("Carte", "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon")
        }
        TAGS.forEach { (tag, label) ->
            val v = exif.getAttribute(tag)?.trim()?.trim('\u0000')
            if (!v.isNullOrBlank()) rows += Row(label, v, if (tag in SENSITIVE) Flag.Warn else Flag.None)
        }
        if (exif.hasThumbnail()) rows += Row("Miniature", "intégrée (peut montrer l'image avant recadrage)", Flag.Warn)
        val remark = when {
            rows.isEmpty() -> "Aucune métadonnée EXIF : déjà nettoyée, ou passée par un réseau social."
            rows.any { it.flag == Flag.Warn } -> "Les lignes en rouge peuvent identifier l'auteur ou le lieu : nettoie avant de partager."
            else -> null
        }
        Report("EXIF", file.name, rows, links, remark)
    }

    private val SENSITIVE = setOf(
        "Artist", "Copyright", "CameraOwnerName", "BodySerialNumber",
        "ImageUniqueID", "UserComment", "DateTimeOriginal",
    )

    // ---- Cleaning ----

    /**
     * The image without its metadata, and its mime type. JPEG and PNG are cleaned losslessly by dropping
     * the metadata blocks (EXIF, XMP, IPTC, comments, text chunks); the orientation is put back so the photo
     * still shows the right way up. Other formats are re-encoded as JPEG.
     */
    suspend fun clean(context: Context, file: Picked): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: throw java.io.IOException("Fichier illisible.")
        when {
            isJpeg(bytes) -> {
                val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val stripped = stripJpeg(bytes)
                (if (orientation > ExifInterface.ORIENTATION_NORMAL) withOrientation(context, stripped, orientation) else stripped) to "image/jpeg"
            }
            isPng(bytes) -> stripPng(bytes) to "image/png"
            else -> reencode(bytes) to "image/jpeg"
        }
    }

    private fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
    private fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    /** Keeps APP0 (JFIF), APP2 (ICC colour profile) and APP14 (Adobe colour transform); drops the other APPn and comments. */
    private fun stripJpeg(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) break
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue } // Padding.
            if (marker == 0xDA) break // Start of scan: the rest is image data.
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val end = i + 2 + len
            if (len < 2 || end > b.size) break
            val drop = (marker in 0xE1..0xEF && marker != 0xE2 && marker != 0xEE) || marker == 0xFE ||
                (marker == 0xE2 && !String(b, i + 4, minOf(11, len - 2), Charsets.ISO_8859_1).startsWith("ICC_PROFILE"))
            if (!drop) out.write(b, i, end - i)
            i = end
        }
        out.write(b, i, b.size - i)
        return out.toByteArray()
    }

    /** Puts back only the orientation tag, through a temporary file (ExifInterface writes files). */
    private fun withOrientation(context: Context, jpeg: ByteArray, orientation: Int): ByteArray {
        val tmp = File.createTempFile("clean", ".jpg", context.cacheDir)
        return try {
            tmp.writeBytes(jpeg)
            ExifInterface(tmp.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            tmp.readBytes()
        } catch (e: Exception) {
            jpeg
        } finally {
            tmp.delete()
        }
    }

    /** Keeps the chunks needed to draw the image right; drops text, EXIF and timestamps. */
    private fun stripPng(b: ByteArray): ByteArray {
        val keep = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "pHYs", "bKGD", "acTL", "fcTL", "fdAT")
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        val input = DataInputStream(b.inputStream(8, b.size - 8))
        var pos = 8
        while (pos + 12 <= b.size) {
            val len = input.readInt()
            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            val total = 12 + len
            if (len < 0 || pos + total > b.size) break
            if (type in keep) out.write(b, pos, total)
            input.skipBytes(total - 4)
            pos += total
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    private fun reencode(b: ByteArray): ByteArray {
        val orientation = when (runCatching { ExifInterface(b.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        var bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: throw java.io.IOException("Format d'image non pris en charge.")
        if (orientation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(orientation.toFloat()) }, true)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    fun cleanName(name: String, mime: String): String {
        val base = name.substringBeforeLast('.')
        return "${base}_propre." + if (mime == "image/png") "png" else "jpg"
    }

    // ---- Hashes ----

    suspend fun hashes(context: Context, file: Picked): Report = withContext(Dispatchers.IO) {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md5.update(buf, 0, n); sha1.update(buf, 0, n); sha256.update(buf, 0, n)
                total += n
            }
        } ?: return@withContext Report.failed("HASH", file.name, "Fichier illisible.")
        fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }
        val s256 = hex(sha256)
        Report(
            "HASH", file.name,
            listOf(
                Row("Taille", "%,d octets".format(Locale.FRANCE, total)),
                Row("Type", file.mime),
                Row("MD5", hex(md5)),
                Row("SHA-1", hex(sha1)),
                Row("SHA-256", s256),
            ),
            listOf(
                Link("VirusTotal", "https://www.virustotal.com/gui/file/$s256"),
                Link("MalwareBazaar", "https://bazaar.abuse.ch/browse.php?search=sha256%3A$s256"),
            ),
            remark = "Seule l'empreinte part dans le lien : le fichier n'est envoyé nulle part.",
        )
    }

    // ---- Reverse image search ----

    /** Search engines that take an image's public address. */
    fun reverseLinks(imageUrl: String): List<Link> {
        val u = Net.enc(imageUrl.trim())
        return listOf(
            Link("Google Lens", "https://lens.google.com/uploadbyurl?url=$u"),
            Link("Bing", "https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:$u"),
            Link("Yandex", "https://yandex.com/images/search?rpt=imageview&url=$u"),
            Link("TinEye", "https://tineye.com/search?url=$u"),
        )
    }
}
