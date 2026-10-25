package dev.netrunner.os.montage

import dev.netrunner.bridge.Bridge
import java.io.File
import javax.swing.SwingUtilities

/**
 * What As//ka sees of Netrunner Montage (the clips, the playhead) and what she can do: add
 * videos and photos, write titles, trim, cut, reorder, put music, export the video.
 */
class MontageBridge(private val state: MontageState) : Bridge.App {
    override val id = "montage"
    override val title = "Netrunner Montage"

    private fun <T> ui(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun context(): String = ui {
        val p = state.project
        "Netrunner Montage : « ${state.file?.nameWithoutExtension ?: "Sans titre"} »" + (if (state.dirty) " (non enregistré)" else "") +
            ", ${p.clips.size} clip(s), ${clock(p.duration)} au total, ${p.width}×${p.height}" +
            (p.music?.let { ", musique ${File(it.file).name} à ${it.volume} %" } ?: "") +
            ", tête de lecture à ${clock(state.position)}" + (state.selected?.let { ", clip ${it + 1} choisi" } ?: "") +
            (state.exporting?.let { ", export en cours (${(it * 100).toInt()} %)" } ?: "") +
            ". Temps en secondes (12,5) ou en minutes:secondes (1:12,5). Pour voir les clips, utilise montage_read."
    }

    private val clipParam = Bridge.Param("clip", "numéro du clip", "integer")

    override fun tools() = listOf(
        Bridge.Tool("montage_read", "Liste les clips du montage : numéro, type, fichier, début et fin dans la source, durée, place dans le montage."),
        Bridge.Tool("montage_add", "Ajoute des vidéos, des photos (ou une musique) après le clip choisi ou à la fin.", listOf(Bridge.Param("paths", "chemins des fichiers, un par ligne, ~ pour le dossier personnel"))),
        Bridge.Tool("montage_title", "Ajoute un carton de titre (texte sur fond sombre).", listOf(Bridge.Param("text", "le texte, sur une ou deux lignes"), Bridge.Param("seconds", "durée en secondes", "number", false), Bridge.Param("after", "numéro du clip après lequel le mettre (0 pour le début)", "integer", false))),
        Bridge.Tool("montage_trim", "Change le début et la fin d'un clip vidéo dans sa source, ou la durée d'une photo ou d'un titre.", listOf(clipParam, Bridge.Param("start", "début dans la source", required = false), Bridge.Param("end", "fin dans la source (ou durée pour une photo, un titre)", required = false))),
        Bridge.Tool("montage_split", "Coupe le montage en deux à un moment donné (un clip devient deux).", listOf(Bridge.Param("at", "moment dans le montage"))),
        Bridge.Tool("montage_delete", "Retire un clip du montage.", listOf(clipParam)),
        Bridge.Tool("montage_move", "Déplace un clip à une autre place.", listOf(clipParam, Bridge.Param("to", "nouvelle place", "integer"))),
        Bridge.Tool("montage_sound", "Règle le son d'un clip vidéo (volume, ou muet).", listOf(clipParam, Bridge.Param("volume", "0 à 200 %", "integer", false), Bridge.Param("muted", "true pour couper le son", "boolean", false))),
        Bridge.Tool("montage_music", "Met une musique sous tout le montage (ou la retire), avec son volume.", listOf(Bridge.Param("path", "chemin de la musique, vide pour la retirer", required = false), Bridge.Param("volume", "0 à 200 %", "integer", false))),
        Bridge.Tool("montage_seek", "Place la tête de lecture et montre l'image à ce moment.", listOf(Bridge.Param("at", "moment dans le montage"), Bridge.Param("play", "true pour lancer la lecture", "boolean", false))),
        Bridge.Tool("montage_undo", "Annule la dernière modification."),
        Bridge.Tool("montage_save", "Enregistre le projet de montage (.montage).", listOf(Bridge.Param("path", "chemin, seulement pour un nouveau projet", required = false))),
        Bridge.Tool("montage_export", "Fabrique la vidéo finale en MP4 (en arrière-plan ; la progression se lit dans le contexte).", listOf(Bridge.Param("path", "chemin du fichier .mp4"), Bridge.Param("quality", "1080p, 720p ou 480p", required = false))),
    )

    private fun path(s: String) = File(s.trim().replaceFirst(Regex("^~"), System.getProperty("user.home")))

    override fun run(tool: String, args: Map<String, String>): String = ui {
        val p = state.project
        fun clip(name: String = "clip"): Int? = args[name]?.trim()?.toIntOrNull()?.minus(1)?.takeIf { it in p.clips.indices }
        fun time(name: String): Long? = args[name]?.takeIf { it.isNotBlank() }?.let { parseClock(it) }
        val which = "Le montage a ${p.clips.size} clip(s)."
        when (tool) {
            "montage_read" -> if (p.clips.isEmpty()) "Le montage est vide." else p.clips.mapIndexed { i, c ->
                "${i + 1}. ${c.kind.label} ${c.name} : " + (if (c.kind == Clip.Kind.Video) "de ${clock(c.start)} à ${clock(c.end)} (source ${clock(c.sourceLength)}), " else "") +
                    "durée ${clock(c.length)}, à ${clock(p.startOf(i))} dans le montage" + (if (c.muted) ", muet" else if (c.kind == Clip.Kind.Video && c.volume != 100) ", son ${c.volume} %" else "") +
                    (if (c.kind == Clip.Kind.Title) ", texte « ${c.text} »" else "")
            }.joinToString("\n")
            "montage_add" -> {
                val files = args["paths"].orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map(::path)
                val missing = files.filter { !it.isFile }
                if (files.isEmpty()) return@ui "Quels fichiers ?"
                if (missing.isNotEmpty()) return@ui "Introuvable(s) : " + missing.joinToString(", ") { it.path }
                state.add(files) + ". Le montage dure ${clock(state.project.duration)}."
            }
            "montage_title" -> {
                val text = args["text"].orEmpty()
                if (text.isBlank()) return@ui "Quel texte ?"
                val seconds = args["seconds"]?.replace(',', '.')?.toDoubleOrNull() ?: 3.0
                val after = args["after"]?.trim()?.toIntOrNull()?.coerceIn(0, p.clips.size)
                state.addTitle(text, seconds, after ?: ((state.selected ?: (p.clips.size - 1)) + 1))
                "Titre ajouté en ${(state.selected ?: 0) + 1}."
            }
            "montage_trim" -> {
                val i = clip() ?: return@ui which
                val c = p.clips[i]
                val s = time("start")
                val e = time("end")
                if (s == null && e == null) return@ui "Donne start, end ou les deux."
                state.edit(i) { if (it.kind == Clip.Kind.Video) it.trim(s ?: it.start, e ?: it.end) else it.trim(0, e ?: it.length) }
                val n = state.project.clips[i]
                "Clip ${i + 1} : " + (if (c.kind == Clip.Kind.Video) "de ${clock(n.start)} à ${clock(n.end)}, " else "") + "durée ${clock(n.length)}."
            }
            "montage_split" -> {
                val at = time("at") ?: return@ui "À quel moment ?"
                val before = p.clips.size
                state.seek(at)
                state.split()
                if (state.project.clips.size > before) "Coupé à ${clock(at)}." else "Rien à couper à ${clock(at)} (une coupe y est déjà, ou trop près)."
            }
            "montage_delete" -> { val i = clip() ?: return@ui which; state.delete(i); "Clip ${i + 1} retiré, il en reste ${state.project.clips.size}." }
            "montage_move" -> {
                val i = clip() ?: return@ui which
                val to = clip("to") ?: return@ui which
                state.selected = i
                state.move(to - i)
                "Clip déplacé en ${to + 1}."
            }
            "montage_sound" -> {
                val i = clip() ?: return@ui which
                if (p.clips[i].kind != Clip.Kind.Video) return@ui "Le clip ${i + 1} n'a pas de son (photo ou titre)."
                state.edit(i) { c ->
                    c.copy(volume = args["volume"]?.trim()?.toIntOrNull()?.coerceIn(0, 200) ?: c.volume, muted = args["muted"]?.lowercase()?.let { it == "true" } ?: c.muted)
                }
                state.project.clips[i].let { "Clip ${i + 1} : " + if (it.muted) "muet." else "son à ${it.volume} %." }
            }
            "montage_music" -> {
                val raw = args["path"]?.trim().orEmpty()
                val volume = args["volume"]?.trim()?.toIntOrNull()
                when {
                    raw.isEmpty() && volume != null -> { state.musicVolume(volume); "Musique à ${state.project.music?.volume ?: 0} %." }
                    raw.isEmpty() -> { state.setMusic(null); "Musique retirée." }
                    else -> {
                        val f = path(raw)
                        if (!f.isFile) return@ui "Pas de fichier « ${f.path} »."
                        state.setMusic(f, volume ?: state.project.music?.volume ?: 60)
                        "Musique : ${f.name} à ${state.project.music?.volume} %."
                    }
                }
            }
            "montage_seek" -> {
                val at = time("at") ?: return@ui "À quel moment ?"
                state.seek(at)
                if (args["play"]?.lowercase() == "true") state.play(true)
                "Tête de lecture à ${clock(state.position)}."
            }
            "montage_undo" -> { state.undo(); "Annulé." }
            "montage_save" -> {
                val target = args["path"]?.takeIf { it.isNotBlank() }?.let(::path) ?: state.file ?: return@ui "Le projet n'a pas encore de fichier : donne un chemin (.montage)."
                val f = if (target.extension.lowercase() == Project.Extension) target else File(target.path + ".${Project.Extension}")
                if (state.saveTo(f)) "Enregistré : ${f.path}" else state.message
            }
            "montage_export" -> {
                val raw = args["path"].orEmpty()
                if (raw.isBlank()) return@ui "Où mettre la vidéo ? (chemin .mp4)"
                val f = path(raw).let { if (it.extension.lowercase() == "mp4") it else File(it.path + ".mp4") }
                val q = when (args["quality"]?.trim()?.lowercase()) { "720p", "720" -> Render.Quality.Medium; "480p", "480" -> Render.Quality.Small; else -> Render.Quality.High }
                state.export(f, q)
            }
            else -> "Outil inconnu : $tool"
        }
    }
}
