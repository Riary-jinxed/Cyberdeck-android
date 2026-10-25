package dev.netrunner.os.text

import dev.netrunner.bridge.Bridge
import dev.netrunner.os.text.Doc.Align
import dev.netrunner.os.text.Doc.Kind
import java.io.File
import javax.swing.SwingUtilities

/**
 * What As//ka sees of Netrunner Texte (the document, the selection) and what she can do: read
 * it, write into it in Markdown, rework the selection, replace, set paragraph kinds, save.
 */
class TextBridge(private val state: TextState) : Bridge.App {
    override val id = "texte"
    override val title = "Netrunner Texte"

    private fun <T> ui(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun context(): String = ui {
        val d = state.doc
        val sel = state.selectedText
        "Netrunner Texte édite « ${state.file?.name ?: "Sans titre"} » (${d.stats()}" + (if (state.dirty) ", non enregistré" else "") + "), " +
            "curseur au paragraphe ${d.paragraphAt(state.selection.min) + 1}" +
            (if (sel.isNotBlank()) ". Texte sélectionné : « ${sel.take(600)}${if (sel.length > 600) "…" else ""} »" else "") +
            ". Pour lire le document, utilise texte_read ; pour écrire, du Markdown."
    }

    override fun tools() = listOf(
        Bridge.Tool("texte_read", "Lit le document ouvert, paragraphe par paragraphe (numéro, type, texte en Markdown).", listOf(Bridge.Param("from", "premier paragraphe", "integer", false), Bridge.Param("to", "dernier paragraphe", "integer", false))),
        Bridge.Tool("texte_write", "Écrit dans le document, en Markdown (# titre, ## sous-titre, - liste, 1. liste, **gras**, *italique*), au curseur, à la place de la sélection, au début ou à la fin.", listOf(Bridge.Param("markdown", "le texte à écrire"), Bridge.Param("where", "cursor, selection, start ou end", required = false))),
        Bridge.Tool("texte_replace", "Remplace un mot ou une phrase dans le document (toutes les fois, ou la prochaine).", listOf(Bridge.Param("find", "le texte à trouver"), Bridge.Param("with", "le texte qui le remplace"), Bridge.Param("all", "true pour tout remplacer", "boolean", false))),
        Bridge.Tool("texte_paragraph", "Change le type ou l'alignement d'un paragraphe.", listOf(Bridge.Param("paragraph", "numéro du paragraphe", "integer"), Bridge.Param("kind", "normal, title, heading1, heading2, heading3, bullet, numbered ou quote", required = false), Bridge.Param("align", "start, center, end ou justify", required = false))),
        Bridge.Tool("texte_select", "Sélectionne un texte du document (pour le montrer ou le retravailler ensuite).", listOf(Bridge.Param("find", "le texte à sélectionner"))),
        Bridge.Tool("texte_undo", "Annule la dernière modification."),
        Bridge.Tool("texte_save", "Enregistre le document (là où il est déjà, ou au chemin donné : .docx, .odt, .txt ou .md).", listOf(Bridge.Param("path", "chemin, seulement pour un nouveau fichier", required = false))),
        Bridge.Tool("texte_open", "Ouvre un document (.docx, .odt, .txt, .md) dans Netrunner Texte.", listOf(Bridge.Param("path", "chemin du fichier, ~ pour le dossier personnel"))),
    )

    private fun path(s: String?) = File(s.orEmpty().trim().replaceFirst(Regex("^~"), System.getProperty("user.home")))

    override fun run(tool: String, args: Map<String, String>): String = ui {
        val d = state.doc
        when (tool) {
            "texte_read" -> {
                val from = (args["from"]?.toIntOrNull() ?: 1).coerceIn(1, d.paras.size)
                val to = (args["to"]?.toIntOrNull() ?: d.paras.size).coerceIn(from, d.paras.size)
                val out = StringBuilder()
                for (i in from - 1 until to) {
                    val r = d.range(i)
                    val one = Doc(d.text.substring(r.first, r.last + 1), listOf(d.paras[i]), d.spans.filter { it.end > r.first && it.start < r.last + 1 }
                        .map { Doc.Span(maxOf(0, it.start - r.first), minOf(r.last + 1, it.end) - r.first, it.style) })
                    out.append("${i + 1}. [${d.paras[i].kind.label}] ").append(Markdown.write(one)).append('\n')
                    if (out.length > 14_000) { out.append("[… coupé : lis la suite avec from=${i + 2}]"); break }
                }
                out.toString().ifBlank { "Le document est vide." }
            }
            "texte_write" -> {
                val md = args["markdown"].orEmpty()
                if (md.isBlank()) return@ui "Rien à écrire."
                val fragment = Markdown.parse(md)
                when (args["where"]?.lowercase()) {
                    "start" -> state.insert(fragment, 0, 0)
                    "end" -> {
                        val end = d.text.length
                        // After the last paragraph: a new one first.
                        state.insert(if (d.text.isNotEmpty()) Markdown.parse("\n" + md) else fragment, end, end)
                    }
                    "cursor" -> state.insert(fragment, state.selection.max, state.selection.max)
                    else -> state.insert(fragment)
                }
                "Écrit (${fragment.paras.size} paragraphe(s)). L'utilisateur peut annuler avec Ctrl+Z."
            }
            "texte_replace" -> {
                val n = state.replace(args["find"].orEmpty(), args["with"].orEmpty(), args["all"]?.lowercase() == "true")
                if (n == 0) "« ${args["find"]} » n'est pas dans le document." else "$n remplacement(s)."
            }
            "texte_paragraph" -> {
                val i = (args["paragraph"]?.toIntOrNull() ?: return@ui "Quel paragraphe ?") - 1
                if (i !in d.paras.indices) return@ui "Le document a ${d.paras.size} paragraphes."
                val kind = args["kind"]?.takeIf { it.isNotBlank() }?.let { k -> Kind.entries.firstOrNull { it.name.equals(k, true) || it.label.equals(k, true) } ?: return@ui "Type inconnu : $k" }
                val align = args["align"]?.takeIf { it.isNotBlank() }?.let { a -> Align.entries.firstOrNull { it.name.equals(a, true) || it.label.equals(a, true) } ?: return@ui "Alignement inconnu : $a" }
                state.select(d.range(i).first, d.range(i).first)
                kind?.let { k -> state.setKind(i, k) }
                align?.let { state.align(it) }
                "Paragraphe ${i + 1} : ${state.doc.paras[i].kind.label}, ${state.doc.paras[i].align.label}."
            }
            "texte_select" -> {
                val q = args["find"].orEmpty()
                val at = d.text.indexOf(q, ignoreCase = true)
                if (q.isEmpty() || at < 0) "« $q » n'est pas dans le document." else { state.select(at, at + q.length); "Sélectionné." }
            }
            "texte_undo" -> { state.undo(); "Annulé." }
            "texte_save" -> {
                val p = args["path"]?.takeIf { it.isNotBlank() }?.let(::path)
                val target = p ?: state.file ?: return@ui "Le document n'a pas encore de fichier : donne un chemin (.docx, .odt…)."
                val f = if (target.extension.lowercase() in DocFiles.Extensions) target else File(target.path + ".docx")
                if (state.saveTo(f)) "Enregistré : ${f.path}" else state.message
            }
            "texte_open" -> {
                val f = path(args["path"])
                if (!f.isFile) return@ui "Pas de fichier « ${f.path} »."
                if (state.dirty) return@ui "Le document ouvert a des modifications non enregistrées : enregistre-le d'abord (texte_save) ou demande à l'utilisateur."
                state.open(f)
                state.message.ifEmpty { "« ${f.name} » ouvert : ${state.doc.stats()}." }
            }
            else -> "Outil inconnu : $tool"
        }
    }
}
