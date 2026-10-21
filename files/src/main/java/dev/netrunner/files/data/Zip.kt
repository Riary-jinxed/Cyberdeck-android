package dev.netrunner.files.data

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Zip archives with java.util.zip, kept free of Android so they can be tested anywhere. */
object Zip {
    data class Entry(val name: String, val size: Long, val isDirectory: Boolean)

    fun isZip(file: File) = file.isFile && file.extension.equals("zip", ignoreCase = true)

    /** Name for an archive of [files]: the item's own name for one item, "archive.zip" for several. */
    fun archiveName(files: List<File>): String =
        (files.singleOrNull()?.let { if (it.isDirectory) it.name else it.nameWithoutExtension } ?: "archive") + ".zip"

    /** Packs [files] (folders with their content) into a new archive in [targetDir]. Returns the archive. */
    fun compress(files: List<File>, targetDir: File): File {
        val target = FileOps.freeName(targetDir, archiveName(files))
        try {
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                for (root in files) {
                    root.walkTopDown()
                        .onEnter { it.canonicalPath == it.absolutePath || it == root }
                        // The archive may sit inside a folder being packed: never pack it into itself.
                        .filter { it.absolutePath != target.absolutePath }
                        .forEach { f ->
                            val rel = f.relativeTo(root.parentFile ?: root).invariantSeparatorsPath
                            if (f.isDirectory) {
                                out.putNextEntry(ZipEntry("$rel/").apply { time = f.lastModified() })
                                out.closeEntry()
                            } else {
                                out.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                                f.inputStream().use { it.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw IOException("Compression interrompue : ${e.message}", e)
        }
        return target
    }

    fun entries(zip: File): List<Entry> = ZipFile(zip).use { z ->
        z.entries().asSequence().map { Entry(it.name, it.size, it.isDirectory) }.toList()
    }

    /**
     * Unpacks [zip] into a new folder named after it in [targetDir] and returns that folder.
     * Entries that would land outside it ("../x", absolute paths) are refused.
     */
    fun extract(zip: File, targetDir: File): File {
        val dest = FileOps.freeName(targetDir, zip.nameWithoutExtension)
        if (!dest.mkdirs()) throw IOException("Création du dossier refusée")
        try {
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val e = input.nextEntry ?: break
                    val out = File(dest, e.name)
                    if (!FileOps.isInside(out, dest)) throw IOException("Archive dangereuse : « ${e.name} » sort du dossier")
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { input.copyTo(it) }
                        if (e.time > 0) out.setLastModified(e.time)
                    }
                }
            }
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw if (e is IOException && e.message?.startsWith("Archive") == true) e else IOException("Extraction impossible : ${e.message}", e)
        }
        return dest
    }
}
