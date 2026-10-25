package dev.netrunner.os.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.netrunner.bridge.Bridge
import dev.netrunner.os.Button
import dev.netrunner.os.Cp
import dev.netrunner.os.MonoFont
import dev.netrunner.os.T
import dev.netrunner.os.TitleFont
import dev.netrunner.os.badge
import dev.netrunner.os.hudPanel
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Netrunner Vidéo: plays a video (or music) and the rest of its folder after it. */
fun video(args: List<String>) {
    val arg = args.firstOrNull()?.let { if (it.startsWith("file://")) File(java.net.URI(it)) else File(it) }
    application {
        val state = remember { VideoState(arg) }
        val window = rememberWindowState(width = 1200.dp, height = 760.dp)
        DisposableEffect(state) {
            val stop = Bridge.serve(VideoBridge(state, window))
            onDispose {
                stop()
                state.player.release()
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = (state.current?.name ?: "Vidéo") + " · Vidéo",
            icon = remember { badge(Cp.Red) },
            state = window,
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && state.key(e.key, window) },
        ) {
            VideoScreen(state, window)
        }
    }
}

class VideoState(arg: File?) {
    var playlist by mutableStateOf(emptyList<File>())
        private set
    var index by mutableStateOf(-1)
        private set
    var time by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var message by mutableStateOf("")
    var lastMove by mutableLongStateOf(System.currentTimeMillis())

    val player = Player(onFinished = { javax.swing.SwingUtilities.invokeLater { if (index + 1 < playlist.size) show(index + 1) else playing = false } })
    val current: File? get() = playlist.getOrNull(index)

    init {
        if (arg != null && (arg.isDirectory || arg.isFile)) open(arg)
    }

    fun open(file: File) {
        val (list, at) = Media.playlist(file)
        playlist = list
        if (list.isEmpty()) {
            message = "Aucune vidéo dans ${file.path}"
            return
        }
        show(at)
    }

    fun show(i: Int) {
        if (playlist.isEmpty()) return
        index = i.mod(playlist.size)
        message = ""
        player.play(playlist[index])
    }

    /** Called four times a second: the player's state as Compose state. */
    fun tick() {
        time = player.time
        length = player.length
        playing = player.playing
    }

    fun flash(text: String) {
        message = text
        lastMove = System.currentTimeMillis()
    }

    fun key(key: Key, window: WindowState): Boolean {
        val full = window.placement == WindowPlacement.Fullscreen
        lastMove = System.currentTimeMillis()
        when (key) {
            Key.Spacebar, Key.K -> player.toggle()
            Key.DirectionRight -> player.skip(10_000)
            Key.DirectionLeft -> player.skip(-10_000)
            Key.L -> player.skip(60_000)
            Key.J -> player.skip(-60_000)
            Key.DirectionUp -> { player.volume += 5; flash("Volume ${player.volume} %") }
            Key.DirectionDown -> { player.volume -= 5; flash("Volume ${player.volume} %") }
            Key.N, Key.PageDown -> show(index + 1)
            Key.P, Key.PageUp -> show(index - 1)
            Key.S -> flash("Sous-titres : " + player.nextSubtitles())
            Key.A -> flash("Audio : " + player.nextAudio())
            Key.F, Key.F11 -> window.placement = if (full) WindowPlacement.Floating else WindowPlacement.Fullscreen
            Key.Escape -> if (full) window.placement = WindowPlacement.Floating else return false
            else -> return false
        }
        return true
    }

    fun chooseFile() {
        val dialog = FileDialog(null as Frame?, "Ouvrir une vidéo", FileDialog.LOAD)
        current?.parentFile?.let { dialog.directory = it.path }
        dialog.isVisible = true
        val name = dialog.file ?: return
        open(File(dialog.directory, name))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VideoScreen(state: VideoState, window: WindowState) {
    LaunchedEffect(Unit) {
        while (true) {
            state.tick()
            delay(250)
        }
    }
    val full = window.placement == WindowPlacement.Fullscreen
    // Controls fade away after 3 s without a move while playing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.lastMove) {
        now = System.currentTimeMillis()
        delay(3100)
        now = System.currentTimeMillis()
    }
    val showControls = !state.playing || now - state.lastMove < 3000
    Column(Modifier.fillMaxSize().background(Cp.Background)) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                .onPointerEvent(PointerEventType.Move) { state.lastMove = System.currentTimeMillis() }
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f) {
                        state.player.volume += if (dy < 0) 5 else -5
                        state.flash("Volume ${state.player.volume} %")
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { state.player.toggle() },
                        onDoubleTap = { window.placement = if (window.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val frame = state.player.frame
            val file = state.current
            when {
                state.player.error != null -> T(state.player.error, Cp.Red, 16.sp, modifier = Modifier.padding(24.dp))
                file == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    T("VIDÉO", Cp.Red, 32.sp, TitleFont, FontWeight.Bold)
                    T(state.message.ifEmpty { "Ouvre une vidéo, ou glisse-la sur l'icône." }, Cp.Dim, 16.sp)
                    Button("OUVRIR UNE VIDÉO…", Cp.Red, filled = true) { state.chooseFile() }
                }
                frame == null && Media.isAudio(file) -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    T("♪", Cp.Red, 64.sp)
                    T(file.nameWithoutExtension, Cp.Text, 20.sp)
                }
                frame == null -> T("CHARGEMENT…", Cp.Dim, 16.sp, MonoFont, FontWeight.Normal)
                else -> Image(frame, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (state.message.isNotEmpty() && file != null && now - state.lastMove < 3000) {
                T(state.message, Cp.Yellow, 16.sp, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).hudPanel(Cp.Yellow).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        if (!full || showControls) Controls(state, window)
    }
}

@Composable
private fun Controls(state: VideoState, window: WindowState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Timeline(state)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("◄◄") { state.show(state.index - 1) }
            Button(if (state.playing) "❚❚" else "►", Cp.Red, filled = true) { state.player.toggle() }
            Button("►►") { state.show(state.index + 1) }
            T(Media.time(state.time) + " / " + Media.time(state.length), Cp.Text, 15.sp, MonoFont, FontWeight.Normal)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                T(state.current?.name.orEmpty(), Cp.Text, 15.sp, maxLines = 1)
                if (state.playlist.size > 1) T("${state.index + 1} / ${state.playlist.size} dans le dossier", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
            }
            Button("− 10 s", Cp.Cyan) { state.player.skip(-10_000) }
            Button("+ 10 s", Cp.Cyan) { state.player.skip(10_000) }
            Button("SOUS-TITRES", Cp.Cyan) { state.flash("Sous-titres : " + state.player.nextSubtitles()) }
            Button("AUDIO", Cp.Cyan) { state.flash("Audio : " + state.player.nextAudio()) }
            Button("VOL ${state.player.volume} %", Cp.Cyan) { state.player.volume = if (state.player.volume >= 100) 50 else state.player.volume + 25 }
            Button("PLEIN ÉCRAN") {
                window.placement = if (window.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
            }
            Button("OUVRIR…") { state.chooseFile() }
        }
    }
}

@Composable
private fun Timeline(state: VideoState) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(14.dp).hudPanel(Cp.Red.copy(alpha = 0.5f), Cp.Panel, 4.dp)
            .pointerInput(state.length) {
                detectTapGestures { p -> if (state.length > 0) state.player.seek((p.x / size.width * state.length).toLong()) }
            },
    ) {
        val part = if (state.length > 0) (state.time.toFloat() / state.length).coerceIn(0f, 1f) else 0f
        Box(Modifier.width(maxWidth * part).height(14.dp).background(Cp.Red))
    }
}
