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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.ui.Cp
import dev.netrunner.launcher.ui.HudFont
import dev.netrunner.launcher.ui.hudPanel
import dev.netrunner.launcher.ui.scanlines
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        // Header: brand, cursor keys, accent strip, keyboard switch.
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp),
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
                        onTap = { tap(); typeChar(a) },
                    )
                }
                Key("✕", Cp.Red, Modifier.weight(1f), onTap = { accents = null })
            } else {
                if (field.spellcheck && suggestions.word.isNotEmpty()) {
                    SuggestionStrip(suggestions, Modifier.weight(1f)) { pick ->
                        tap()
                        actions.pickSuggestion(pick)
                        if (shift == Shift.Once) shift = Shift.Off
                    }
                } else {
                    Text(
                        "◢ NETRUNNER//KB",
                        color = Cp.Yellow,
                        fontFamily = HudFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                }
                Key(
                    if (layer == Layer.Numpad) "ABC" else "123",
                    Cp.Cyan,
                    Modifier.weight(0.6f),
                    onTap = { tap(); layer = if (layer == Layer.Numpad) Layer.Letters else Layer.Numpad },
                )
                Key("◄", Cp.Cyan, Modifier.weight(0.5f), repeat = true, onTap = { actions.cursor(left = true) })
                Key("►", Cp.Cyan, Modifier.weight(0.5f), repeat = true, onTap = { actions.cursor(left = false) })
                Key("🌐", Cp.Dim, Modifier.weight(0.5f), onTap = { actions.switchKeyboard() })
                Key("▼", Cp.Dim, Modifier.weight(0.5f), onTap = { actions.hide() })
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
            Key("━━ ESPACE ━━", Cp.Dim, Modifier.weight(4f), onTap = { tap(); typeChar(' ') })
            CharKey('.', shift, Modifier.weight(1f), ::tap, ::typeChar) { accents = it }
            Key(field.enterLabel, Cp.Background, Modifier.weight(1.6f), filled = true, fill = Cp.Yellow, onTap = {
                tap()
                actions.enter()
                if (actions.shouldCapitalize()) shift = Shift.Once
            })
        }
    }
}

/** Spell checker strip: the typed word (red when it looks wrong) and up to three corrections. */
@Composable
private fun SuggestionStrip(s: Suggestions, modifier: Modifier, onPick: (String) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            s.word,
            color = if (s.typo) Cp.Red else Cp.Dim,
            fontFamily = HudFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        s.options.forEach { option ->
            val auto = option == s.autoFix
            Text(
                option,
                color = if (auto) Cp.Background else Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.6f), fill = if (auto) Cp.Yellow else Cp.Panel, cut = 5.dp)
                    .pointerInput(option) { detectTapGestures(onTap = { onPick(option) }) }
                    .padding(vertical = 5.dp),
            )
        }
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
    onLong: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentTap by rememberUpdatedState(onTap)
    val currentLong by rememberUpdatedState(onLong)
    val scope = rememberCoroutineScope()
    val background = when {
        pressed -> Cp.Yellow.copy(alpha = 0.85f)
        filled -> fill
        else -> Cp.Panel
    }
    val textColor = if (pressed) Cp.Background else color
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
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
