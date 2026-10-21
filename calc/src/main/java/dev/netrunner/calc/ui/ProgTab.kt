package dev.netrunner.calc.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.calc.data.CalcPrefs
import dev.netrunner.calc.engine.ProgCalc
import dev.netrunner.calc.engine.ProgCalc.Base
import dev.netrunner.calc.engine.ProgCalc.Num
import dev.netrunner.calc.engine.ProgCalc.Token
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.hudShape
import dev.netrunner.deck.scanlines

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProgTab() {
    val context = LocalContext.current
    var tokens by remember { mutableStateOf<List<Token>>(emptyList()) }
    var fresh by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf(CalcPrefs.progBase(context)) }
    var mode by remember { mutableStateOf(CalcPrefs.progMode(context)) }

    fun edit(change: (List<Token>) -> List<Token>) {
        tokens = change(tokens)
        fresh = false
        error = null
    }

    fun digit(d: Int) {
        if (fresh) tokens = emptyList()
        edit { ProgCalc.typeDigit(it, d, base, mode) }
    }

    fun setMode(m: ProgCalc.Mode) {
        mode = m
        CalcPrefs.setProgMode(context, m)
        // Re-wraps what is on screen to the new word.
        tokens = tokens.map { if (it is Num) Num(m.wrap(it.value)) else it }
    }

    // The value on the four base lines and the bit grid: live result, else the number being typed.
    val value: Long = remember(tokens, mode) {
        runCatching { ProgCalc.eval(tokens, mode) }.getOrNull()
            ?: (tokens.lastOrNull { it is Num } as? Num)?.value ?: 0L
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Column(
            Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f)).scanlines(Cp.Background.copy(alpha = 0.18f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(mode.width.label) {
                    val all = ProgCalc.Width.entries
                    setMode(mode.copy(width = all[(all.indexOf(mode.width) + 1) % all.size]))
                }
                Spacer(Modifier.width(8.dp))
                Chip(if (mode.signed) "SIGNÉ" else "NON SIGNÉ") { setMode(mode.copy(signed = !mode.signed)) }
                Spacer(Modifier.weight(1f))
                Text(error ?: "", color = Cp.Red, fontFamily = HudFont, fontSize = 13.sp, maxLines = 1)
            }
            Text(
                ProgCalc.render(tokens, base, mode).ifEmpty { "0" }, color = if (fresh) Cp.Dim else Cp.Text, fontFamily = MonoFont,
                fontSize = 22.sp, maxLines = 2, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            Base.entries.forEach { b ->
                val on = b == base
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { base = b; CalcPrefs.setProgBase(context, b) },
                            onLongClick = { copy(context, ProgCalc.format(value, b, mode)) },
                        )
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        b.label, color = if (on) Cp.Background else Cp.Dim, fontFamily = HudFont, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp, modifier = Modifier.background(if (on) Cp.Yellow else Cp.Panel, hudShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Text(
                        ProgCalc.format(value, b, mode, group = true), color = if (on) Cp.Yellow else Cp.Text, fontFamily = MonoFont,
                        fontSize = if (b == Base.BIN && mode.width.bits > 16) 12.sp else 16.sp, textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BitGrid(value, mode) { bit ->
            if (fresh) fresh = false
            edit { ProgCalc.toggleBit(it, bit, mode) }
        }
        Spacer(Modifier.height(8.dp))

        val op = Cp.Yellow
        val logic = Cp.Cyan
        fun d(label: String, v: Int) = Key(label, Cp.Text, enabled = v < base.radix) { digit(v) }
        fun o(label: String, color: androidx.compose.ui.graphics.Color = op, size: Int = 16) =
            Key(label, color, size = size.sp) { edit { ProgCalc.typeOperator(it, label) } }
        Keypad(
            listOf(
                listOf(o("AND", logic), o("OR", logic), o("XOR", logic), Key("NOT", logic, size = 16.sp) { edit(ProgCalc::typeNot) }, o("<<", logic), o(">>", logic)),
                listOf(d("A", 10), d("B", 11), Key("(", op) { edit(ProgCalc::typeOpen) }, Key(")", op) { edit(ProgCalc::typeClose) }, o("MOD"),
                    Key("AC", Cp.Red, size = 16.sp) { tokens = emptyList(); fresh = false; error = null }),
                listOf(d("C", 12), d("D", 13), d("7", 7), d("8", 8), d("9", 9), o("÷", size = 22)),
                listOf(d("E", 14), d("F", 15), d("4", 4), d("5", 5), d("6", 6), o("×", size = 22)),
                listOf(o("NAND", logic, 14), o("NOR", logic, 14), d("1", 1), d("2", 2), d("3", 3), o("−", size = 22)),
                listOf(
                    Key("±", op) {
                        edit { t ->
                            val last = t.lastOrNull()
                            if (last is Num) t.dropLast(1) + Num(mode.wrap(-last.value)) else ProgCalc.typeOperator(t, "−")
                        }
                    },
                    Key("⌫", Cp.Red, onLongClick = { tokens = emptyList(); fresh = false; error = null }) {
                        if (fresh) tokens = emptyList() else edit { ProgCalc.backspace(it, base, mode) }
                    },
                    d("0", 0),
                    Key("=", Cp.Yellow, filled = true, weight = 2f, size = 22.sp) {
                        if (tokens.isNotEmpty()) {
                            runCatching { ProgCalc.eval(tokens, mode) }
                                .onSuccess { tokens = listOf(Num(it)); fresh = true; error = null }
                                .onFailure { error = it.message }
                        }
                    },
                    o("+", size = 22),
                ),
            ),
            Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Text(
        text, color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp,
        modifier = Modifier.hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 5.dp).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The word's bits, 16 per row, high bits first. Tap a bit to flip it. */
@Composable
private fun BitGrid(value: Long, mode: ProgCalc.Mode, onToggle: (Int) -> Unit) {
    val bits = mode.width.bits
    val perRow = minOf(16, bits)
    Column(
        Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (row in 0 until bits / perRow) {
            val high = bits - 1 - row * perRow
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%02d".format(high), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.width(22.dp))
                for (k in 0 until perRow) {
                    val bit = high - k
                    val on = (value ushr bit) and 1L == 1L
                    Box(
                        Modifier.weight(1f).clickable { onToggle(bit) }.padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (on) "1" else "0", color = if (on) Cp.Yellow else Cp.Dim, fontFamily = MonoFont, fontSize = 15.sp)
                    }
                    // A gap between nibbles.
                    if (k % 4 == 3 && k != perRow - 1) Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}
