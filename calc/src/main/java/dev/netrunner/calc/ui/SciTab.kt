package dev.netrunner.calc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.calc.data.CalcPrefs
import dev.netrunner.calc.engine.SciCalc
import dev.netrunner.calc.engine.SciInput
import dev.netrunner.deck.Cp
import dev.netrunner.deck.DisplayFont
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.scanlines

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SciTab() {
    val context = LocalContext.current
    var expression by rememberSaveable { mutableStateOf("") }
    // True right after "=": the next digit starts a new calculation.
    var fresh by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var ans by rememberSaveable { mutableStateOf(0.0) }
    var degrees by remember { mutableStateOf(CalcPrefs.degrees(context)) }
    var second by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(CalcPrefs.history(context)) }

    fun type(token: String) {
        expression = SciInput.append(expression, token, fresh)
        fresh = false
        error = null
        second = false
    }

    fun evaluate() {
        if (expression.isEmpty()) return
        runCatching { SciCalc.eval(expression, degrees, ans) }
            .onSuccess { v ->
                val result = SciCalc.format(v)
                if (result != expression) history = CalcPrefs.addHistory(context, CalcPrefs.Entry(SciInput.closed(expression), result))
                ans = v
                expression = result
                fresh = true
                error = null
            }
            .onFailure { error = it.message ?: "erreur" }
    }

    // Live result while typing, hidden when it would only repeat the expression.
    val preview = remember(expression, degrees, ans) {
        if (fresh || expression.isEmpty()) null
        else runCatching { SciCalc.format(SciCalc.eval(expression, degrees, ans)) }.getOrNull()?.takeIf { it != expression }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Display
        Column(
            Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f)).scanlines(Cp.Background.copy(alpha = 0.18f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (degrees) "DEG" else "RAD", color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    letterSpacing = 2.sp, modifier = Modifier.clickable { degrees = !degrees; CalcPrefs.setDegrees(context, degrees) },
                )
                if (second) Text("  2ND", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (showHistory) "◂ CALCUL" else "HISTORIQUE ▸", color = Cp.Dim, fontFamily = HudFont, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, letterSpacing = 1.sp, modifier = Modifier.clickable { showHistory = !showHistory },
                )
            }
            val scroll = rememberScrollState()
            LaunchedEffect(expression) { scroll.scrollTo(scroll.maxValue) }
            Text(
                expression.ifEmpty { "0" }, color = if (fresh) Cp.Dim else Cp.Text, fontFamily = MonoFont,
                fontSize = when { expression.length > 22 -> 22.sp; expression.length > 14 -> 28.sp; else -> 34.sp },
                maxLines = 1, softWrap = false, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(top = 8.dp),
            )
            val shown = error ?: preview?.let { "= $it" } ?: if (fresh) "= $expression" else ""
            Text(
                shown, color = if (error != null) Cp.Red else Cp.Yellow,
                fontFamily = if (error != null) HudFont else DisplayFont, fontWeight = FontWeight.Bold,
                fontSize = if (error != null) 16.sp else 22.sp, maxLines = 1, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().height(32.dp).padding(top = 4.dp)
                    .combinedClickable(onClick = {}, onLongClick = {
                        val value = preview ?: if (fresh) expression else null
                        if (value != null) copy(context, value)
                    }),
            )
        }
        Spacer(Modifier.height(10.dp))

        if (showHistory) {
            History(
                history,
                onPick = { entry ->
                    // Inserts the value after an operator, replaces the expression otherwise.
                    expression = if (!fresh && expression.isNotEmpty() && expression.last() in "+−×÷^(") expression + entry.result else entry.result
                    fresh = false
                    error = null
                    showHistory = false
                },
                onClear = { CalcPrefs.clearHistory(context); history = emptyList() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            SciKeys(
                second, degrees,
                onSecond = { second = !second },
                onDegrees = { degrees = !degrees; CalcPrefs.setDegrees(context, degrees) },
                type = ::type,
                onClear = { expression = ""; fresh = false; error = null },
                onBack = { expression = if (fresh) "" else SciInput.backspace(expression); fresh = false; error = null },
                onEquals = ::evaluate,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SciKeys(
    s: Boolean,
    degrees: Boolean,
    onSecond: () -> Unit,
    onDegrees: () -> Unit,
    type: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onEquals: () -> Unit,
    modifier: Modifier,
) {
    val fn = Cp.Cyan
    val num = Cp.Text
    val op = Cp.Yellow
    Keypad(
        listOf(
            listOf(
                Key("2ND", Cp.Yellow, filled = s, size = 16.sp, onClick = onSecond),
                Key(if (degrees) "DEG" else "RAD", fn, size = 16.sp, onClick = onDegrees),
                Key("(", op) { type("(") },
                Key(")", op) { type(")") },
                Key("AC", Cp.Red, size = 18.sp, onClick = onClear),
            ),
            listOf(
                Key(if (s) "sin⁻¹" else "sin", fn, size = 17.sp) { type(if (s) "asin(" else "sin(") },
                Key(if (s) "cos⁻¹" else "cos", fn, size = 17.sp) { type(if (s) "acos(" else "cos(") },
                Key(if (s) "tan⁻¹" else "tan", fn, size = 17.sp) { type(if (s) "atan(" else "tan(") },
                Key(if (s) "eˣ" else "ln", fn, size = 17.sp) { type(if (s) "exp(" else "ln(") },
                Key(if (s) "10ˣ" else "log", fn, size = 17.sp) { if (s) type("10^") else type("log(") },
            ),
            listOf(
                Key(if (s) "x³" else "x²", fn, size = 17.sp) { type(if (s) "³" else "²") },
                Key(if (s) "∛" else "√", fn) { type(if (s) "∛(" else "√(") },
                Key(if (s) "EE" else "xʸ", fn, size = 17.sp) { type(if (s) "×10^" else "^") },
                Key(if (s) "|x|" else "x!", fn, size = 17.sp) { type(if (s) "abs(" else "!") },
                Key(if (s) "mod" else "π", fn, size = 17.sp) { type(if (s) " mod " else "π") },
            ),
            listOf(
                Key("7", num) { type("7") }, Key("8", num) { type("8") }, Key("9", num) { type("9") },
                Key("÷", op, size = 24.sp) { type("÷") },
                Key("⌫", Cp.Red, onLongClick = onClear, onClick = onBack),
            ),
            listOf(
                Key("4", num) { type("4") }, Key("5", num) { type("5") }, Key("6", num) { type("6") },
                Key("×", op, size = 24.sp) { type("×") },
                Key("%", fn) { type("%") },
            ),
            listOf(
                Key("1", num) { type("1") }, Key("2", num) { type("2") }, Key("3", num) { type("3") },
                Key("−", op, size = 24.sp) { type("−") },
                Key("e", fn) { type("e") },
            ),
            listOf(
                Key("ANS", fn, size = 16.sp) { type("ans") },
                Key("0", num) { type("0") },
                Key(",", num) { type(",") },
                Key("+", op, size = 24.sp) { type("+") },
                Key("=", Cp.Yellow, filled = true, size = 24.sp, onClick = onEquals),
            ),
        ),
        modifier,
    )
}

@Composable
private fun History(entries: List<CalcPrefs.Entry>, onPick: (CalcPrefs.Entry) -> Unit, onClear: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("HISTORIQUE", Cp.Yellow, Modifier.weight(1f))
            if (entries.isNotEmpty()) HudButton("EFFACER", color = Cp.Red, size = 13.sp, onClick = onClear)
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text("Aucun calcul pour l'instant.", color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp)
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries) { e ->
                Column(
                    Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.35f), cut = 6.dp)
                        .clickable { onPick(e) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(e.expression, color = Cp.Dim, fontFamily = MonoFont, fontSize = 15.sp, textAlign = TextAlign.End)
                    Text("= ${e.result}", color = Cp.Yellow, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }
    }
}

fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("calcul", text))
    Toast.makeText(context, "Copié : $text", Toast.LENGTH_SHORT).show()
}
