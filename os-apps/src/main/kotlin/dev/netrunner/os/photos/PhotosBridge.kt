package dev.netrunner.os.photos

import dev.netrunner.bridge.Bridge
import java.io.File
import javax.swing.SwingUtilities

/** What As//ka sees of Netrunner Photos, and what she can do in it. */
class PhotosBridge(private val state: PhotosState) : Bridge.App {
    override val id = "photos"
    override val title = "Netrunner Photos"

    private fun <T> ui(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun context(): String = ui {
        val folder = state.folder
        val file = state.current
        if (file != null) {
            "Netrunner Photos affiche la photo « ${file.name} » (${(state.viewing ?: 0) + 1} sur ${folder.images.size}) du dossier ${folder.dir.path} : " +
                Images.describe(file, null) + (if (state.slideshow) ", en diaporama" else "") + "."
        } else {
            "Netrunner Photos montre la galerie du dossier ${folder.dir.path} : ${folder.images.size} photos" +
                (if (folder.subfolders.isEmpty()) "" else ", sous-dossiers " + folder.subfolders.take(12).joinToString(", ") { it.name }) + "."
        }
    }

    override fun tools() = listOf(
        Bridge.Tool("photos_list", "Liste les photos du dossier ouvert dans Netrunner Photos (nom, taille, date)."),
        Bridge.Tool("photos_open_folder", "Ouvre un dossier dans la galerie de Netrunner Photos.", listOf(Bridge.Param("path", "chemin du dossier, ~ pour le dossier personnel"))),
        Bridge.Tool("photos_show", "Affiche une photo du dossier ouvert, par son nom (ou une partie) ou son numéro.", listOf(Bridge.Param("photo", "nom ou numéro de la photo"))),
        Bridge.Tool("photos_next", "Passe à la photo suivante."),
        Bridge.Tool("photos_previous", "Revient à la photo précédente."),
        Bridge.Tool("photos_rotate", "Fait pivoter la photo affichée d'un quart de tour à droite (à l'écran seulement)."),
        Bridge.Tool("photos_slideshow", "Lance ou arrête le diaporama.", listOf(Bridge.Param("on", "true pour lancer, false pour arrêter", "boolean"))),
        Bridge.Tool("photos_gallery", "Revient à la galerie."),
        Bridge.Tool("photos_trash", "Met la photo affichée à la corbeille. Demande d'abord à l'utilisateur de confirmer.", confirm = true),
    )

    override fun run(tool: String, args: Map<String, String>): String = ui {
        when (tool) {
            "photos_list" -> state.folder.images.take(200).mapIndexed { i, f -> "${i + 1}. ${f.name} · ${Images.describe(f, null)}" }
                .joinToString("\n").ifEmpty { "Aucune photo dans ${state.folder.dir.path}." }
            "photos_open_folder" -> {
                val path = args["path"].orEmpty().trim().replaceFirst(Regex("^~"), System.getProperty("user.home"))
                val dir = File(path)
                if (!dir.isDirectory) return@ui "Pas de dossier « $path »."
                state.open(dir)
                "Dossier ${state.folder.dir.path} ouvert : ${state.folder.images.size} photos."
            }
            "photos_show" -> {
                val q = args["photo"].orEmpty().trim()
                val images = state.folder.images
                val i = q.toIntOrNull()?.minus(1)?.takeIf { it in images.indices }
                    ?: images.indexOfFirst { it.name.equals(q, ignoreCase = true) }.takeIf { it >= 0 }
                    ?: images.indexOfFirst { it.name.contains(q, ignoreCase = true) }.takeIf { it >= 0 && q.isNotEmpty() }
                    ?: return@ui "Aucune photo « $q » dans ce dossier."
                state.show(i)
                "Photo ${images[i].name} affichée."
            }
            "photos_next" -> if (state.current == null) { state.show(0); "Première photo affichée." } else { state.next(); "Photo ${state.current?.name} affichée." }
            "photos_previous" -> if (state.current == null) "Aucune photo affichée." else { state.previous(); "Photo ${state.current?.name} affichée." }
            "photos_rotate" -> if (state.current == null) "Aucune photo affichée." else { state.rotate(); "Photo pivotée de ${state.rotation}°." }
            "photos_slideshow" -> {
                val on = args["on"]?.lowercase() != "false"
                if (on && state.current == null) state.show(0)
                state.slideshow = on && state.current != null
                if (state.slideshow) "Diaporama lancé." else "Diaporama arrêté."
            }
            "photos_gallery" -> { state.close(); "Retour à la galerie." }
            "photos_trash" -> state.trashNow()
            else -> "Outil inconnu : $tool"
        }
    }
}
