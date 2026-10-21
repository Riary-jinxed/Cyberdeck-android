package dev.netrunner.launcher

import android.app.Application
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppRepository(app)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _favorites = MutableStateFlow(repo.favorites())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    // Keeps the app list in sync when something is installed, updated or removed.
    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    init {
        repo.registerCallback(packageCallback)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { repo.loadApps() }
        }
    }

    fun isFavorite(app: AppEntry): Boolean = app.key in _favorites.value

    fun toggleFavorite(app: AppEntry) {
        val current = _favorites.value
        val updated = if (app.key in current) current - app.key else current + app.key
        _favorites.value = updated
        repo.saveFavorites(updated)
    }

    fun launch(app: AppEntry) = repo.launch(app)

    fun openAppInfo(app: AppEntry) = repo.openAppInfo(app)

    override fun onCleared() {
        repo.unregisterCallback(packageCallback)
    }
}
