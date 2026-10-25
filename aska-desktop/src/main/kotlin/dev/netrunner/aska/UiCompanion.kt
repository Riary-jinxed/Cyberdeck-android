package dev.netrunner.aska

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Window.Type
import androidx.compose.ui.awt.ComposeWindow
import kotlin.math.abs
import kotlin.math.roundToInt

/** Her window: transparent, borderless, above everything, out of the taskbar. */
@Composable
fun Companion(aska: Aska, visible: Boolean, onSettings: () -> Unit) {
    val settings by aska.settings.collectAsState()
    val bubble by aska.bubble.collectAsState()
    val chatOpen by aska.chatOpen.collectAsState()

    val scale = settings.scale * if (settings.game) 0.7f else 1f
    val width = (BUBBLE_WIDTH * scale).roundToInt().coerceAtLeast(240)
    val spriteHeight = (SPRITE_HEIGHT * scale).roundToInt()
    val compact = spriteHeight + FOOTER
    val tall = compact + BUBBLE_HEIGHT
    val height = if (bubble != null || chatOpen) tall else compact

    Window(
        visible = visible,
        create = {
            ComposeWindow().apply {
                // A utility window has no taskbar button and stays out of Alt+Tab.
                type = Type.UTILITY
                isUndecorated = true
                // Without a compositor (some Linux desktops), she gets a dark backdrop instead.
                val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) isTransparent = true
                isAlwaysOnTop = true
                isResizable = false
                title = "As//ka"
                val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                val left = if (settings.x >= 0) settings.x else usable.x + usable.width - width - 24
                val bottom = if (settings.y >= 0) settings.y else usable.y + usable.height - 8
                setBounds(left, bottom - height, width, height)
            }
        },
        dispose = { it.dispose() },
    ) {
        // Grows and shrinks from the bottom: her feet stay where the runner put her.
        LaunchedEffect(width, height) {
            val bottom = window.y + window.height
            window.setBounds(window.x, bottom - height, width, height)
            window.isAlwaysOnTop = true
        }
        val input = remember { FocusRequester() }
        LaunchedEffect(chatOpen) {
            if (chatOpen) {
                window.toFront()
                window.requestFocus()
                delay(80)
                runCatching { input.requestFocus() }
            }
        }
        LaunchedEffect(Unit) {
            Activity.summons.drop(1).collect {
                if (aska.chatOpen.value) aska.closeChat() else aska.openChat()
            }
        }

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
            if (bubble != null || chatOpen) {
                Bubble(aska, bubble, chatOpen, input, Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
            Sprite(
                aska, settings.keyboard, spriteHeight,
                onMoved = { left, bottom -> aska.update { it.copy(x = left, y = bottom) } },
                onSettings = onSettings,
                window = window,
            )
        }
    }
}

@Composable
private fun Sprite(aska: Aska, keyboard: Boolean, height: Int, onMoved: (Int, Int) -> Unit, onSettings: () -> Unit, window: ComposeWindow) {
    val pose by aska.pose.collectAsState()
    val sprites = remember { Pose.entries.associateWith { sprite(it.sprite) } }
    val scope = rememberCoroutineScope()

    // She breathes.
    val breath by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 1.025f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
    )
    // Bongo Cat: while the runner types she sits at her keyboard, and on every key one of her
    // hands slaps it, left then right (three drawings that differ only by the hands).
    val bongo = remember { BONGO.associateWith { sprite(it) } }
    val taps by Activity.taps.collectAsState()
    // The count comes back from her last session: only new presses make her play.
    val typedFrom = remember { taps }
    var hand by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(taps) {
        if (!keyboard || taps == typedFrom) return@LaunchedEffect
        hand = if (taps % 2L == 0L) "bongo_left" else "bongo_right"
        delay(KEY_DOWN_MS)
        hand = null
    }
    val typing by produceState(false, taps) {
        value = taps > typedFrom
        delay(3_000)
        value = false
    }
    // In the middle of a conversation, her talking and thinking poses come first.
    val atKeyboard = keyboard && typing && pose != Pose.Talking && pose != Pose.Thinking

    var pendingClick by remember { mutableStateOf<Job?>(null) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.height(height.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (currentEvent.buttons.isSecondaryPressed) {
                        onSettings()
                        return@awaitEachGesture
                    }
                    val startMouse = MouseInfo.getPointerInfo().location
                    val startX = window.x
                    val startY = window.y
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val p2 = MouseInfo.getPointerInfo().location
                        val dx = p2.x - startMouse.x
                        val dy = p2.y - startMouse.y
                        if (!dragged && abs(dx) + abs(dy) > 4) dragged = true
                        if (dragged) {
                            window.setLocation(startX + dx, startY + dy)
                            change.consume()
                        }
                    }
                    if (dragged) {
                        onMoved(window.x, window.y + window.height)
                    } else if (pendingClick?.isActive == true) {
                        // Double click: she listens.
                        pendingClick?.cancel()
                        aska.openChat()
                    } else {
                        pendingClick = scope.launch {
                            delay(DOUBLE_CLICK_MS)
                            aska.poke()
                        }
                    }
                }
            },
        ) {
            Crossfade(if (atKeyboard) null else pose, animationSpec = tween(200)) { p ->
                Image(
                    if (p == null) bongo.getValue(hand ?: "bongo_idle") else sprites.getValue(p), contentDescription = "As//ka",
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomCenter,
                    modifier = Modifier.height(height.dp).graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        // At the keyboard she stays still, so only her hands move.
                        scaleY = if (p == null) 1f else breath
                    },
                )
            }
        }
        Box(Modifier.height(FOOTER.dp)) {
            if (keyboard && typing) T("⌨ $taps", Cp.Pink, 12.sp, MonoFont)
        }
    }
}

@Composable
private fun Bubble(aska: Aska, text: String?, chatOpen: Boolean, input: FocusRequester, modifier: Modifier) {
    val busy by aska.busy.collectAsState()
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }

    Column(modifier.hudPanel().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("AS//KA", Cp.Pink, 11.sp, TitleFont)
            Spacer(Modifier.weight(1f))
            T("✕", Cp.Dim, 13.sp, modifier = Modifier.clickable { aska.closeChat() }.padding(start = 8.dp))
        }
        Box(Modifier.heightIn(max = (if (chatOpen) BUBBLE_HEIGHT - 90 else BUBBLE_HEIGHT - 40).dp).verticalScroll(scroll).padding(top = 4.dp)) {
            BasicText(styled(text.orEmpty()), style = TextStyle(color = Cp.Text, fontSize = 15.sp, fontFamily = HudFont))
        }
        if (chatOpen) {
            fun send() {
                if (draft.isBlank()) return
                aska.send(draft)
                draft = ""
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(color = Cp.Text, fontSize = 15.sp, fontFamily = HudFont),
                cursorBrush = SolidColor(Cp.Pink),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).hudPanel(Cp.Dim, Cp.Background.copy(alpha = 0.9f), 6.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .focusRequester(input)
                    .onPreviewKeyEvent {
                        when {
                            it.type != KeyEventType.KeyDown -> false
                            it.key == Key.Enter && !it.isShiftPressed -> { send(); true }
                            it.key == Key.Escape -> { aska.closeChat(); true }
                            else -> false
                        }
                    },
                decorationBox = { field ->
                    Box {
                        if (draft.isEmpty()) T(if (busy) "Elle réfléchit…" else "Écris-lui… (/aide pour ses outils)", Cp.Dim, 14.sp)
                        field()
                    }
                },
            )
        }
    }
}

/** Her gestures, between asterisks, in pink italics. */
private fun styled(text: String) = buildAnnotatedString {
    text.split('*').forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(color = Cp.Pink.copy(alpha = 0.85f), fontStyle = FontStyle.Italic)) { append(part) }
        else append(part)
    }
}

private const val BUBBLE_WIDTH = 320f
private const val SPRITE_HEIGHT = 220f
private const val BUBBLE_HEIGHT = 260
private const val FOOTER = 18
private val BONGO = listOf("bongo_idle", "bongo_left", "bongo_right")
/** How long a hand stays on the key. */
private const val KEY_DOWN_MS = 110L
private const val DOUBLE_CLICK_MS = 300L
