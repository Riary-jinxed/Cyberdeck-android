package dev.netrunner.os.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

/** Netrunner Photos: a folder's photos as a grid, then one at a time with zoom, rotation and slideshow. */
fun photos(args: List<String>) {
    val start = Images.start(args)
    application {
        val state = remember { PhotosState(start) }
        DisposableEffect(state) {
            val stop = Bridge.serve(PhotosBridge(state))
            onDispose { stop() }
        }
        val window = rememberWindowState(width = 1200.dp, height = 800.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = state.title,
            icon = remember { badge(Cp.Cyan) },
            state = window,
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && state.key(e.key, window) },
        ) {
            Box(Modifier.fillMaxSize().background(Cp.Background)) {
                if (state.viewing == null) Browser(state) else Viewer(state, window)
            }
        }
    }
}

class PhotosState(start: Images.Start) {
    var folder by mutableStateOf(Images.list(start.dir))
        private set
    var viewing by mutableStateOf(start.file?.let { f -> folder.images.indexOf(f).takeIf { it >= 0 } })
        private set
    var zoom by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var rotation by mutableStateOf(0)
    var slideshow by mutableStateOf(false)
    var trashArmed by mutableStateOf(false)
    var message by mutableStateOf("")

    val current: File? get() = viewing?.let { folder.images.getOrNull(it) }

    val title: String get() = (current?.name ?: folder.dir.name.ifEmpty { folder.dir.path }) + " · Photos"

    fun open(dir: File) {
        folder = Images.list(dir.canonicalFile)
        viewing = null
        slideshow = false
        message = ""
    }

    fun show(i: Int) {
        if (folder.images.isEmpty()) return
        viewing = i.mod(folder.images.size)
        zoom = 1f
        offset = Offset.Zero
        rotation = 0
        trashArmed = false
        message = ""
    }

    fun next() = viewing?.let { show(it + 1) }
    fun previous() = viewing?.let { show(it - 1) }

    fun close() {
        viewing = null
        slideshow = false
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(1f, 12f)
        if (zoom == 1f) offset = Offset.Zero
    }

    fun rotate() {
        rotation = (rotation + 90) % 360
        zoom = 1f
        offset = Offset.Zero
    }

    /** First press arms, second sends the photo to the trash and shows the next one. */
    fun trash() {
        val file = current ?: return
        if (!trashArmed) {
            trashArmed = true
            return
        }
        trashNow()
    }

    /** Sends the photo on screen to the trash and shows the next one; says what happened. */
    fun trashNow(): String {
        val file = current ?: return "Aucune photo affichée."
        val at = viewing ?: 0
        if (!Images.trash(file)) {
            trashArmed = false
            message = "Impossible de mettre ${file.name} à la corbeille"
            return message
        }
        folder = Images.list(folder.dir)
        if (folder.images.isEmpty()) close() else show(minOf(at, folder.images.size - 1))
        message = "${file.name} est dans la corbeille"
        return message
    }

    fun key(key: Key, window: WindowState): Boolean {
        val full = window.placement == WindowPlacement.Fullscreen
        fun fullscreen(on: Boolean) {
            window.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }
        if (viewing == null) {
            return when (key) {
                Key.Backspace -> folder.dir.parentFile?.let { open(it) } != null
                Key.Enter -> if (folder.images.isNotEmpty()) { show(0); true } else false
                else -> false
            }
        }
        when (key) {
            Key.Escape -> if (full) fullscreen(false) else close()
            Key.DirectionRight, Key.PageDown -> next()
            Key.DirectionLeft, Key.PageUp -> previous()
            Key.Plus, Key.Equals, Key.NumPadAdd -> zoomBy(1.25f)
            Key.Minus, Key.NumPadSubtract -> zoomBy(0.8f)
            Key.Zero, Key.NumPad0 -> { zoom = 1f; offset = Offset.Zero }
            Key.R -> rotate()
            Key.F, Key.F11 -> fullscreen(!full)
            Key.Spacebar -> slideshow = !slideshow
            Key.Delete -> trash()
            else -> return false
        }
        return true
    }
}

@Composable
private fun Browser(state: PhotosState) {
    val folder = state.folder
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            T("PHOTOS", Cp.Cyan, 24.sp, TitleFont, FontWeight.Bold)
            T(folder.dir.path, Cp.Dim, 14.sp, MonoFont, FontWeight.Normal, Modifier.weight(1f), maxLines = 1)
            folder.dir.parentFile?.let { parent -> Button("▲ DOSSIER PARENT", Cp.Cyan) { state.open(parent) } }
            Button("IMAGES", Cp.Cyan) { state.open(Images.pictures(File(System.getProperty("user.home")))) }
            Button("OUVRIR UN DOSSIER…") {
                val chooser = JFileChooser(folder.dir).apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Ouvrir un dossier de photos"
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) state.open(chooser.selectedFile)
            }
            if (folder.images.isNotEmpty()) Button("DIAPORAMA ►", filled = true) { state.show(0); state.slideshow = true }
        }
        T(
            "${folder.images.size} photo" + (if (folder.images.size > 1) "s" else "") +
                if (folder.subfolders.isNotEmpty()) " · ${folder.subfolders.size} dossier" + (if (folder.subfolders.size > 1) "s" else "") else "",
            Cp.Dim, 13.sp, MonoFont, FontWeight.Normal,
        )
        if (state.message.isNotEmpty()) T(state.message, Cp.Yellow, 13.sp)
        if (folder.images.isEmpty() && folder.subfolders.isEmpty()) {
            T("Aucune photo dans ce dossier.", Cp.Dim, 16.sp)
        }
        LazyVerticalGrid(
            GridCells.Adaptive(170.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(folder.subfolders, key = { "d:" + it.path }) { dir ->
                Box(
                    Modifier.aspectRatio(1f).hudPanel(Cp.Yellow.copy(alpha = 0.6f), Cp.Panel, 8.dp).clickable { state.open(dir) }.padding(12.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    T("▸", Cp.Yellow, 40.sp, modifier = Modifier.align(Alignment.Center))
                    T(dir.name, Cp.Text, 14.sp, maxLines = 2)
                }
            }
            items(folder.images.size, key = { "f:" + folder.images[it].path }) { i ->
                Thumb(folder.images[i]) { state.show(i) }
            }
        }
    }
}

@Composable
private fun Thumb(file: File, onClick: () -> Unit) {
    val picture by produceState<Images.Picture?>(null, file) { value = withContext(Images.thumbnails) { Images.thumbnail(file) } }
    Box(
        Modifier.aspectRatio(1f).hudPanel(Cp.Cyan.copy(alpha = 0.35f), Cp.Panel, 6.dp).clickable(onClick = onClick).padding(3.dp).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val p = picture
        val image = p?.image
        when {
            p == null -> T("…", Cp.Dim, 18.sp)
            image == null -> T(file.extension.uppercase(), Cp.Dim, 18.sp, MonoFont, FontWeight.Normal)
            else -> Image(image, file.name, Modifier.fillMaxSize().rotate(p.rotation.toFloat()), contentScale = ContentScale.Crop)
        }
        T(
            file.name, Cp.Text, 12.sp, MonoFont, FontWeight.Normal, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Cp.Background.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Viewer(state: PhotosState, window: WindowState) {
    val file = state.current ?: return
    val index = state.viewing ?: 0
    val count = state.folder.images.size
    val picture by produceState<Images.Picture?>(null, file) { value = withContext(Dispatchers.IO) { Images.decode(file, 4096) } }
    LaunchedEffect(state.slideshow, file) {
        if (state.slideshow) {
            delay(4000)
            state.next()
        }
    }
    val full = window.placement == WindowPlacement.Fullscreen
    Column(Modifier.fillMaxSize()) {
        if (!full) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button("◄ GALERIE", Cp.Cyan) { state.close() }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    T(file.name, Cp.Text, 16.sp, maxLines = 1)
                    T("${index + 1} / $count · " + Images.describe(file, picture), Cp.Dim, 12.sp, MonoFont, FontWeight.Normal, maxLines = 1)
                }
                Button("◄") { state.previous() }
                Button("►") { state.next() }
                Button("↻ PIVOTER") { state.rotate() }
                Button("+") { state.zoomBy(1.25f) }
                Button("−") { state.zoomBy(0.8f) }
                Button(if (state.slideshow) "❚❚ PAUSE" else "DIAPORAMA", filled = state.slideshow) { state.slideshow = !state.slideshow }
                Button("PLEIN ÉCRAN") { window.placement = WindowPlacement.Fullscreen }
                Button(if (state.trashArmed) "CONFIRMER : CORBEILLE" else "CORBEILLE", Cp.Red, filled = state.trashArmed) { state.trash() }
            }
            if (state.message.isNotEmpty()) T(state.message, Cp.Yellow, 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black)
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f) state.zoomBy(if (dy < 0) 1.15f else 1 / 1.15f)
                }
                .pointerInput(file) { detectDragGestures { change, drag -> change.consume(); if (state.zoom > 1f) state.offset += drag } }
                .pointerInput(file) { detectTapGestures(onDoubleTap = { state.zoomBy(if (state.zoom > 1f) 0f else 2.5f) }) },
            contentAlignment = Alignment.Center,
        ) {
            val p = picture
            val image = p?.image
            when {
                p == null -> T("CHARGEMENT…", Cp.Dim, 16.sp, MonoFont, FontWeight.Normal)
                image == null -> T("Format non lu : ${file.name}", Cp.Red, 16.sp)
                else -> {
                    // Fitted by hand: a quarter turn swaps the sides ContentScale.Fit would measure.
                    val turn = (p.rotation + state.rotation) % 360
                    val (w, h) = if (turn % 180 == 0) image.width to image.height else image.height to image.width
                    val fit = minOf(constraints.maxWidth / w.toFloat(), constraints.maxHeight / h.toFloat(), 1f)
                    val density = LocalDensity.current
                    with(density) {
                        Image(
                            image, file.name,
                            Modifier.requiredSize((image.width * fit).toDp(), (image.height * fit).toDp()).graphicsLayer {
                                scaleX = state.zoom
                                scaleY = state.zoom
                                translationX = state.offset.x
                                translationY = state.offset.y
                                rotationZ = turn.toFloat()
                            },
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                }
            }
        }
    }
}
