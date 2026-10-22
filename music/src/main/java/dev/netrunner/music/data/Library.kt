package dev.netrunner.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** The phone's music, as indexed by Android (MediaStore). */
object Library {
    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val duration: Long,
        val number: Int,
    ) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

        fun toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                    .setTrackNumber(number.takeIf { it > 0 }).build(),
            )
            .build()
    }

    data class Group(val key: String, val title: String, val subtitle: String, val tracks: List<Track>)

    const val UNKNOWN = "<unknown>"

    fun load(context: Context): List<Track> {
        val columns = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
        )
        val out = mutableListOf<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += Track(
                    id = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Sans titre",
                    artist = c.getString(2)?.takeIf { it.isNotBlank() && it != UNKNOWN } ?: "Artiste inconnu",
                    album = c.getString(3)?.takeIf { it.isNotBlank() && it != UNKNOWN } ?: "Album inconnu",
                    albumId = c.getLong(4),
                    duration = c.getLong(5),
                    // MediaStore stores disc × 1000 + track.
                    number = c.getInt(6) % 1000,
                )
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }

    fun search(tracks: List<Track>, query: String): List<Track> {
        val q = fold(query.trim())
        if (q.isEmpty()) return tracks
        return tracks.filter { t -> listOf(t.title, t.artist, t.album).any { fold(it).contains(q) } }
    }

    fun albums(tracks: List<Track>): List<Group> = tracks.groupBy { it.albumId }.map { (id, list) ->
        val sorted = list.sortedWith(compareBy({ it.number }, { it.title.lowercase() }))
        val artists = list.map { it.artist }.distinct()
        Group("album:$id", list.first().album, if (artists.size == 1) artists[0] else "Artistes variés", sorted)
    }.sortedBy { it.title.lowercase() }

    fun artists(tracks: List<Track>): List<Group> = tracks.groupBy { it.artist }.map { (name, list) ->
        val albums = list.map { it.albumId }.distinct().size
        val sorted = list.sortedWith(compareBy({ it.album.lowercase() }, { it.number }, { it.title.lowercase() }))
        Group("artist:$name", name, "$albums album${if (albums > 1) "s" else ""} · ${list.size} titre${if (list.size > 1) "s" else ""}", sorted)
    }.sortedBy { it.title.lowercase() }

    /** Lower case without accents, so "beyonce" finds "Beyoncé". */
    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
