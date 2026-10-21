package dev.netrunner.files.system

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/** Storage access, the quick roots, and handing files to other apps. */
object Storage {

    data class Root(val label: String, val dir: File)

    val home: File get() = Environment.getExternalStorageDirectory()

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** The "All files access" page for this app, or the general list when HyperOS hides it. */
    fun requestAccess(context: Context) {
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(own)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun roots(context: Context): List<Root> {
        val list = mutableListOf(Root("INTERNE", home))
        listOf(
            "DOWNLOAD" to Environment.DIRECTORY_DOWNLOADS,
            "DCIM" to Environment.DIRECTORY_DCIM,
            "IMAGES" to Environment.DIRECTORY_PICTURES,
            "DOCS" to Environment.DIRECTORY_DOCUMENTS,
            "MUSIQUE" to Environment.DIRECTORY_MUSIC,
            "VIDÉOS" to Environment.DIRECTORY_MOVIES,
        ).forEach { (label, name) ->
            val dir = File(home, name)
            if (dir.isDirectory) list += Root(label, dir)
        }
        // SD card or USB key, when one is mounted.
        if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(StorageManager::class.java).storageVolumes
                .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .forEach { v -> v.directory?.let { list += Root(if (it.path.contains("usb", true)) "USB" else "SD", it) } }
        }
        return list
    }

    fun mime(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun open(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), mime(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Aucune appli pour ouvrir ce fichier", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, files: List<File>) {
        val shareable = files.filter { it.isFile }
        if (shareable.isEmpty()) {
            Toast.makeText(context, "Les dossiers ne se partagent pas, seulement les fichiers", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = shareable.map { uri(context, it) }
        val types = shareable.map { mime(it) }.distinct()
        val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Partager"))
    }

    /** "~/Download/x" for paths in the main storage, the full path elsewhere. */
    fun pretty(file: File): String {
        val h = home.absolutePath
        val p = file.absolutePath
        return when {
            p == h -> "~"
            p.startsWith("$h/") -> "~/" + p.removePrefix("$h/")
            else -> p
        }
    }
}
