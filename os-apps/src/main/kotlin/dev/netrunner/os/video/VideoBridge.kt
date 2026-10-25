package dev.netrunner.os.video

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import dev.netrunner.bridge.Bridge
import java.io.File
import javax.swing.SwingUtilities

/** What As//ka sees of Netrunner Vidéo, and what she can do in it. */
class VideoBridge(private val state: VideoState, private val window: WindowState) : Bridge.App {
    override val id = "video"
    override val title = "Netrunner Vidéo"

    private fun <T> ui(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun context(): String = ui {
        val file = state.current ?: return@ui "Netrunner Vidéo est ouvert, sans vidéo."
        "Netrunner Vidéo ${if (state.player.playing) "lit" else "est en pause sur"} « ${file.name} » à ${Media.time(state.player.time)} sur ${Media.time(state.player.length)}" +
            (if (state.playlist.size > 1) " (${state.index + 1} sur ${state.playlist.size} du dossier ${file.parent})" else "") +
            ", volume ${state.player.volume} %."
    }

    override fun tools() = listOf(
        Bridge.Tool("video_open", "Ouvre une vidéo ou un dossier de vidéos dans Netrunner Vidéo.", listOf(Bridge.Param("path", "chemin du fichier ou du dossier, ~ pour le dossier personnel"))),
        Bridge.Tool("video_play", "Reprend la lecture."),
        Bridge.Tool("video_pause", "Met en pause."),
        Bridge.Tool("video_seek", "Va à un moment de la vidéo.", listOf(Bridge.Param("time", "moment, par exemple 1:23, 90 ou 1h05"))),
        Bridge.Tool("video_skip", "Avance ou recule de quelques secondes.", listOf(Bridge.Param("seconds", "secondes, négatif pour reculer", "integer"))),
        Bridge.Tool("video_volume", "Règle le volume.", listOf(Bridge.Param("percent", "0 à 150", "integer"))),
        Bridge.Tool("video_next", "Passe à la vidéo suivante du dossier."),
        Bridge.Tool("video_previous", "Revient à la vidéo précédente du dossier."),
        Bridge.Tool("video_list", "Liste les vidéos du dossier en cours."),
        Bridge.Tool("video_subtitles", "Change de sous-titres (aucun, puis chaque piste)."),
        Bridge.Tool("video_audio", "Change de piste audio (langue)."),
        Bridge.Tool("video_fullscreen", "Passe en plein écran ou en sort.", listOf(Bridge.Param("on", "true ou false", "boolean"))),
    )

    override fun run(tool: String, args: Map<String, String>): String = ui {
        val p = state.player
        when (tool) {
            "video_open" -> {
                val path = args["path"].orEmpty().trim().replaceFirst(Regex("^~"), System.getProperty("user.home"))
                if (!File(path).exists()) return@ui "Rien à « $path »."
                state.open(File(path))
                state.current?.let { "Lecture de ${it.name}." } ?: state.message
            }
            "video_play" -> { p.pause(false); "Lecture." }
            "video_pause" -> { p.pause(true); "Pause." }
            "video_seek" -> {
                val ms = Media.parseTime(args["time"].orEmpty()) ?: return@ui "Moment illisible : ${args["time"]}"
                p.seek(ms)
                "À ${Media.time(ms)}."
            }
            "video_skip" -> {
                val s = args["seconds"]?.toLongOrNull() ?: return@ui "Combien de secondes ?"
                p.skip(s * 1000)
                "À ${Media.time(p.time + s * 1000)}."
            }
            "video_volume" -> {
                val v = args["percent"]?.toIntOrNull() ?: return@ui "Quel volume ?"
                p.volume = v
                "Volume ${p.volume} %."
            }
            "video_next" -> { state.show(state.index + 1); "Lecture de ${state.current?.name}." }
            "video_previous" -> { state.show(state.index - 1); "Lecture de ${state.current?.name}." }
            "video_list" -> state.playlist.mapIndexed { i, f -> "${i + 1}. ${f.name}" + if (i == state.index) " (en cours)" else "" }.joinToString("\n").ifEmpty { "Aucune vidéo." }
            "video_subtitles" -> "Sous-titres : " + p.nextSubtitles()
            "video_audio" -> "Audio : " + p.nextAudio()
            "video_fullscreen" -> {
                val on = args["on"]?.lowercase() != "false"
                window.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
                if (on) "Plein écran." else "Fenêtre."
            }
            else -> "Outil inconnu : $tool"
        }
    }
}
