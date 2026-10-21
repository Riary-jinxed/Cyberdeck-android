package dev.netrunner.scanner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.scanner.data.Codes
import dev.netrunner.scanner.data.History
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Past scans; tap to open the card again, long press to forget one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryTab(scans: List<History.Scan>, onChange: (List<History.Scan>) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<History.Scan?>(null) }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${scans.size} SCAN${if (scans.size > 1) "S" else ""}", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f),
            )
            if (scans.isNotEmpty()) Chip("TOUT EFFACER", Cp.Red) { open = null; onChange(History.clear(context)) }
        }
        if (scans.isEmpty()) {
            Message("Rien pour l'instant : les codes scannés s'affichent ici.")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(scans, key = { it.raw + it.time }) { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (open == s) {
                            ResultCard(s, Modifier.fillMaxWidth(), onClose = null)
                        }
                        val c = Codes.parse(s.raw)
                        Row(
                            Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = if (open == s) 0.8f else 0.3f), cut = 6.dp)
                                .combinedClickable(
                                    onClick = { open = if (open == s) null else s },
                                    onLongClick = { if (open == s) open = null; onChange(History.remove(context, s)) },
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.label, color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                                Text(
                                    describe(c).first().second, color = Cp.Text, fontFamily = MonoFont, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(dateFormat.format(Date(s.time)), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
