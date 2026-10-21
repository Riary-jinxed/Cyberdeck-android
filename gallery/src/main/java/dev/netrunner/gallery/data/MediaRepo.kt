package dev.netrunner.gallery.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns as f
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Photos and videos from MediaStore, with a small thumbnail cache. */
object MediaRepo {

    data class Media(
        val id: Long,
        val uri: Uri,
        val name: String,
        val bucket: String,
        val taken: Long,
        val mime: String,
        val width: Int,
        val height: Int,
        val size: Long,
        val duration: Long,
    ) {
        val isVideo get() = mime.startsWith("video/")
    }

    data class Album(val name: String, val cover: Media, val count: Int)

    /** Permissions to ask for on this Android version. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Full access, or (Android 14) access to the photos the user picked. */
    fun granted(context: Context): Boolean {
        fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 -> has(Manifest.permission.READ_MEDIA_IMAGES) ||
                (Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** True when Android 14 only gave access to a selection. */
    fun partial(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    /** Everything, newest first. */
    suspend fun all(context: Context): List<Media> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val out = mutableListOf<Media>()
        context.contentResolver.query(
            collection,
            arrayOf(f._ID, f.DISPLAY_NAME, f.BUCKET_DISPLAY_NAME, f.DATE_TAKEN, f.DATE_MODIFIED, f.MIME_TYPE, f.WIDTH, f.HEIGHT, f.SIZE, f.DURATION, f.MEDIA_TYPE),
            "${f.MEDIA_TYPE} IN (${f.MEDIA_TYPE_IMAGE}, ${f.MEDIA_TYPE_VIDEO})",
            null,
            "${f.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val video = c.getInt(10) == f.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                // DATE_TAKEN is in ms and can be missing; DATE_MODIFIED is in seconds.
                val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
                out += Media(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    name = c.getString(1) ?: "",
                    bucket = c.getString(2) ?: "Sans album",
                    taken = taken,
                    mime = c.getString(5) ?: if (video) "video/*" else "image/*",
                    width = c.getInt(6),
                    height = c.getInt(7),
                    size = c.getLong(8),
                    duration = c.getLong(9),
                )
            }
        }
        out.sortedByDescending { it.taken }
    }

    fun albums(media: List<Media>): List<Album> =
        media.groupBy { it.bucket }.map { (name, items) -> Album(name, items.first(), items.size) }
            .sortedWith(compareByDescending<Album> { it.name.equals("Camera", true) }.thenByDescending { it.cover.taken })

    private val thumbs = object : LruCache<Long, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    suspend fun thumbnail(context: Context, media: Media, px: Int): Bitmap? {
        thumbs.get(media.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(media.uri, Size(px, px), null) }.getOrNull()
                ?.also { thumbs.put(media.id, it) }
        }
    }

    /** The full picture, scaled down to about [maxSide] pixels so big photos stay light in memory. */
    suspend fun picture(context: Context, uri: Uri, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                // Software bitmaps, so the holo export can read pixels.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    /** Saves [bitmap] as a JPEG in Pictures/Netrunner and returns its uri. */
    suspend fun saveCopy(context: Context, bitmap: Bitmap, name: String): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Netrunner")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        runCatching {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }
}
