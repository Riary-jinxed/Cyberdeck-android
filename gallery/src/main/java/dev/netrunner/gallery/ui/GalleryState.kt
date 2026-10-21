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
        media = runCatching { MediaRepo.all(app) }.getOrDefault(emptyList())
    }

    fun toggleHolo() {
        holo = !holo
        GalleryPrefs.setHolo(app, holo)
    }

    fun cycleColumns() {
        columns = when (columns) { 3 -> 4; 4 -> 5; else -> 3 }
        GalleryPrefs.setColumns(app, columns)
    }
}
