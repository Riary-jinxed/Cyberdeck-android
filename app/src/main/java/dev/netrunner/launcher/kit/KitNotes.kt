package dev.netrunner.launcher.kit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Kit sheet → Notes Netrunner: inserts a note through its provider (signature permission
 * WRITE_NOTES) with a kit:CODE link back to the sheet, then opens it in Notes.
 */
object KitNotes {
    private const val PACKAGE = "dev.netrunner.notes"
    private val NOTES: Uri = Uri.parse("content://dev.netrunner.notes.provider/notes")

    fun create(context: Context, e: Equipment) {
        val title = e.name.ifBlank { e.code }
        val content = buildString {
            appendLine("kit:${e.code}")
            if (e.serial.isNotBlank()) appendLine("S/N : ${e.serial}")
            if (e.location.isNotBlank()) appendLine("Emplacement : ${e.location}")
            appendLine("État : ${e.state}")
            KitStore.cablesFor(e.id).forEach { c ->
                appendLine("Câble : ${c.outlet} → ${c.switchName} ${c.port}".trimEnd() + (c.vlan.takeIf { it.isNotBlank() }?.let { " · VLAN $it" } ?: ""))
            }
            if (e.notes.isNotBlank()) {
                appendLine()
                appendLine(e.notes)
            }
        }.trimEnd()
        val uri = runCatching {
            context.contentResolver.insert(NOTES, ContentValues().apply {
                put("title", title)
                put("content", content)
            })
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "Installe Notes depuis Applis du deck (v0.2 ou plus)", Toast.LENGTH_LONG).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW, uri).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(view) }.isSuccess ||
            context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let {
                runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            } == true
        if (!opened) Toast.makeText(context, "Note créée dans Notes", Toast.LENGTH_SHORT).show()
    }
}
