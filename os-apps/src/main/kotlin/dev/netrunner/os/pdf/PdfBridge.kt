package dev.netrunner.os.pdf

import dev.netrunner.bridge.Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.SwingUtilities

/** What As//ka sees of Netrunner PDF, and what she can do in it: she can read it to summarize or answer. */
class PdfBridge(private val state: PdfState) : Bridge.App {
    override val id = "pdf"
    override val title = "Netrunner PDF"

    private fun <T> ui(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun context(): String = ui {
        val d = state.doc ?: return@ui "Netrunner PDF est ouvert, sans document."
        "Netrunner PDF affiche « ${d.title} » (${d.file.path}, ${d.pages} pages" + (if (d.author.isNotEmpty()) ", de ${d.author}" else "") +
            "), page ${state.page} à l'écran. Pour répondre sur son contenu, lis-le avec pdf_read."
    }

    override fun tools() = listOf(
        Bridge.Tool("pdf_open", "Ouvre un PDF dans Netrunner PDF.", listOf(Bridge.Param("path", "chemin du fichier, ~ pour le dossier personnel"))),
        Bridge.Tool("pdf_read", "Donne le texte de pages du PDF ouvert (pour le résumer, répondre ou citer).", listOf(Bridge.Param("from", "première page", "integer"), Bridge.Param("to", "dernière page", "integer"))),
        Bridge.Tool("pdf_search", "Cherche des mots dans le PDF ouvert : les pages et un extrait.", listOf(Bridge.Param("query", "les mots à chercher"))),
        Bridge.Tool("pdf_goto", "Affiche une page du PDF ouvert.", listOf(Bridge.Param("page", "numéro de page", "integer"))),
        Bridge.Tool("pdf_zoom", "Règle le zoom de Netrunner PDF.", listOf(Bridge.Param("percent", "50 à 400", "integer"))),
    )

    override fun run(tool: String, args: Map<String, String>): String {
        // Reading and searching run on the PDF's own worker, not on the screen's thread.
        val d = ui { state.doc }
        fun int(k: String) = args[k]?.trim()?.toIntOrNull()
        return when (tool) {
            "pdf_open" -> {
                val f = File(args["path"].orEmpty().trim().replaceFirst(Regex("^~"), System.getProperty("user.home")))
                if (!f.isFile) return "Pas de fichier « ${f.path} »."
                runBlocking(Dispatchers.Main) { state.open(f) }
                ui { state.doc?.takeIf { it.file == f }?.let { "« ${it.title} » ouvert : ${it.pages} pages." } ?: state.error }
            }
            "pdf_read" -> {
                d ?: return "Aucun PDF ouvert."
                val from = int("from") ?: ui { state.page }
                runBlocking { withContext(Pdf.worker) { d.read(from, int("to") ?: from) } }
            }
            "pdf_search" -> {
                d ?: return "Aucun PDF ouvert."
                val q = args["query"].orEmpty()
                val found = runBlocking { withContext(Pdf.worker) { d.search(q) } }
                ui {
                    state.query = q
                    state.results = found
                    found.firstOrNull()?.let { state.goTo(it.first) }
                }
                if (found.isEmpty()) "« $q » n'est pas dans le document." else found.take(20).joinToString("\n") { "page ${it.first} : …${it.second}…" }
            }
            "pdf_goto" -> {
                d ?: return "Aucun PDF ouvert."
                val p = int("page") ?: return "Quelle page ?"
                ui { state.goTo(p) }
                "Page ${p.coerceIn(1, d.pages)} affichée."
            }
            "pdf_zoom" -> {
                val p = int("percent") ?: return "Quel zoom ?"
                ui { state.zoom = (p / 100f).coerceIn(0.5f, 4f) }
                "Zoom ${p.coerceIn(50, 400)} %."
            }
            else -> "Outil inconnu : $tool"
        }
    }
}
