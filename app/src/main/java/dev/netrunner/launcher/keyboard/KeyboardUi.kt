package dev.netrunner.launcher.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.ui.Cp
import dev.netrunner.launcher.ui.HudFont
import dev.netrunner.launcher.ui.hudPanel
import dev.netrunner.launcher.ui.hudShape
import dev.netrunner.launcher.ui.scanlines
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class Layer { Letters, Symbols, More, Numpad }
private enum class Shift { Off, Once, Locked }

private val LetterRows = listOf("azertyuiop", "qsdfghjklm", "wxcvbn'")
private val SymbolRows = listOf("1234567890", "@#€&-_()\"/", "!?:;,*%")
private val MoreRows = listOf("~`|•√π÷×¶∆", "£¥\$¢^°={}\\", "[]<>+©®")

/** Long press on these letters shows their accented forms. */
private val Accents = mapOf(
    'e' to "éèêë€", 'a' to "àâæáä", 'u' to "ùûüú", 'i' to "îïíì", 'o' to "ôöœóò",
    'c' to "ç©", 'y' to "ÿý", 'n' to "ñ", 's' to "ß$", '\'' to "’‘\"«»",
)

@Composable
fun KeyboardUi(field: FieldInfo, suggestions: Suggestions, actions: KeyboardActions) {
    var layer by remember { mutableStateOf(Layer.Letters) }
    var shift by remember { mutableStateOf(Shift.Off) }
    var lastShiftTap by remember { mutableStateOf(0L) }
    var accents by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current

    LaunchedEffect(field) {
        layer = if (field.numeric) Layer.Numpad else Layer.Letters
        shift = if (field.capsStart) Shift.Once else Shift.Off
        accents = null
    }

    fun tap() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    fun typeChar(c: Char) {
        val text = if (shift != Shift.Off && c.isLetter()) c.uppercase() else c.toString()
        actions.type(text)
        accents = null
        if (shift == Shift.Once) shift = Shift.Off
        // After ". " or a new line, start the next sentence with a capital.
        if ((c == ' ' || c == '\n') && shift == Shift.Off && actions.shouldCapitalize()) shift = Shift.Once
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Cp.Background)
            .scanlines()
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Suggestion bar: full width above everything, always there so the keys never jump.
        SuggestionBar(
            suggestions = if (field.spellcheck) suggestions else Suggestions(),
            onPick = { pick ->
                tap()
                actions.pickSuggestion(pick)
                if (shift == Shift.Once) shift = Shift.Off
            },
            onTool = { id -> tap(); actions.contextAction(id) },
        )

        // Controls: brand (or the accents of a long-pressed letter), cursor keys, switch, hide.
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val pending = accents
            if (pending != null) {
                pending.forEach { a ->
                    Key(
                        label = if (shift != Shift.Off) a.uppercase() else a.toString(),
                        color = Cp.Yellow,
                        modifier = Modifier.weight(1f),
                        height = 32.dp,
                        onTap = { tap(); typeChar(a) },
                    )
                }
                Key("✕", Cp.Red, Modifier.weight(1f), height = 32.dp, onTap = { accents = null })
            } else {
                Text(
                    "◢ ${Cp.faction.label}//KB",
                    color = Cp.Yellow,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                Key(
                    if (layer == Layer.Numpad) "ABC" else "123",
                    Cp.Cyan,
                    Modifier.weight(0.6f),
                    height = 32.dp,
                    onTap = { tap(); layer = if (layer == Layer.Numpad) Layer.Letters else Layer.Numpad },
                )
                Key("◄", Cp.Cyan, Modifier.weight(0.5f), height = 32.dp, repeat = true, onTap = { actions.cursor(left = true) })
                Key("►", Cp.Cyan, Modifier.weight(0.5f), height = 32.dp, repeat = true, onTap = { actions.cursor(left = false) })
                Key("🌐", Cp.Dim, Modifier.weight(0.5f), height = 32.dp, onTap = { actions.switchKeyboard() })
                Key("▼", Cp.Dim, Modifier.weight(0.5f), height = 32.dp, onTap = { actions.hide() })
            }
        }

        if (layer == Layer.Numpad) {
            Numpad(field, ::tap, ::typeChar, actions, onLetters = { layer = Layer.Letters })
            return@Column
        }
        val rows = when (layer) {
            Layer.Letters -> LetterRows
            Layer.Symbols -> SymbolRows
            else -> MoreRows
        }
        KeyRow { rows[0].forEach { c -> CharKey(c, shift, Modifier.weight(1f), ::tap, ::typeChar) { accents = it } } }
        KeyRow {
            rows[1].forEach { c -> CharKey(c, shift, Modifier.weight(1f), ::tap, ::typeChar) { accents = it } }
        }
        KeyRow {
            when (layer) {
                Layer.Letters -> Key(
                    label = when (shift) { Shift.Off -> "⇧"; Shift.Once -> "⬆"; Shift.Locked -> "⇪" },
                    color = if (shift == Shift.Off) Cp.Cyan else Cp.Yellow,
                    filled = shift == Shift.Locked,
                    modifier = Modifier.weight(1.5f),
                    onTap = {
                        tap()
                        val now = System.currentTimeMillis()
                        shift = when {
                            shift == Shift.Once && now - lastShiftTap < 350 -> Shift.Locked
                            shift == Shift.Off -> Shift.Once
                            else -> Shift.Off
                        }
                        lastShiftTap = now
                    },
                )
                Layer.Symbols -> Key("=\\<", Cp.Cyan, Modifier.weight(1.5f), onTap = { tap(); layer = Layer.More })
                else -> Key("?123", Cp.Cyan, Modifier.weight(1.5f), onTap = { tap(); layer = Layer.Symbols })
            }
            rows[2].forEach { c -> CharKey(c, shift, Modifier.weight(1f), ::tap, ::typeChar) { accents = it } }
            Key("⌫", Cp.Red, Modifier.weight(1.5f), repeat = true, onTap = { tap(); actions.backspace() })
        }
        KeyRow {
            Key(
                if (layer == Layer.Letters) "?123" else "ABC",
                Cp.Cyan,
                Modifier.weight(1.4f),
                onTap = { tap(); layer = if (layer == Layer.Letters) Layer.Symbols else Layer.Letters },
            )
            // Email and web address fields get "@" or "/" where the comma usually sits.
            val side = when (field.kind) {
                FieldKind.Email -> '@'
                FieldKind.Url -> '/'
                else -> ','
            }
            CharKey(side, shift, Modifier.weight(1f), ::tap, ::typeChar) { accents = it }
            SpaceKey(
                Modifier.weight(4f),
                onSpace = { tap(); typeChar(' ') },
                onSlide = { left -> tap(); actions.cursor(left) },
            )
            CharKey('.', shift, Modifier.weight(1f), ::tap, ::typeChar) { accents = it }
            Key(field.enterLabel, Cp.Background, Modifier.weight(1.6f), filled = true, fill = Cp.Yellow, onTap = {
                tap()
                actions.enter()
                if (actions.shouldCapitalize()) shift = Shift.Once
            })
        }
    }
}

/**
 * Suggestion bar: three equal slots, the typed word on the left (tap to keep it as is), the
 * best correction in the middle (filled when it will replace the word on the next space) and
 * the runner-up on the right. Long words shrink, then end with "…", never cut at the start.
 * With nothing to suggest it holds paste, select all and copy.
 */
@Composable
private fun SuggestionBar(suggestions: Suggestions, onPick: (String) -> Unit, onTool: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.45f), fill = Cp.Panel, cut = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val word = suggestions.word
        if (word.isEmpty()) {
            Slot("⎘ COLLER", Cp.Cyan) { onTool(android.R.id.paste) }
            Divider()
            Slot("▣ TOUT SÉLECT.", Cp.Dim) { onTool(android.R.id.selectAll) }
            Divider()
            Slot("⧉ COPIER", Cp.Dim) { onTool(android.R.id.copy) }
            return@Row
        }
        val best = suggestions.options.getOrNull(0)
        val second = suggestions.options.getOrNull(1)
        val typed = "«$word»"
        val typedColor = if (suggestions.typo) Cp.Red else Cp.Dim
        if (best == null) {
            Slot("", Cp.Dim, onTap = null)
            Divider()
            Slot(typed, typedColor) { onPick(word) }
            Divider()
            Slot("", Cp.Dim, onTap = null)
        } else {
            Slot(typed, typedColor) { onPick(word) }
            Divider()
            Slot(best, Cp.Yellow, filled = best == suggestions.autoFix) { onPick(best) }
            Divider()
            Slot(second.orEmpty(), Cp.Yellow, onTap = second?.let { s -> { onPick(s) } })
        }
    }
}

@Composable
private fun RowScope.Slot(text: String, color: Color, filled: Boolean = false, onTap: (() -> Unit)?) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(3.dp)
            .then(if (filled) Modifier.background(color, hudShape(6.dp)) else Modifier)
            .then(if (onTap != null) Modifier.pointerInput(text) { detectTapGestures(onTap = { onTap() }) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) Cp.Background else color,
            fontFamily = HudFont,
            fontSize = when {
                text.length > 14 -> 12.sp
                text.length > 10 -> 14.sp
                else -> 16.sp
            },
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .width(1.dp)
            .height(22.dp)
            .background(Cp.Cyan.copy(alpha = 0.25f))
    )
}

/**
 * Space bar that also moves the cursor: slide a finger along it and the cursor follows,
 * one character per step, without lifting.
 */
@Composable
private fun SpaceKey(modifier: Modifier, onSpace: () -> Unit, onSlide: (left: Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    var sliding by remember { mutableStateOf(false) }
    val space by rememberUpdatedState(onSpace)
    val slide by rememberUpdatedState(onSlide)
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .hudPanel(
                borderColor = if (pressed) Cp.Yellow else Cp.Cyan.copy(alpha = 0.35f),
                fill = if (pressed && !sliding) Cp.Yellow.copy(alpha = 0.85f) else Cp.Panel,
                cut = 6.dp,
            )
            .pointerInput(Unit) {
                val step = 12.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    var anchor = down.position.x
                    var slid = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val x = change.position.x
                        if (!slid && abs(x - down.position.x) > viewConfiguration.touchSlop) {
                            slid = true
                            sliding = true
                            anchor = x
                        }
                        if (slid) {
                            while (x - anchor >= step) { slide(false); anchor += step }
                            while (anchor - x >= step) { slide(true); anchor -= step }
                            change.consume()
                        }
                    }
                    if (!slid) space()
                    pressed = false
                    sliding = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (sliding) "◄ CURSEUR ►" else "━━ ESPACE ━━",
            color = if (pressed && !sliding) Cp.Background else if (sliding) Cp.Yellow else Cp.Dim,
            fontFamily = HudFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Phone-style digit pad for number and phone fields (and on demand with the 123 key). */
@Composable
private fun Numpad(field: FieldInfo, tap: () -> Unit, type: (Char) -> Unit, actions: KeyboardActions, onLetters: () -> Unit) {
    val sides = if (field.kind == FieldKind.Phone) listOf('+', '*', '#') else listOf('-', ',', '.')
    listOf("123", "456", "789").forEachIndexed { i, row ->
        KeyRow {
            row.forEach { c -> Key(c.toString(), Cp.Text, Modifier.weight(1f), onTap = { tap(); type(c) }) }
            if (i < 2) Key(sides[i].toString(), Cp.Cyan, Modifier.weight(0.8f), onTap = { tap(); type(sides[i]) })
            else Key("⌫", Cp.Red, Modifier.weight(0.8f), repeat = true, onTap = { tap(); actions.backspace() })
        }
    }
    KeyRow {
        Key("ABC", Cp.Cyan, Modifier.weight(1f), onTap = { tap(); onLetters() })
        Key("0", Cp.Text, Modifier.weight(1f), onTap = { tap(); type('0') })
        Key(sides[2].toString(), Cp.Cyan, Modifier.weight(1f), onTap = { tap(); type(sides[2]) })
        Key(field.enterLabel, Cp.Background, Modifier.weight(0.8f), filled = true, fill = Cp.Yellow, onTap = { tap(); actions.enter() })
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun CharKey(
    c: Char,
    shift: Shift,
    modifier: Modifier,
    tap: () -> Unit,
    type: (Char) -> Unit,
    showAccents: (String) -> Unit,
) {
    val label = if (shift != Shift.Off && c.isLetter()) c.uppercase() else c.toString()
    Key(
        label = label,
        color = Cp.Text,
        modifier = modifier,
        preview = true,
        onTap = { tap(); type(c) },
        onLong = Accents[c]?.let { variants -> { showAccents(variants) } },
    )
}

/**
 * One cut-corner key. Lights up yellow while pressed; [repeat] keys fire again while held,
 * [onLong] replaces the tap on a long press.
 */
@Composable
private fun Key(
    label: String,
    color: Color,
    modifier: Modifier,
    filled: Boolean = false,
    fill: Color = Cp.Cyan,
    repeat: Boolean = false,
    height: Dp = 46.dp,
    preview: Boolean = false,
    onLong: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val currentTap by rememberUpdatedState(onTap)
    val currentLong by rememberUpdatedState(onLong)
    val scope = rememberCoroutineScope()
    val background = when {
        pressed -> Cp.Yellow.copy(alpha = 0.85f)
        filled -> fill
        else -> Cp.Panel
    }
    val textColor = if (pressed) Cp.Background else color
    val bubbleFill = Cp.Panel
    val bubbleText = Cp.Yellow
    Box(
        modifier
            .zIndex(if (pressed) 1f else 0f)
            .fillMaxWidth()
            .height(height)
            // Letters show an enlarged copy above the finger while pressed, as the key itself is hidden under it.
            .drawWithContent {
                drawContent()
                if (preview && pressed) {
                    val bw = size.width + 14.dp.toPx()
                    val bh = size.height * 1.1f
                    val topLeft = Offset((size.width - bw) / 2, -bh - 6.dp.toPx())
                    val radius = CornerRadius(6.dp.toPx())
                    drawRoundRect(bubbleFill, topLeft, Size(bw, bh), radius)
                    drawRoundRect(bubbleText, topLeft, Size(bw, bh), radius, style = Stroke(2.dp.toPx()))
                    val layout = measurer.measure(
                        label,
                        TextStyle(color = bubbleText, fontFamily = HudFont, fontSize = 30.sp, fontWeight = FontWeight.Bold),
                    )
                    drawText(
                        layout,
                        topLeft = Offset(
                            topLeft.x + (bw - layout.size.width) / 2,
                            topLeft.y + (bh - layout.size.height) / 2,
                        ),
                    )
                }
            }
            .hudPanel(borderColor = if (pressed) Cp.Yellow else Cp.Cyan.copy(alpha = 0.35f), fill = background, cut = 6.dp)
            .pointerInput(repeat) {
                if (repeat) {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        val job = scope.launch {
                            currentTap()
                            delay(400)
                            while (true) {
                                currentTap()
                                delay(55)
                            }
                        }
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        job.cancel()
                        pressed = false
                    }
                } else {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onLongPress = { currentLong?.invoke() ?: currentTap() },
                        onTap = { currentTap() },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontFamily = HudFont,
            fontSize = if (label.length > 3) 12.sp else 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
