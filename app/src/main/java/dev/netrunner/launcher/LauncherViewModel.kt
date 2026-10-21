package dev.netrunner.launcher

import android.app.Application
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.netrunner.launcher.system.UiSound
import dev.netrunner.launcher.terminal.Terminal
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

    private val _usage = MutableStateFlow(repo.usage())
    val usage: StateFlow<Map<String, Int>> = _usage.asStateFlow()

    private val _favorites = MutableStateFlow(repo.favorites())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    /** The app being "jacked into": the launch animation plays, then the app opens. */
    private val _launching = MutableStateFlow<AppEntry?>(null)
    val launching: StateFlow<AppEntry?> = _launching.asStateFlow()

    /** Full-screen panel shown over the home HUD. */
    enum class Panel { None, Terminal, Wifi, Feed, Quick, Settings, Messages }

    /** The boot log plays once per launcher process, not on every return home. */
    var booted = false

    private val _panel = MutableStateFlow(Panel.None)
    val panel: StateFlow<Panel> = _panel.asStateFlow()

    val terminal = Terminal(
        context = app,
        scope = viewModelScope,
        apps = { _apps.value },
        launch = ::requestLaunch,
        openPanel = ::openPanel,
    )

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

    private val _folders = MutableStateFlow(repo.folders())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    fun isFavorite(app: AppEntry): Boolean =
        app.key in _favorites.value || _folders.value.any { app.key in it.keys }

    fun toggleFavorite(app: AppEntry) {
        _folders.value.firstOrNull { app.key in it.keys }?.let {
            removeFromFolder(it.id, app.key, backToGrid = false)
            return
        }
        val current = _favorites.value
        setFavorites(if (app.key in current) current - app.key else current + app.key)
    }

    private fun setFavorites(keys: List<String>) {
        _favorites.value = keys
        repo.saveFavorites(keys)
    }

    private fun setFolders(list: List<Folder>) {
        _folders.value = list
        repo.saveFolders(list)
    }

    /** Moves a pinned app into a folder; a null id creates a new folder in the app's place. */
    fun moveToFolder(appKey: String, folderId: String?) {
        val folders = _folders.value
        val target = folders.firstOrNull { it.id == folderId }
        if (target == null) {
            val folder = Folder(System.currentTimeMillis().toString(36), "DOSSIER ${folders.size + 1}", listOf(appKey))
            setFolders(folders + folder)
            setFavorites(_favorites.value.map { if (it == appKey) folder.slot else it })
        } else {
            setFolders(folders.map { if (it.id == target.id && appKey !in it.keys) it.copy(keys = it.keys + appKey) else it })
            setFavorites(_favorites.value - appKey)
        }
    }

    /** Takes an app out of a folder (back to the grid or unpinned); an empty folder disappears. */
    fun removeFromFolder(folderId: String, appKey: String, backToGrid: Boolean = true) {
        val folder = _folders.value.firstOrNull { it.id == folderId } ?: return
        val keys = folder.keys - appKey
        var favorites = _favorites.value
        if (backToGrid) favorites = favorites + appKey
        if (keys.isEmpty()) {
            setFolders(_folders.value.filter { it.id != folderId })
            favorites = favorites - folder.slot
        } else {
            setFolders(_folders.value.map { if (it.id == folderId) it.copy(keys = keys) else it })
        }
        setFavorites(favorites)
    }

    fun renameFolder(folderId: String, name: String) {
        val clean = name.trim().take(20).ifEmpty { return }
        setFolders(_folders.value.map { if (it.id == folderId) it.copy(name = clean) else it })
    }

    /** Re-reads favorites and folders, after a profile switch or a config restore. */
    fun reloadConfig() {
        _favorites.value = repo.favorites()
        _folders.value = repo.folders()
    }

    fun requestLaunch(app: AppEntry) {
        UiSound.play(getApplication<Application>(), UiSound.Cue.JackIn)
        _launching.value = app
    }

    fun completeLaunch() {
        _launching.value?.let(repo::launch)
        _usage.value = repo.usage()
        _launching.value = null
    }

    fun openPanel(panel: Panel) {
        if (panel == _panel.value) return
        UiSound.play(getApplication<Application>(), if (panel == Panel.None) UiSound.Cue.Close else UiSound.Cue.Open)
        _panel.value = panel
    }

    fun openAppInfo(app: AppEntry) = repo.openAppInfo(app)

    override fun onCleared() {
        repo.unregisterCallback(packageCallback)
    }
}
