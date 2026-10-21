package dev.netrunner.calc.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.calc.data.CalcPrefs
import dev.netrunner.calc.data.Rates
import dev.netrunner.calc.engine.SciCalc
import dev.netrunner.calc.engine.Units
import dev.netrunner.deck.Cp
import dev.netrunner.deck.DisplayFont
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import kotlinx.coroutines.launch

private const val CURRENCY = "DEVISES"

/** One row of the conversion list: a unit or a currency, and the input converted into it. */
private data class Line(val symbol: String, val name: String, val value: Double?)

/** Converts a value (or a small calculation) into every unit of a category, or into every currency. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConvertTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val categories = remember { listOf(CURRENCY) + Units.categories.map { it.name } }
    var category by rememberSaveable { mutableIntStateOf(CalcPrefs.convertCategory(context).coerceIn(0, categories.size - 1)) }
    var input by rememberSaveable { mutableStateOf("1") }
    var source by rememberSaveable(category) { mutableStateOf(CalcPrefs.convertSource(context, categories[category])) }
    var table by remember { mutableStateOf(Rates.cached(context)) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            val fresh = Rates.refresh(context)
            if (fresh != null) table = fresh
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val value = remember(input) { runCatching { SciCalc.eval(input) }.getOrNull() }
    val name = categories[category]
    val rows: List<Line> = if (name == CURRENCY) {
        val rates = table?.rates.orEmpty()
        val from = source?.takeIf { it in rates } ?: "EUR"
        Rates.ordered(rates).map { code ->
            Line(code, Rates.names[code] ?: code, value?.let { Units.convertCurrency(it, from, code, rates) })
        }
    } else {
        val units = Units.categories.first { it.name == name }.units
        val from = units.firstOrNull { it.symbol == source } ?: units.first()
        units.map { Line(it.symbol, it.name, value?.let { v -> Units.convert(v, from, it) }) }
    }
    val selected = source?.takeIf { s -> rows.any { it.symbol == s } } ?: rows.firstOrNull()?.symbol

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEachIndexed { i, c ->
                val on = i == category
                Text(
                    c, color = if (on) Cp.Background else Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), fill = if (on) Cp.Cyan else Cp.Panel.copy(alpha = 0.85f), cut = 5.dp)
                        .clickable { category = i; CalcPrefs.setConvertCategory(context, i) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                input, { input = it.take(40) }, singleLine = true, colors = hudFieldColors(),
                label = { Text("Valeur en ${selected ?: ""} (calcul possible)", fontFamily = HudFont) },
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 20.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = value == null && input.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            HudButton("±", size = 18.sp) {
                input = if (input.trimStart().startsWith("-")) input.trimStart().removePrefix("-") else "-$input"
            }
        }
        if (name == CURRENCY) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        loading -> "Mise à jour des taux…"
                        table == null -> "Taux indisponibles : connexion nécessaire."
                        else -> "Taux BCE du ${frenchDate(table!!.date)}"
                    },
                    color = if (table == null && !loading) Cp.Red else Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                HudButton("ACTUALISER", color = Cp.Cyan, size = 12.sp) { refresh() }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(rows, key = { it.symbol }) { row ->
                val on = row.symbol == selected
                val text = row.value?.let { SciCalc.format(it) } ?: "—"
                Row(
                    Modifier.fillMaxWidth()
                        .hudPanel(borderColor = (if (on) Cp.Yellow else Cp.Cyan).copy(alpha = if (on) 0.9f else 0.3f), cut = 6.dp)
                        .combinedClickable(
                            onClick = {
                                // The quantity stays the same: the input becomes its value in the new unit.
                                if (!on && row.value != null) input = text
                                source = row.symbol
                                CalcPrefs.setConvertSource(context, name, row.symbol)
                            },
                            onLongClick = { if (row.value != null) copy(context, text) },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(0.45f)) {
                        Text(row.symbol, color = if (on) Cp.Yellow else Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(row.name, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        text, color = if (on) Cp.Yellow else Cp.Text, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        Text(
            "Touche une ligne pour en faire l'unité de départ, appui long pour copier.",
            color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** "2026-10-21" → "21/10/2026". */
private fun frenchDate(iso: String): String = iso.split('-').takeIf { it.size == 3 }?.reversed()?.joinToString("/") ?: iso
