package dev.netrunner.files.data

import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * A recycle bin in a hidden folder: each deleted item moves to [root]/<id>/ next to an "info" file
 * holding where it came from and when. Plain java.io, tested on the JVM.
 */
class Trash(val root: File) {

    data class Entry(val id: String, val name: String, val origin: String, val deletedAt: Long, val item: File) {
        val isDir get() = item.isDirectory
    }

    /** Moves [file] to the bin. A rename on the same volume, else copy then delete. */
    fun put(file: File, now: Long = System.currentTimeMillis()): Entry {
        if (FileOps.isInside(file, root) || FileOps.isInside(root, file)) throw IOException("« ${file.name} » ne peut pas aller à la corbeille")
        root.mkdirs()
        var id = now.toString(36)
        var n = 1
        while (File(root, id).exists()) id = now.toString(36) + "-" + n++
        val slot = File(root, id).apply { mkdirs() }
        Properties().apply {
            setProperty("name", file.name)
            setProperty("origin", file.absolutePath)
            setProperty("deleted", now.toString())
        }.let { p -> File(slot, INFO).outputStream().use { p.store(it, null) } }
        val target = File(slot, file.name)
        if (!file.renameTo(target)) {
            if (!file.copyRecursively(target, overwrite = false)) { slot.deleteRecursively(); throw IOException("Copie vers la corbeille interrompue") }
            if (!file.deleteRecursively()) throw IOException("Copié dans la corbeille, mais l'original n'a pas pu être supprimé")
        }
        return Entry(id, file.name, file.absolutePath, now, target)
    }

    /** Newest first. Slots without a readable item are skipped. */
    fun list(): List<Entry> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { slot ->
        val info = File(slot, INFO)
        val p = Properties().apply { runCatching { info.inputStream().use { load(it) } } }
        val name = p.getProperty("name") ?: return@mapNotNull null
        val item = File(slot, name).takeIf { it.exists() } ?: return@mapNotNull null
        Entry(slot.name, name, p.getProperty("origin").orEmpty(), p.getProperty("deleted")?.toLongOrNull() ?: slot.lastModified(), item)
    }.sortedByDescending { it.deletedAt }

    /** Puts the item back where it was (under a free name if that place is taken). */
    fun restore(entry: Entry): File {
        val origin = File(entry.origin)
        val parent = origin.parentFile ?: throw IOException("Emplacement d'origine inconnu")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Impossible de recréer ${parent.path}")
        val target = FileOps.freeName(parent, entry.name)
        if (!entry.item.renameTo(target)) {
            if (!entry.item.copyRecursively(target, overwrite = false)) throw IOException("Restauration interrompue")
        }
        File(root, entry.id).deleteRecursively()
        return target
    }

    fun purge(entry: Entry) {
        if (!File(root, entry.id).deleteRecursively()) throw IOException("Suppression incomplète de « ${entry.name} »")
    }

    fun empty() = list().forEach { purge(it) }

    /** Drops what has been in the bin for more than [days] days. Returns how many went. */
    fun purgeOlderThan(days: Int, now: Long = System.currentTimeMillis()): Int {
        val limit = now - days * 86_400_000L
        return list().filter { it.deletedAt < limit }.onEach { runCatching { purge(it) } }.size
    }

    /** Bytes and items in the bin. */
    fun size(): Pair<Long, Int> = list().fold(0L to 0) { (b, n), e -> (b + FileOps.treeSize(e.item).first) to n + 1 }

    companion object {
        const val FOLDER = ".netrunner-corbeille"
        private const val INFO = ".info"
        const val KEEP_DAYS = 30
    }
}
