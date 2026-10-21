package dev.netrunner.gallery.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.gallery.data.GalleryPrefs
import dev.netrunner.gallery.data.MediaRepo

/** Media list, the open viewer and the display settings, shared by the tabs. */
class GalleryState(context: Context) {
    private val app = context.applicationContext

    var media by mutableStateOf<List<MediaRepo.Media>?>(null)
        private set
    var holo by mutableStateOf(GalleryPrefs.holo(app))
        private set
    var columns by mutableIntStateOf(GalleryPrefs.columns(app))
        private set
    /** Album shown in PHOTOS, null for all. */
    var album by mutableStateOf<String?>(null)
    /** The viewer: the list it pages through and where it starts. */
    var viewer by mutableStateOf<Pair<List<MediaRepo.Media>, Int>?>(null)
    var version by mutableIntStateOf(0)
        private set
    var favorites by mutableStateOf(GalleryPrefs.favorites(app))
        private set
    /** PHOTOS shows only the favourites. */
    var favoritesOnly by mutableStateOf(false)
    /** Ids picked with a long press, for actions on several photos. */
    var selection by mutableStateOf<Set<Long>>(emptySet())

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { version++ }
    }

    fun start() {
        runCatching { app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer) }
        version++
    }

    fun refresh() { version++ }

    fun stop() = app.contentResolver.unregisterContentObserver(observer)

    suspend fun reload() {
        val list = runCatching { MediaRepo.all(app) }.getOrDefault(emptyList())
        media = list
        // Forget what no longer exists (deleted elsewhere).
        val ids = list.mapTo(HashSet()) { it.id }
        selection = selection.filterTo(HashSet()) { it in ids }
        if (list.isNotEmpty() && favorites.any { it !in ids }) {
            favorites = favorites.filterTo(HashSet()) { it in ids }
            GalleryPrefs.setFavorites(app, favorites)
        }
    }

    fun toggleHolo() {
        holo = !holo
        GalleryPrefs.setHolo(app, holo)
    }

    /** Stars every id in [ids], or unstars them all when they already are. */
    fun toggleFavorite(ids: Collection<Long>) {
        val valid = ids.filter { it >= 0 }
        if (valid.isEmpty()) return
        favorites = if (valid.all { it in favorites }) favorites - valid.toSet() else favorites + valid
        GalleryPrefs.setFavorites(app, favorites)
    }

    fun toggleSelected(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun cycleColumns() {
        columns = when (columns) { 3 -> 4; 4 -> 5; else -> 3 }
        GalleryPrefs.setColumns(app, columns)
    }
}
