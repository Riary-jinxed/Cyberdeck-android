package dev.netrunner.files.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.files.data.FileOps
import dev.netrunner.files.data.FilesPrefs
import dev.netrunner.files.system.Storage
import java.io.File

/** Browser state shared by the EXPLORER and RECHERCHE tabs. */
class Explorer(context: Context) {
    private val app = context.applicationContext

    var dir by mutableStateOf(
        FilesPrefs.lastDir(app)?.let { File(it) }?.takeIf { it.isDirectory } ?: Storage.home
    )
        private set
    /** Folders unfolded in place, as in `tree`. */
    var expanded by mutableStateOf(emptySet<String>())
    var selection by mutableStateOf(emptySet<File>())
    var clipboard by mutableStateOf(emptyList<File>())
    var cut by mutableStateOf(false)
    var sort by mutableStateOf(FilesPrefs.sort(app))
        private set
    var descending by mutableStateOf(FilesPrefs.descending(app))
        private set
    var showHidden by mutableStateOf(FilesPrefs.showHidden(app))
        private set
    /** Bumped after every change on disk, so listings reload. */
    var version by mutableIntStateOf(0)
        private set

    fun go(target: File) {
        dir = target
        expanded = emptySet()
        selection = emptySet()
        FilesPrefs.setLastDir(app, target.absolutePath)
    }

    /** False at the top of a storage volume, so back leaves the app. */
    fun up(): Boolean {
        val parent = dir.parentFile ?: return false
        if (Storage.roots(app).any { it.dir.absolutePath == dir.absolutePath } || !parent.canRead()) return false
        go(parent)
        return true
    }

    fun toggleExpanded(folder: File) {
        val p = folder.absolutePath
        expanded = if (p in expanded) expanded.filterNot { it == p || it.startsWith("$p/") }.toSet() else expanded + p
    }

    fun toggleSelected(file: File) {
        selection = if (file in selection) selection - file else selection + file
    }

    fun sortBy(s: FileOps.Sort) {
        // Same column again flips the order.
        descending = if (s == sort) !descending else s == FileOps.Sort.DATE || s == FileOps.Sort.SIZE
        sort = s
        FilesPrefs.setSort(app, sort, descending)
    }

    fun toggleHidden() {
        showHidden = !showHidden
        FilesPrefs.setShowHidden(app, showHidden)
    }

    fun refresh() {
        version++
    }

    /** One line of the tree view. */
    data class Row(val file: File, val depth: Int, val guides: String, val isLast: Boolean)

    /** The listing with unfolded folders inlined, drawn with tree guides (│ ├── └──). */
    fun rows(): List<Row>? {
        val top = FileOps.list(dir, showHidden, sort, descending) ?: return null
        val out = mutableListOf<Row>()
        fun walk(entries: List<File>, depth: Int, guides: String) {
            entries.forEachIndexed { i, f ->
                val last = i == entries.size - 1
                out += Row(f, depth, guides, last)
                if (f.isDirectory && f.absolutePath in expanded && out.size < MAX_ROWS) {
                    val children = FileOps.list(f, showHidden, sort, descending).orEmpty()
                    walk(children, depth + 1, guides + if (last) "    " else "│   ")
                }
            }
        }
        walk(top, 0, "")
        return out.take(MAX_ROWS)
    }

    companion object {
        private const val MAX_ROWS = 3000
    }
}
