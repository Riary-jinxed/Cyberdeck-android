package dev.netrunner.os.montage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.netrunner.bridge.Bridge
import dev.netrunner.os.Button
import dev.netrunner.os.Cp
import dev.netrunner.os.HudFont
import dev.netrunner.os.MonoFont
import dev.netrunner.os.T
import dev.netrunner.os.TitleFont
import dev.netrunner.os.badge
import dev.netrunner.os.hudPanel
import dev.netrunner.os.video.Player
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Netrunner Montage: puts videos, photos and titles end to end, with music, and makes one video. */
fun montage(args: List<String>) {
    val arg = args.firstOrNull()?.let { if (it.startsWith("file://")) File(java.net.URI(it)) else File(it) }
    application {
        val state = remember { MontageState().apply { if (arg != null && arg.isFile) { if (arg.extension.lowercase() == Project.Extension) open(arg) else add(listOf(arg)) } } }
        DisposableEffect(state) {
            val stop = Bridge.serve(MontageBridge(state))
            onDispose {
                stop()
                state.player.release()
            }
        }
        Window(
            onCloseRequest = { if (state.confirmDiscard("fermer")) { state.cancelExport = true; exitApplication() } },
            title = (if (state.dirty) "● " else "") + (state.file?.nameWithoutExtension ?: "Sans titre") + " · Montage",
            icon = remember { badge(Cp.Red) },
            state = rememberWindowState(width = 1400.dp, height = 920.dp),
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && state.key(e) },
        ) {
            MontageScreen(state)
        }
    }
}

class MontageState {
    var project by mutableStateOf(Project())
        private set
    var selected by mutableStateOf<Int?>(null)
    /** The playhead, in the montage's time. */
    var position by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    var file by mutableStateOf<File?>(null)
    var dirty by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
    /** Export progress, 0 to 1, or null when not exporting. */
    var exporting by mutableStateOf<Float?>(null)
        private set
    @Volatile var cancelExport = false
    /** A title's text is being typed: plain keys are letters, not shortcuts. */
    var typing by mutableStateOf(false)
    /** Pixels per second on the timeline. */
    var zoom by mutableStateOf(40f)

    val player = Player(onFinished = { SwingUtilities.invokeLater { if (playing) nextClip() } })

    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()

    /** Which clip and file the player holds now. */
    private var loaded: String? = null
    private var pendingSeek: Long? = null
    private var lastTick = 0L

    private fun commit(next: Project) {
        undo.addLast(project)
        if (undo.size > 200) undo.removeFirst()
        redo.clear()
        project = next
        dirty = true
        selected = selected?.takeIf { it in next.clips.indices }
        position = position.coerceIn(0, next.duration)
        loaded = null
        show()
    }

    fun undo() { val p = undo.removeLastOrNull() ?: return; redo.addLast(project); project = p; dirty = true; loaded = null; show() }
    fun redo() { val p = redo.removeLastOrNull() ?: return; undo.addLast(project); project = p; dirty = true; loaded = null; show() }

    val clip: Clip? get() = selected?.let { project.clips.getOrNull(it) }

    // ---- Editing.

    /** Videos, photos (and music, which goes under everything) at the end or after the selection. */
    fun add(files: List<File>): String {
        var p = project
        var at = (selected ?: (p.clips.size - 1)) + 1
        val skipped = mutableListOf<String>()
        var added = 0
        for (f in files) {
            val ext = f.extension.lowercase()
            when {
                ext in Probe.Sounds -> { p = p.copy(music = Music(f.path, p.music?.volume ?: 60)); added++ }
                ext in Probe.Images -> { p = p.insert(at++, Clip(Clip.Kind.Image, f.path, 0, 4000)); added++ }
                else -> {
                    val probe = Probe.of(f)
                    if (probe == null || !probe.video || probe.duration <= 0) { skipped += f.name; continue }
                    p = p.insert(at++, Clip(Clip.Kind.Video, f.path, 0, probe.duration, sourceLength = probe.duration))
                    added++
                }
            }
        }
        if (added > 0) {
            if (project.clips.isEmpty()) p = fitFirst(p)
            commit(p)
            selected = (at - 1).coerceIn(0, p.clips.size - 1)
        }
        message = if (skipped.isEmpty()) "" else "Illisible : " + skipped.joinToString(", ")
        return "$added ajouté(s)" + if (skipped.isEmpty()) "" else ", illisible(s) : " + skipped.joinToString(", ")
    }

    /** The montage takes the shape of its first video (portrait from a phone, 4:3…). */
    private fun fitFirst(p: Project): Project {
        val v = p.clips.firstOrNull { it.kind == Clip.Kind.Video } ?: return p
        val probe = Probe.of(File(v.file!!)) ?: return p
        if (probe.width <= 0 || probe.height <= 0) return p
        val h = minOf(1080, probe.height).let { it - it % 2 }
        val w = (probe.width.toLong() * h / probe.height).toInt().let { it - it % 2 }
        return p.copy(width = w, height = h)
    }

    fun addTitle(text: String = "Titre", seconds: Double = 3.0, at: Int = (selected ?: (project.clips.size - 1)) + 1) {
        commit(project.insert(at, Clip(Clip.Kind.Title, null, 0, (seconds * 1000).toLong().coerceAtLeast(Project.MinLength), text)))
        selected = at.coerceIn(0, project.clips.size - 1)
    }

    fun edit(i: Int, change: (Clip) -> Clip) {
        val c = project.clips.getOrNull(i) ?: return
        commit(project.with(i, change(c)))
    }

    fun split() {
        val before = project.clips.size
        commit(project.split(position))
        if (project.clips.size == before) message = "Rien à couper ici (trop près d'une coupe)."
    }

    fun delete(i: Int? = selected) {
        i ?: return
        commit(project.remove(i))
        selected = null
    }

    fun move(by: Int) {
        val i = selected ?: return
        val to = (i + by).coerceIn(0, project.clips.size - 1)
        if (to == i) return
        commit(project.move(i, to))
        selected = to
    }

    /** Sets the selected clip's start or end where the preview is. */
    fun markHere(start: Boolean) {
        val i = selected ?: return
        val (at, off) = project.at(position) ?: return
        if (at != i) { message = "Place la tête de lecture sur le clip choisi."; return }
        val c = project.clips[i]
        val src = c.start + off
        edit(i) { if (start) it.trim(src, it.end) else it.trim(it.start, src) }
        if (start) seek(project.startOf(i))
    }

    fun setMusic(f: File?, volume: Int = project.music?.volume ?: 60) =
        commit(project.copy(music = f?.let { Music(it.path, volume.coerceIn(0, 200)) }))

    fun musicVolume(v: Int) { project.music?.let { commit(project.copy(music = it.copy(volume = v.coerceIn(0, 200)))) } }

    // ---- Preview.

    fun seek(ms: Long) {
        position = ms.coerceIn(0, project.duration)
        show()
    }

    fun play(on: Boolean = !playing) {
        if (project.clips.isEmpty()) return
        if (on && position >= project.duration - 50) position = 0
        playing = on
        lastTick = System.currentTimeMillis()
        show()
    }

    /** Puts the preview where the playhead is. */
    private fun show() {
        val (i, off) = project.at(position) ?: run { player.pause(true); return }
        val c = project.clips[i]
        if (c.kind != Clip.Kind.Video) {
            player.pause(true)
            return
        }
        val key = "$i:${c.file}"
        if (loaded != key) {
            loaded = key
            player.play(File(c.file!!))
            pendingSeek = c.start + off
        } else {
            player.seek(c.start + off)
        }
        player.pause(!playing)
        player.volume = if (c.muted) 0 else c.volume
    }

    private fun nextClip() {
        val (i, _) = project.at(position) ?: return
        if (i + 1 >= project.clips.size) { playing = false; position = project.duration; player.pause(true); return }
        position = project.startOf(i + 1)
        show()
    }

    /** Twenty-five times a second: moves the playhead along. */
    fun tick() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTick
        lastTick = now
        pendingSeek?.let { s -> if (player.length > 0) { player.seek(s); player.pause(!playing); pendingSeek = null } }
        if (!playing) return
        val (i, _) = project.at(position) ?: return
        val c = project.clips[i]
        if (c.kind == Clip.Kind.Video) {
            if (pendingSeek != null) return
            val t = player.time
            if (t >= c.end - 30) nextClip() else position = project.startOf(i) + (t - c.start).coerceAtLeast(0)
        } else {
            val next = position + elapsed
            if (next >= project.startOf(i) + c.length) nextClip() else position = next
        }
    }

    // ---- Files.

    fun open(f: File) {
        runCatching { Project.read(f) }.onSuccess { p ->
            project = p
            file = f
            dirty = false
            undo.clear()
            redo.clear()
            selected = null
            position = 0
            loaded = null
            val missing = (p.clips.mapNotNull { it.file } + listOfNotNull(p.music?.file)).filter { !File(it).isFile }
            message = if (missing.isEmpty()) "" else "Fichiers introuvables : " + missing.joinToString(", ") { File(it).name }
            show()
        }.onFailure { message = "Impossible d'ouvrir ${f.name} : ${it.message}" }
    }

    fun new() {
        project = Project()
        file = null
        dirty = false
        undo.clear()
        redo.clear()
        selected = null
        position = 0
        loaded = null
        message = ""
        player.pause(true)
    }

    fun save(asNew: Boolean = false): Boolean {
        val target = file?.takeIf { !asNew } ?: choose("Enregistrer le montage", FileDialog.SAVE, (file?.nameWithoutExtension ?: "Montage") + ".${Project.Extension}")
            ?.let { if (it.extension.lowercase() == Project.Extension) it else File(it.path + ".${Project.Extension}") } ?: return false
        return saveTo(target)
    }

    fun saveTo(target: File): Boolean = runCatching { Project.write(project, target) }.fold(
        onSuccess = { file = target; dirty = false; message = "Enregistré : ${target.path}"; true },
        onFailure = { message = "Pas enregistré : ${it.message}"; false },
    )

    fun choose(title: String, mode: Int, name: String? = null, filter: Set<String>? = null): File? {
        val dialog = FileDialog(null as Frame?, title, mode)
        dialog.directory = (file?.parentFile ?: File(System.getProperty("user.home"), "Vidéos").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).path
        name?.let { dialog.file = it }
        filter?.let { exts -> dialog.setFilenameFilter { _, n -> n.substringAfterLast('.').lowercase() in exts } }
        dialog.isVisible = true
        val n = dialog.file ?: return null
        return File(dialog.directory, n)
    }

    fun chooseMedia() {
        val dialog = FileDialog(null as Frame?, "Ajouter des vidéos, des photos ou une musique", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.directory = (File(System.getProperty("user.home"), "Vidéos").takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).path
        dialog.setFilenameFilter { _, n -> n.substringAfterLast('.').lowercase() in Probe.Videos + Probe.Images + Probe.Sounds }
        dialog.isVisible = true
        dialog.files?.toList()?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }

    fun chooseOpen() {
        if (!confirmDiscard("ouvrir un autre montage")) return
        choose("Ouvrir un montage", FileDialog.LOAD, filter = setOf(Project.Extension))?.let { open(it) }
    }

    fun confirmDiscard(action: String): Boolean {
        if (!dirty) return true
        val choice = JOptionPane.showOptionDialog(
            null, "Le montage a des modifications non enregistrées. Les enregistrer avant de $action ?", "Montage",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, arrayOf("Enregistrer", "Ne pas enregistrer", "Annuler"), "Enregistrer",
        )
        return when (choice) { 0 -> save(); 1 -> true; else -> false }
    }

    // ---- Export.

    /** The HUD font for titles, out of the jar for ffmpeg. */
    private val font: File by lazy {
        File.createTempFile("netrunner-titre", ".ttf").apply {
            deleteOnExit()
            MontageState::class.java.getResourceAsStream("/font/rajdhani_bold.ttf")!!.use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    fun chooseExport() {
        if (project.clips.isEmpty()) { message = "Ajoute d'abord des vidéos ou des photos."; return }
        val q = JOptionPane.showOptionDialog(
            null, "Qualité de la vidéo (MP4) :", "Exporter", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
            Render.Quality.entries.map { it.label }.toTypedArray(), Render.Quality.High.label,
        ).takeIf { it >= 0 } ?: return
        val out = choose("Exporter la vidéo", FileDialog.SAVE, (file?.nameWithoutExtension ?: "Montage") + ".mp4")?.let { if (it.extension.lowercase() == "mp4") it else File(it.path + ".mp4") } ?: return
        export(out, Render.Quality.entries[q])
    }

    /** Makes the video in the background; says what it started. */
    fun export(out: File, quality: Render.Quality): String {
        if (exporting != null) return "Un export est déjà en cours."
        if (project.clips.isEmpty()) return "Le montage est vide."
        val missing = project.clips.mapNotNull { it.file }.filter { !File(it).isFile }
        if (missing.isNotEmpty()) return "Fichiers introuvables : " + missing.joinToString(", ") { File(it).name }
        val p = project
        cancelExport = false
        exporting = 0f
        message = "Export de ${out.name}…"
        Thread {
            val work = kotlin.io.path.createTempDirectory("netrunner-montage").toFile()
            val error = runCatching { Render.run(Render.command(p, out, quality, font, work), p.duration, { f -> SwingUtilities.invokeLater { exporting = f } }, { cancelExport }) }
                .getOrElse { it.message ?: it.toString() }
            work.deleteRecursively()
            SwingUtilities.invokeLater {
                exporting = null
                message = error ?: "Vidéo prête : ${out.path}"
                lastExport = if (error == null) out else lastExport
            }
        }.apply { isDaemon = true; name = "export"; start() }
        return "Export lancé vers ${out.path} (${quality.label}, ${clock(p.duration, false)})."
    }

    var lastExport by mutableStateOf<File?>(null)

    fun key(e: KeyEvent): Boolean {
        if (e.isCtrlPressed) {
            when (e.key) {
                Key.S -> save(asNew = e.isShiftPressed)
                Key.O -> chooseOpen()
                Key.N -> if (confirmDiscard("créer un nouveau montage")) new()
                Key.Z -> if (e.isShiftPressed) redo() else undo()
                Key.Y -> redo()
                Key.E -> chooseExport()
                Key.I -> chooseMedia()
                else -> return false
            }
            return true
        }
        if (typing) return false
        when (e.key) {
            Key.Spacebar -> play()
            Key.DirectionRight -> seek(position + if (e.isShiftPressed) 5000 else 1000 / project.fps.coerceAtLeast(1) * 4)
            Key.DirectionLeft -> seek(position - if (e.isShiftPressed) 5000 else 1000 / project.fps.coerceAtLeast(1) * 4)
            Key.Home -> seek(0)
            Key.MoveEnd -> seek(project.duration)
            Key.S -> split()
            Key.Delete -> delete()
            Key.I -> markHere(true)
            Key.O -> markHere(false)
            else -> return false
        }
        return true
    }
}

private val pictures = HashMap<String, ImageBitmap?>()

private fun picture(path: String): ImageBitmap? = pictures.getOrPut(path) {
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap() }.getOrNull()
}

@Composable
private fun MontageScreen(state: MontageState) {
    LaunchedEffect(Unit) {
        while (true) {
            state.tick()
            delay(40)
        }
    }
    Column(Modifier.fillMaxSize().background(Cp.Background)) {
        Toolbar(state)
        if (state.message.isNotEmpty()) T(state.message, Cp.Yellow, 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        state.exporting?.let { f ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                T("EXPORT ${(f * 100).toInt()} %", Cp.Cyan, 14.sp, MonoFont, FontWeight.Normal)
                BoxWithConstraints(Modifier.weight(1f).height(10.dp).hudPanel(Cp.Cyan.copy(alpha = 0.5f), Cp.Panel, 3.dp)) {
                    Box(Modifier.width(maxWidth * f).fillMaxHeight().background(Cp.Cyan))
                }
                Button("ANNULER", Cp.Dim) { state.cancelExport = true }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Preview(state, Modifier.weight(1f).fillMaxHeight())
            Inspector(state, Modifier.width(340.dp).fillMaxHeight())
        }
        Timeline(state)
    }
}

@Composable
private fun Toolbar(state: MontageState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        T("MONTAGE", Cp.Red, 22.sp, TitleFont, FontWeight.Bold)
        Box(Modifier.width(8.dp))
        Button("NOUVEAU", Cp.Red) { if (state.confirmDiscard("créer un nouveau montage")) state.new() }
        Button("OUVRIR…", Cp.Red) { state.chooseOpen() }
        Button("ENREGISTRER", Cp.Red, filled = state.dirty) { state.save() }
        Box(Modifier.width(8.dp))
        Button("↶", Cp.Dim) { state.undo() }
        Button("↷", Cp.Dim) { state.redo() }
        Box(Modifier.width(8.dp))
        Button("+ VIDÉOS / PHOTOS…", Cp.Cyan) { state.chooseMedia() }
        Button("+ TITRE", Cp.Cyan) { state.addTitle() }
        Button("+ MUSIQUE…", Cp.Cyan) { state.choose("Choisir une musique", FileDialog.LOAD, filter = Probe.Sounds)?.let { state.setMusic(it) } }
        Button("✂ COUPER", Cp.Yellow) { state.split() }
        Button("◄", Cp.Yellow) { state.move(-1) }
        Button("►", Cp.Yellow) { state.move(1) }
        Button("SUPPRIMER", Cp.Dim) { state.delete() }
        Box(Modifier.weight(1f))
        Button("EXPORTER LA VIDÉO…", Cp.Yellow, filled = true) { state.chooseExport() }
    }
}

@Composable
private fun Preview(state: MontageState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val at = state.project.at(state.position)
            val c = at?.let { state.project.clips[it.first] }
            when {
                state.player.error != null -> T(state.player.error, Cp.Red, 15.sp, modifier = Modifier.padding(24.dp))
                c == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    T("MONTAGE", Cp.Red, 30.sp, TitleFont, FontWeight.Bold)
                    T("Ajoute des vidéos et des photos, elles se mettent bout à bout. Couper : S. Début et fin d'un clip : I et O.", Cp.Dim, 15.sp)
                    Button("+ VIDÉOS / PHOTOS…", Cp.Red, filled = true) { state.chooseMedia() }
                }
                c.kind == Clip.Kind.Title -> Box(Modifier.fillMaxSize().background(Color(0xFF07070D)), contentAlignment = Alignment.Center) {
                    T(c.text, Cp.Yellow, 34.sp, weight = FontWeight.Bold)
                }
                c.kind == Clip.Kind.Image -> picture(c.file!!)?.let { Image(it, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: T("Photo illisible : ${c.name}", Cp.Red, 15.sp)
                else -> state.player.frame?.let { Image(it, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: T("CHARGEMENT…", Cp.Dim, 15.sp, MonoFont, FontWeight.Normal)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("|◄", Cp.Red) { state.seek(0) }
            Button(if (state.playing) "❚❚" else "►", Cp.Red, filled = true) { state.play() }
            T(clock(state.position) + " / " + clock(state.project.duration), Cp.Text, 15.sp, MonoFont, FontWeight.Normal)
            Box(Modifier.weight(1f))
            T("${state.project.width}×${state.project.height} · ${state.project.clips.size} clip(s)", Cp.Dim, 13.sp, MonoFont, FontWeight.Normal)
        }
    }
}

@Composable
private fun Inspector(state: MontageState, modifier: Modifier) {
    Column(modifier.hudPanel(Cp.Dim.copy(alpha = 0.5f), cut = 8.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val i = state.selected
        val c = state.clip
        if (c == null || i == null) {
            T("CLIP", Cp.Dim, 16.sp, TitleFont, FontWeight.Bold)
            T("Choisis un clip sur la ligne de temps pour le régler.", Cp.Dim, 14.sp)
        } else {
            T("${c.kind.label.uppercase()} ${i + 1}", Cp.Red, 16.sp, TitleFont, FontWeight.Bold)
            T(c.name, Cp.Text, 14.sp, maxLines = 2)
            T("Durée ${clock(c.length)}" + if (c.kind == Clip.Kind.Video) " · de ${clock(c.start)} à ${clock(c.end)} sur ${clock(c.sourceLength)}" else "", Cp.Dim, 13.sp, MonoFont, FontWeight.Normal)
            when (c.kind) {
                Clip.Kind.Video -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button("DÉBUT ICI (I)", Cp.Cyan) { state.markHere(true) }
                        Button("FIN ICI (O)", Cp.Cyan) { state.markHere(false) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(if (c.muted) "SON COUPÉ" else "SON ${c.volume} %", Cp.Yellow, filled = c.muted) { state.edit(i) { it.copy(muted = !it.muted) } }
                        Button("−", Cp.Dim) { state.edit(i) { it.copy(volume = (it.volume - 10).coerceAtLeast(0)) } }
                        Button("+", Cp.Dim) { state.edit(i) { it.copy(volume = (it.volume + 10).coerceAtMost(200)) } }
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("Durée", Cp.Dim, 14.sp)
                        Button("− 1 s", Cp.Cyan) { state.edit(i) { it.trim(0, it.length - 1000) } }
                        Button("+ 1 s", Cp.Cyan) { state.edit(i) { it.trim(0, it.length + 1000) } }
                    }
                    if (c.kind == Clip.Kind.Title) {
                        var text by remember(i) { mutableStateOf(c.text) }
                        Box(Modifier.fillMaxWidth().height(120.dp).hudPanel(Cp.Yellow.copy(alpha = 0.5f), cut = 6.dp).padding(10.dp)) {
                            BasicTextField(
                                text, { text = it }, textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                                cursorBrush = SolidColor(Cp.Yellow), modifier = Modifier.fillMaxSize().onFocusChanged { state.typing = it.isFocused },
                            )
                        }
                        Button("APPLIQUER LE TEXTE", Cp.Yellow, filled = text != c.text) { state.edit(i) { it.copy(text = text) } }
                    }
                }
            }
        }
        Box(Modifier.weight(1f))
        T("MUSIQUE", Cp.Dim, 14.sp, TitleFont, FontWeight.Bold)
        val m = state.project.music
        if (m == null) T("Aucune : « + MUSIQUE » pour en mettre une sous tout le montage.", Cp.Dim, 13.sp)
        else {
            T(File(m.file).name, Cp.Text, 14.sp, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                T("${m.volume} %", Cp.Cyan, 14.sp, MonoFont, FontWeight.Normal)
                Button("−", Cp.Dim) { state.musicVolume(m.volume - 10) }
                Button("+", Cp.Dim) { state.musicVolume(m.volume + 10) }
                Button("RETIRER", Cp.Dim) { state.setMusic(null) }
            }
        }
        state.lastExport?.let { f -> Button("OUVRIR LA VIDÉO EXPORTÉE", Cp.Cyan) { runCatching { ProcessBuilder("xdg-open", f.path).start() } } }
    }
}

@Composable
private fun Timeline(state: MontageState) {
    val scroll = rememberScrollState()
    val p = state.project
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T("LIGNE DE TEMPS", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
            Box(Modifier.weight(1f))
            Button("ZOOM −", Cp.Dim) { state.zoom = (state.zoom / 1.5f).coerceAtLeast(4f) }
            Button("ZOOM +", Cp.Dim) { state.zoom = (state.zoom * 1.5f).coerceAtMost(400f) }
        }
        Box(Modifier.fillMaxWidth().height(if (p.music != null) 110.dp else 84.dp).horizontalScroll(scroll)) {
            val zoom = state.zoom
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    p.clips.forEachIndexed { i, c ->
                        val on = state.selected == i
                        val color = when (c.kind) { Clip.Kind.Video -> Cp.Cyan; Clip.Kind.Image -> Cp.Yellow; Clip.Kind.Title -> Cp.Red }
                        Column(
                            Modifier.width((c.length / 1000f * zoom).coerceAtLeast(36f).dp).height(76.dp)
                                .background(color.copy(alpha = if (on) 0.35f else 0.15f))
                                .border(if (on) 2.dp else 1.dp, if (on) color else color.copy(alpha = 0.5f))
                                .pointerInput(i, c, zoom) {
                                    detectTapGestures { o ->
                                        state.selected = i
                                        state.seek(p.startOf(i) + (o.x / density / zoom * 1000).toLong().coerceIn(0, c.length - 1))
                                    }
                                }
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            T(c.name, Cp.Text, 12.sp, maxLines = 1)
                            T(clock(c.length), Cp.Dim, 11.sp, MonoFont, FontWeight.Normal, maxLines = 1)
                            if (c.kind == Clip.Kind.Video && c.muted) T("muet", Cp.Red, 11.sp, maxLines = 1)
                        }
                    }
                }
                p.music?.let { m ->
                    Box(Modifier.width((p.duration / 1000f * zoom).coerceAtLeast(36f).dp + (p.clips.size * 2).dp).height(26.dp).background(Cp.Red.copy(alpha = 0.15f)).border(1.dp, Cp.Red.copy(alpha = 0.5f)).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                        T("♪ " + File(m.file).name + " · ${m.volume} %", Cp.Text, 12.sp, maxLines = 1)
                    }
                }
            }
            // The playhead (clips are at least 36 px wide and 2 px apart: it follows the same layout).
            val (ci, off) = p.at(state.position) ?: (0 to 0L)
            val x = p.clips.take(ci).sumOf { (it.length / 1000.0 * zoom).coerceAtLeast(36.0) + 2 } +
                (p.clips.getOrNull(ci)?.let { off / 1000.0 * zoom } ?: 0.0)
            Box(Modifier.offset(x.toFloat().dp).width(2.dp).height(if (p.music != null) 110.dp else 84.dp).background(Cp.Yellow))
        }
    }
}
