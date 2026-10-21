package dev.netrunner.files.data

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File system operations on plain java.io files, kept free of Android so they can be tested anywhere. */
object FileOps {

    enum class Sort(val label: String) { NAME("NOM"), DATE("DATE"), SIZE("TAILLE"), TYPE("TYPE") }

    /** Folders first, then files, in the chosen order. Null when the folder cannot be read. */
    fun list(dir: File, showHidden: Boolean, sort: Sort, descending: Boolean = false): List<File>? {
        val entries = dir.listFiles() ?: return null
        val visible = if (showHidden) entries.toList() else entries.filterNot { it.name.startsWith('.') }
        val order: Comparator<File> = when (sort) {
            Sort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.DATE -> compareBy<File> { it.lastModified() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.SIZE -> compareBy<File> { if (it.isDirectory) 0L else it.length() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.TYPE -> compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val sorted = if (descending) order.reversed() else order
        return visible.sortedWith(compareBy<File> { !it.isDirectory }.then(sorted))
    }

    /**
     * Files and folders under [root] whose name contains every word of [query] (case and accents
     * ignored), breadth first so the closest matches come first. Lazy: stop pulling to cancel.
     */
    fun search(root: File, query: String, showHidden: Boolean): Sequence<File> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptySequence()
        return sequence {
            val queue = ArrayDeque<File>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                val children = dir.listFiles() ?: continue
                for (f in children.sortedBy { it.name.lowercase() }) {
                    if (!showHidden && f.name.startsWith('.')) continue
                    val name = fold(f.name)
                    if (words.all { name.contains(it) }) yield(f)
                    // Symbolic links are not followed, so a loop cannot trap the search.
                    if (f.isDirectory && f.canonicalPath == f.absolutePath) queue.add(f)
                }
            }
        }
    }

    /** Lower case without accents: "Éte" matches "ete". */
    fun fold(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** A free name in [dir]: "photo.jpg", then "photo (2).jpg", "photo (3).jpg"… */
    fun freeName(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (candidate.exists()) candidate = File(dir, "$base ($n)$ext").also { n++ }
        return candidate
    }

    /** Copies [source] (file or folder) into [targetDir] under a free name. Returns the copy. */
    fun copyInto(source: File, targetDir: File): File {
        if (source.isDirectory && isInside(targetDir, source)) throw IOException("Impossible de copier un dossier dans lui-même")
        val target = freeName(targetDir, source.name)
        if (!source.copyRecursively(target, overwrite = false)) throw IOException("Copie interrompue")
        return target
    }

    /** Moves [source] into [targetDir]: a rename when possible, else copy then delete. */
    fun moveInto(source: File, targetDir: File): File {
        if (source.parentFile?.canonicalPath == targetDir.canonicalPath) return source
        if (source.isDirectory && isInside(targetDir, source)) throw IOException("Impossible de déplacer un dossier dans lui-même")
        val target = freeName(targetDir, source.name)
        if (source.renameTo(target)) return target
        val copy = copyInto(source, targetDir)
        if (!source.deleteRecursively()) throw IOException("Copié, mais l'original n'a pas pu être supprimé")
        return copy
    }

    fun rename(file: File, newName: String): File {
        val clean = newName.trim()
        if (clean.isEmpty() || clean.contains('/') || clean == "." || clean == "..") throw IOException("Nom invalide")
        val target = File(file.parentFile, clean)
        if (target.exists()) throw IOException("« $clean » existe déjà")
        if (!file.renameTo(target)) throw IOException("Renommage refusé")
        return target
    }

    fun mkdir(parent: File, name: String): File {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean == "." || clean == "..") throw IOException("Nom invalide")
        val dir = File(parent, clean)
        if (dir.exists()) throw IOException("« $clean » existe déjà")
        if (!dir.mkdir()) throw IOException("Création refusée")
        return dir
    }

    fun delete(file: File) {
        if (!file.deleteRecursively()) throw IOException("Suppression incomplète de « ${file.name} »")
    }

    /** True when [child] is [parent] or somewhere below it. */
    fun isInside(child: File, parent: File): Boolean {
        val c = child.canonicalPath
        val p = parent.canonicalPath
        return c == p || c.startsWith(p.trimEnd('/') + "/")
    }

    /** Total size of a folder, counted lazily (big trees can take a moment). */
    fun treeSize(file: File): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        file.walkTopDown().onEnter { it.canonicalPath == it.absolutePath || it == file }.forEach {
            if (it.isFile) { bytes += it.length(); count++ }
        }
        return bytes to count
    }

    /** 0 o, 512 o, 1,4 Ko, 23 Mo, 1,2 Go */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val units = listOf("Ko", "Mo", "Go", "To")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        val text = if (v < 10) "%.1f".format(Locale.ROOT, v) else "%.0f".format(Locale.ROOT, v)
        return "${text.replace('.', ',').removeSuffix(",0")} ${units[i]}"
    }

    /** "drwx" style flags, as `ls -l` would print them for this app. */
    fun flags(file: File): String = buildString {
        append(if (file.isDirectory) 'd' else '-')
        append(if (file.canRead()) 'r' else '-')
        append(if (file.canWrite()) 'w' else '-')
        append(if (file.isDirectory || file.canExecute()) 'x' else '-')
    }

    private val dateFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.FRANCE)

    fun date(file: File): String = dateFormat.format(Date(file.lastModified()))

    /** Rough family from the extension, for the colour and the icon glyph. */
    enum class Kind(val glyph: String) { DIR("▸"), IMAGE("◩"), VIDEO("▶"), AUDIO("♪"), DOC("≡"), ARCHIVE("▤"), APK("◆"), CODE("‹›"), OTHER("·") }

    fun kind(file: File): Kind {
        if (file.isDirectory) return Kind.DIR
        return when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "dng" -> Kind.IMAGE
            "mp4", "mkv", "webm", "mov", "avi", "3gp" -> Kind.VIDEO
            "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "amr" -> Kind.AUDIO
            "pdf", "txt", "md", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "csv", "rtf", "epub" -> Kind.DOC
            "zip", "rar", "7z", "tar", "gz", "xz", "bz2" -> Kind.ARCHIVE
            "apk", "apks", "xapk" -> Kind.APK
            "kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh", "yml", "yaml", "c", "cpp", "h", "rs", "go", "log", "conf", "ini" -> Kind.CODE
            else -> Kind.OTHER
        }
    }
}
