package dev.netrunner.os.slides

import dev.netrunner.bridge.Bridge
import java.io.File
import javax.swing.SwingUtilities

/**
 * What As//ka sees of Netrunner Présentation (the slide on screen) and what she can do: read
 * the slides, write new ones in Markdown, rework one, reorder, change the theme, present, save.
 */
class DeckBridge(private val state: DeckState) : Bridge.App {
    override val id = "presentation"
    override val title = "Netrunner Présentation"

    private fun <T> ui(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun context(): String = ui {
        val d = state.deck
        "Netrunner Présentation édite « ${state.file?.name ?: "Sans titre"} »" + (if (state.dirty) " (non enregistré)" else "") +
            " : ${d.slides.size} diapo(s), thème ${d.theme.label}, diapo ${state.current + 1} affichée (« ${state.slide.title.ifEmpty { "sans titre" }} », ${state.slide.layout.label})" +
            (if (state.showing != null) ", diaporama en cours" else "") +
            ". Pour lire les diapos, utilise presentation_read ; pour en écrire, du Markdown : # titre, - point (deux espaces par niveau), ## sous-titre pour une diapo de titre, --- entre deux diapos, Notes : pour l'orateur."
    }

    private val slideParam = Bridge.Param("slide", "numéro de la diapo", "integer")

    override fun tools() = listOf(
        Bridge.Tool("presentation_read", "Lit les diapos (toutes, ou de from à to) en Markdown, avec leurs notes.", listOf(Bridge.Param("from", "première diapo", "integer", false), Bridge.Param("to", "dernière diapo", "integer", false))),
        Bridge.Tool("presentation_write", "Ajoute des diapos écrites en Markdown (--- entre deux diapos), après la diapo affichée, à la fin, ou à la place de toute la présentation.", listOf(Bridge.Param("markdown", "les diapos"), Bridge.Param("where", "after, end ou replace_all", required = false))),
        Bridge.Tool("presentation_edit", "Change une diapo : son titre, ses lignes (une par ligne, deux espaces par niveau), ses notes ou sa disposition.", listOf(slideParam, Bridge.Param("title", "nouveau titre", required = false), Bridge.Param("body", "nouvelles lignes", required = false), Bridge.Param("notes", "nouvelles notes", required = false), Bridge.Param("layout", "title, content ou section", required = false))),
        Bridge.Tool("presentation_goto", "Affiche une diapo (dans le diaporama aussi s'il est lancé).", listOf(slideParam)),
        Bridge.Tool("presentation_delete", "Supprime une diapo.", listOf(slideParam)),
        Bridge.Tool("presentation_move", "Déplace une diapo à une autre place.", listOf(slideParam, Bridge.Param("to", "nouvelle place", "integer"))),
        Bridge.Tool("presentation_theme", "Change le thème : Netrunner, Clair, Ardoise ou Papier.", listOf(Bridge.Param("theme", "nom du thème"))),
        Bridge.Tool("presentation_show", "Lance le diaporama en plein écran (depuis le début ou une diapo), ou l'arrête.", listOf(Bridge.Param("from", "diapo de départ", "integer", false), Bridge.Param("stop", "true pour arrêter", "boolean", false))),
        Bridge.Tool("presentation_undo", "Annule la dernière modification."),
        Bridge.Tool("presentation_save", "Enregistre la présentation (là où elle est déjà, ou au chemin donné : .pptx ou .odp).", listOf(Bridge.Param("path", "chemin, seulement pour un nouveau fichier", required = false))),
        Bridge.Tool("presentation_open", "Ouvre une présentation (.pptx, .odp) dans Netrunner Présentation.", listOf(Bridge.Param("path", "chemin du fichier, ~ pour le dossier personnel"))),
    )

    private fun path(s: String?) = File(s.orEmpty().trim().replaceFirst(Regex("^~"), System.getProperty("user.home")))

    override fun run(tool: String, args: Map<String, String>): String = ui {
        val d = state.deck
        fun slide(name: String = "slide"): Int? = args[name]?.trim()?.toIntOrNull()?.minus(1)?.takeIf { it in d.slides.indices }
        val which = "La présentation a ${d.slides.size} diapo(s)."
        when (tool) {
            "presentation_read" -> {
                val from = (args["from"]?.toIntOrNull() ?: 1).coerceIn(1, d.slides.size)
                val to = (args["to"]?.toIntOrNull() ?: d.slides.size).coerceIn(from, d.slides.size)
                val out = StringBuilder()
                for (i in from - 1 until to) {
                    out.append("Diapo ${i + 1} (${d.slides[i].layout.label}) :\n").append(SlideMarkdown.write(listOf(d.slides[i]))).append('\n')
                    if (out.length > 14_000) { out.append("[… coupé : lis la suite avec from=${i + 2}]"); break }
                }
                out.toString()
            }
            "presentation_write" -> {
                val slides = SlideMarkdown.parse(args["markdown"].orEmpty())
                if (slides.isEmpty()) return@ui "Rien à écrire."
                when (args["where"]?.lowercase()) {
                    "replace_all" -> state.replaceAll(slides)
                    "end" -> state.add(d.slides.size - 1, slides)
                    else -> state.add(state.current, slides)
                }
                "${slides.size} diapo(s) écrite(s), la présentation en a ${state.deck.slides.size}. L'utilisateur peut annuler avec Ctrl+Z."
            }
            "presentation_edit" -> {
                val i = slide() ?: return@ui which
                val layout = args["layout"]?.takeIf { it.isNotBlank() }?.let { l -> Layout.entries.firstOrNull { it.name.equals(l, true) || it.label.equals(l, true) } ?: return@ui "Disposition inconnue : $l" }
                state.edit(i) { s ->
                    s.copy(
                        title = args["title"] ?: s.title,
                        body = args["body"]?.let { Slide.lines(it) } ?: s.body,
                        notes = args["notes"] ?: s.notes,
                        layout = layout ?: s.layout,
                    )
                }
                state.goTo(i)
                "Diapo ${i + 1} changée."
            }
            "presentation_goto" -> {
                val i = slide() ?: return@ui which
                state.goTo(i)
                if (state.showing != null) state.showing = i
                "Diapo ${i + 1} affichée."
            }
            "presentation_delete" -> { val i = slide() ?: return@ui which; state.delete(i); "Diapo ${i + 1} supprimée, il en reste ${state.deck.slides.size}." }
            "presentation_move" -> {
                val i = slide() ?: return@ui which
                val to = slide("to") ?: return@ui which
                state.goTo(i)
                state.move(to - i)
                "Diapo déplacée en ${to + 1}."
            }
            "presentation_theme" -> {
                val name = args["theme"].orEmpty().trim()
                val t = Theme.entries.firstOrNull { it.name.equals(name, true) || it.label.equals(name, true) } ?: return@ui "Thèmes : " + Theme.entries.joinToString(", ") { it.label }
                state.theme(t)
                "Thème ${t.label}."
            }
            "presentation_show" -> {
                if (args["stop"]?.lowercase() == "true") { state.showing = null; return@ui "Diaporama arrêté." }
                val from = slide("from") ?: 0
                state.show(from)
                "Diaporama lancé depuis la diapo ${from + 1} (Échap pour sortir)."
            }
            "presentation_undo" -> { state.undo(); "Annulé." }
            "presentation_save" -> {
                val p = args["path"]?.takeIf { it.isNotBlank() }?.let(::path)
                val target = p ?: state.file ?: return@ui "La présentation n'a pas encore de fichier : donne un chemin (.pptx, .odp)."
                val f = DeckFiles.target(target)
                if (state.saveTo(f)) "Enregistré : ${f.path}" else state.message
            }
            "presentation_open" -> {
                val f = path(args["path"])
                if (!f.isFile) return@ui "Pas de fichier « ${f.path} »."
                if (state.dirty) return@ui "La présentation ouverte a des modifications non enregistrées : enregistre-la d'abord (presentation_save) ou demande à l'utilisateur."
                state.open(f)
                state.message.ifEmpty { "« ${f.name} » ouvert : ${state.deck.slides.size} diapo(s)." }
            }
            else -> "Outil inconnu : $tool"
        }
    }
}
