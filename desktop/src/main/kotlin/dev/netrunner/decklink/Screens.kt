package dev.netrunner.decklink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

// ---------------------------------------------------------------------------------- shared bits

@Composable
private fun Button(label: String, color: Color = Cp.Yellow, filled: Boolean = false, onClick: () -> Unit) {
    T(
        label, if (filled) Cp.Background else color, 14.sp, weight = FontWeight.Bold,
        modifier = Modifier
            .hudPanel(color, if (filled) color else color.copy(alpha = 0.08f), 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun Section(title: String) = T(title, Cp.Yellow, 13.sp, weight = FontWeight.Bold)

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    Box(Modifier.fillMaxWidth().hudPanel(Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 12.dp, vertical = 9.dp)) {
        if (value.isEmpty()) T(hint, Cp.Dim, 15.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(Cp.Yellow),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EmptyKit() = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    T("> rien reçu pour l'instant", Cp.Cyan, 15.sp, MonoFont, FontWeight.Normal)
    T("Appaire le téléphone dans LIAISON, puis lance une synchro depuis le Kit (Outils › PC).", Cp.Dim, 13.sp, MonoFont, FontWeight.Normal)
}

/** A column of a [Table]: header, relative width, and the cell text of a row. */
private class Col<R>(val title: String, val weight: Float, val color: (R) -> Color = { Cp.Text }, val cell: (R) -> String)

@Composable
private fun <R> Table(rows: List<R>, cols: List<Col<R>>, onClick: ((R) -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            cols.forEach { T(it.title.uppercase(), Cp.Dim, 12.sp, weight = FontWeight.Bold, modifier = Modifier.weight(it.weight), maxLines = 1) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows) { r ->
                Row(
                    Modifier.fillMaxWidth()
                        .hudPanel(Cp.Cyan.copy(alpha = 0.3f), cut = 4.dp)
                        .let { m -> if (onClick != null) m.clickable { onClick(r) } else m }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    cols.forEach { c -> T(c.cell(r), c.color(r), 14.sp, modifier = Modifier.weight(c.weight).padding(end = 8.dp), maxLines = 2) }
                }
            }
        }
    }
}

private fun matches(query: String, vararg fields: String): Boolean =
    query.isBlank() || query.trim().split(Regex("\\s+")).all { word -> fields.any { it.contains(word, ignoreCase = true) } }

// ---------------------------------------------------------------------------------- link

@Composable
private fun Qr(text: String, size: Dp) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    // Black on white whatever the theme: phone scanners read that best.
    Canvas(Modifier.size(size).background(Color.White)) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
fun LinkScreen(state: LinkState) {
    var renewArmed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.fatal?.let { T(it, Cp.Red, 15.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.hudPanel(Cp.Yellow).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Qr(state.offer(), 240.dp)
                Spacer(Modifier.height(10.dp))
                T(state.code.text, Cp.Yellow, 16.sp, MonoFont, FontWeight.Normal)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("APPAIRER LE TÉLÉPHONE")
                T(
                    "1. Sur le téléphone : KIT › OUTILS › PC › SCANNER LE QR DU PC.\n" +
                        "2. Scanne ce code. La première synchro part tout de suite.\n" +
                        "3. Ensuite, le kit se synchronise à chaque fermeture (ou avec SYNCHRONISER MAINTENANT).",
                    Cp.Text, 14.sp, MonoFont, FontWeight.Normal,
                )
                T(
                    "Le téléphone et le PC doivent être sur le même réseau. Au premier lancement, Windows demande d'autoriser " +
                        "Deck Link sur les réseaux privés : accepte, sinon le téléphone ne trouve pas le PC.",
                    Cp.Dim, 13.sp, MonoFont, FontWeight.Normal,
                )
                T("Adresses de ce PC : " + state.addresses().ifEmpty { listOf("aucune") }.joinToString(", "), Cp.Cyan, 13.sp, MonoFont, FontWeight.Normal)
                T("Par câble USB : adb reverse tcp:47321 tcp:47321", Cp.Cyan, 13.sp, MonoFont, FontWeight.Normal)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("OUVRIR LE DOSSIER", Cp.Cyan) { runCatching { Desktop.getDesktop().open(Paths.sync) } }
                    Button(if (renewArmed) "CONFIRMER : NOUVEAU CODE" else "NOUVEAU CODE", Cp.Red) {
                        if (renewArmed) state.renewCode()
                        renewArmed = !renewArmed
                    }
                }
            }
        }
        Section("DERNIÈRE SYNCHRO")
        T(state.lastSync ?: "jamais", Cp.Text, 14.sp, MonoFont, FontWeight.Normal)
        T(
            "Copie du kit : ${Paths.kit}" + if (Paths.fellBack) "\n(le dossier d'installation n'est pas modifiable : la copie est dans ton dossier personnel)" else "",
            Cp.Dim, 12.sp, MonoFont, FontWeight.Normal,
        )
        T("Un fichier supprimé sur le téléphone part dans sync/kit/.corbeille, jamais effacé.", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
        Section("JOURNAL")
        state.log.take(40).forEach { T(it, Cp.Dim, 12.sp, MonoFont, FontWeight.Normal) }
    }
}

// ---------------------------------------------------------------------------------- inventory

@Composable
fun InventoryScreen(kit: KitData) {
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<KitData.Equipment?>(null) }
    if (kit.equipment.isEmpty()) return EmptyKit()
    open?.let { e ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("◄ INVENTAIRE", Cp.Cyan) { open = null }
            T(e.name.ifBlank { e.code }, Cp.Yellow, 22.sp, weight = FontWeight.Bold)
            listOf("Code" to e.code, "N° de série" to e.serial, "Emplacement" to e.location, "État" to e.state, "Mis à jour" to LinkState.time(e.updated), "Notes" to e.notes)
                .filter { it.second.isNotBlank() }
                .forEach { (k, v) -> Row { T(k.uppercase(), Cp.Dim, 13.sp, modifier = Modifier.width(140.dp)); T(v, Cp.Text, 15.sp) } }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(query, { query = it }, "Rechercher (nom, code, série, emplacement…)")
        val rows = kit.equipment.filter { matches(query, it.name, it.code, it.serial, it.location, it.state, it.notes) }
        T("${rows.size} / ${kit.equipment.size} équipements", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
        Table(
            rows,
            listOf(
                Col("Nom", 2f) { it.name },
                Col("Code", 1.4f) { it.code },
                Col("Série", 1.3f) { it.serial },
                Col("Emplacement", 1.4f) { it.location },
                Col("État", 1f, color = { if (it.state == "HS" || it.state == "À réparer") Cp.Red else Cp.Cyan }) { it.state },
            ),
            onClick = { open = it },
        )
    }
}

@Composable
fun StockScreen(kit: KitData) {
    var query by remember { mutableStateOf("") }
    if (kit.stock.isEmpty()) return EmptyKit()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(query, { query = it }, "Rechercher un consommable")
        val low = kit.stock.count { it.low }
        if (low > 0) T("$low article(s) sous le seuil d'alerte", Cp.Red, 14.sp)
        Table(
            kit.stock.filter { matches(query, it.name, it.location, it.notes) },
            listOf(
                Col("Article", 2.2f) { it.name },
                Col("Quantité", 1f, color = { if (it.low) Cp.Red else Cp.Cyan }) { "${it.qty} ${it.unit}" },
                Col("Seuil", 0.8f) { if (it.threshold > 0) "${it.threshold}" else "" },
                Col("Emplacement", 1.4f) { it.location },
                Col("Notes", 1.6f) { it.notes },
            ),
        )
    }
}

@Composable
fun CablesScreen(kit: KitData) {
    var query by remember { mutableStateOf("") }
    if (kit.cables.isEmpty()) return EmptyKit()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(query, { query = it }, "Rechercher (site, prise, switch, VLAN…)")
        Table(
            kit.cables.filter { matches(query, it.site, it.outlet, it.switchName, it.port, it.vlan, it.notes) },
            listOf(
                Col("Site", 1.3f) { it.site },
                Col("Prise", 1f) { it.outlet },
                Col("Switch / port", 1.4f) { listOf(it.switchName, it.port).filter { s -> s.isNotBlank() }.joinToString(" · ") },
                Col("VLAN", 0.7f) { it.vlan },
                Col("Longueur", 0.8f) { if (it.length.isBlank()) "" else "${it.length} m" },
                Col("PoE", 0.7f) { it.poe },
                Col("Test", 0.8f, color = { if (it.test == "Défaut") Cp.Red else Cp.Cyan }) { it.test },
            ),
        )
    }
}

// ---------------------------------------------------------------------------------- logbook

@Composable
private fun Photo(file: File, height: Dp) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.height(height).width(height * 4 / 3).hudPanel(Cp.Cyan.copy(alpha = 0.4f), cut = 4.dp).clickable { runCatching { Desktop.getDesktop().open(file) } }) {
        image?.let { Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun LogbookScreen(kit: KitData) {
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    if (kit.interventions.isEmpty()) return EmptyKit()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(query, { query = it }, "Rechercher une intervention")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(kit.interventions.filter { matches(query, it.title, it.site, it.client, it.ticket, it.notes) }) { i ->
                    val on = i.id == openId
                    Column(
                        Modifier.fillMaxWidth()
                            .hudPanel((if (i.open) Cp.Yellow else Cp.Cyan).copy(alpha = if (on) 1f else 0.4f), if (on) Cp.Panel else Cp.Panel.copy(alpha = 0.6f), 4.dp)
                            .clickable { openId = i.id }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        T(i.title.ifBlank { "(sans titre)" }, if (i.open) Cp.Yellow else Cp.Text, 15.sp, maxLines = 1)
                        T(listOf(LinkState.time(i.started), i.client, i.site).filter { it.isNotBlank() }.joinToString(" · "), Cp.Dim, 12.sp, MonoFont, FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        val i = kit.interventions.firstOrNull { it.id == openId } ?: kit.interventions.first()
        LaunchedEffect(kit) { if (kit.interventions.none { it.id == openId }) openId = kit.interventions.first().id }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T(i.title.ifBlank { "(sans titre)" }, Cp.Yellow, 22.sp, weight = FontWeight.Bold)
            T(
                listOf(
                    "début ${LinkState.time(i.started)}",
                    if (i.open) "EN COURS" else "fin ${LinkState.time(i.ended)}",
                    i.client, i.site, if (i.ticket.isBlank()) "" else "ticket ${i.ticket}",
                ).filter { it.isNotBlank() }.joinToString(" · "),
                if (i.open) Cp.Yellow else Cp.Cyan, 13.sp, MonoFont, FontWeight.Normal,
            )
            if (i.notes.isNotBlank()) T(i.notes, Cp.Text, 15.sp)
            if (i.photos.isNotEmpty()) {
                Section("PHOTOS (${i.photos.size})")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(i.photos) { name -> Photo(File(File(File(Paths.kit, "logbook"), i.id), name), 150.dp) }
                }
            }
            if (i.journal.isNotEmpty()) {
                Section("JOURNAL")
                i.journal.sortedBy { it.at }.forEach { e ->
                    Row { T(LinkState.time(e.at), Cp.Dim, 13.sp, MonoFont, FontWeight.Normal, Modifier.width(130.dp)); T(e.text, Cp.Text, 14.sp) }
                }
            }
            if (i.consumed.isNotEmpty()) {
                Section("CONSOMMÉ")
                i.consumed.forEach { T("${it.qty} ${it.unit} · ${it.name}", Cp.Text, 14.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- clients

/** Quote states as the launcher stores them (kit/Clients.kt, QuoteState). */
private val QuoteStates = mapOf("draft" to "Brouillon", "sent" to "Envoyé", "accepted" to "Accepté", "refused" to "Refusé")

@Composable
fun ClientsScreen(kit: KitData) {
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    if (kit.clients.isEmpty()) return EmptyKit()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(query, { query = it }, "Rechercher un client")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(kit.clients.filter { matches(query, it.name, it.contact, it.address, it.notes) }) { c ->
                    T(
                        c.name.ifBlank { "(sans nom)" }, if (c.id == openId) Cp.Yellow else Cp.Text, 15.sp, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().hudPanel(Cp.Cyan.copy(alpha = 0.4f), cut = 4.dp).clickable { openId = c.id }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        val c = kit.clients.firstOrNull { it.id == openId } ?: kit.clients.first()
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T(c.name, Cp.Yellow, 22.sp, weight = FontWeight.Bold)
            listOf("Contact" to c.contact, "Téléphone" to c.phone, "E-mail" to c.email, "Adresse" to c.address, "Accès" to c.access, "Consignes" to c.instructions, "Notes" to c.notes)
                .filter { it.second.isNotBlank() }
                .forEach { (k, v) -> Row { T(k.uppercase(), Cp.Dim, 13.sp, modifier = Modifier.width(120.dp)); T(v, Cp.Text, 15.sp) } }
            if (c.contracts.isNotEmpty()) {
                Section("CONTRATS")
                c.contracts.forEach {
                    T(listOf(it.ref, it.kind, "${LinkState.day(it.start)} → ${LinkState.day(it.end)}", it.amount).filter { s -> s.isNotBlank() && s != " → " }.joinToString(" · "), Cp.Text, 14.sp)
                }
            }
            if (c.quotes.isNotEmpty()) {
                Section("DEVIS")
                c.quotes.forEach { T(listOf(it.ref, it.label, LinkState.day(it.date), it.amount, QuoteStates[it.state] ?: it.state).filter { s -> s.isNotBlank() }.joinToString(" · "), Cp.Text, 14.sp) }
            }
            val visits = kit.interventions.filter { it.client.equals(c.name, ignoreCase = true) }
            if (visits.isNotEmpty()) {
                Section("INTERVENTIONS (${visits.size})")
                visits.forEach { T("${LinkState.time(it.started)} · ${it.title}", Cp.Text, 14.sp) }
            }
        }
    }
}
