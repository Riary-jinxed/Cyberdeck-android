package dev.netrunner.weather.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import dev.netrunner.weather.data.Place
import dev.netrunner.weather.data.WeatherRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The phone's position, saved cities, and a search to add more. Choosing one shows its forecast. */
@Composable
fun PlacesTab(state: WeatherState, onChosen: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        results = emptyList()
        if (query.trim().length < 2) return@LaunchedEffect
        delay(400) // Waits for the typing to settle.
        searching = true
        results = runCatching { WeatherRepo.search(query) }.getOrDefault(emptyList())
        searching = false
    }

    fun choose(place: Place?) {
        state.select(place)
        scope.launch { state.refresh() }
        onChosen()
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        TextField(
            query, { query = it }, singleLine = true, colors = hudFieldColors(),
            placeholder = { Text("Chercher une ville…", fontFamily = HudFont, color = Cp.Dim) },
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 17.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (query.isNotBlank()) {
                item { SectionTitle(if (searching) "RECHERCHE…" else "RÉSULTATS", Cp.Yellow) }
                if (!searching && results.isEmpty() && query.trim().length >= 2) item { Message("Aucune ville trouvée.") }
                items(results, key = { "r${it.lat},${it.lon}" }) { p ->
                    PlaceRow(p, selected = false, trailing = "+ GARDER", onTrailing = { state.save(p); query = "" }) { state.save(p); query = ""; choose(p) }
                }
            }
            item { SectionTitle("LIEUX", Cp.Yellow, Modifier.padding(top = 8.dp)) }
            item {
                PlaceRow(
                    Place("Position actuelle", "Localisation du téléphone", 0.0, 0.0, gps = true), selected = state.selected == null,
                ) { choose(null) }
            }
            items(state.saved, key = { "s${it.lat},${it.lon}" }) { p ->
                PlaceRow(p, selected = state.selected == p, trailing = "✕", onTrailing = { state.remove(p) }) { choose(p) }
            }
            item {
                Text(
                    "Les villes gardées restent disponibles ici. ✕ pour en retirer une.",
                    color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, selected: Boolean, trailing: String? = null, onTrailing: () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().hudPanel(borderColor = (if (selected) Cp.Yellow else Cp.Cyan).copy(alpha = if (selected) 0.9f else 0.3f), cut = 6.dp)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (place.gps) "◎" else "▸", color = if (selected) Cp.Yellow else Cp.Cyan, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, color = if (selected) Cp.Yellow else Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (place.detail.isNotBlank()) Text(place.detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Text(
                trailing, color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onTrailing).padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}
