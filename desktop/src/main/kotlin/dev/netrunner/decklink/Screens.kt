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
internal fun Button(label: String, color: Color = Cp.Yellow, filled: Boolean = false, onClick: () -> Unit) {
    T(
        label, if (filled) Cp.Background else color, 14.sp, weight = FontWeight.Bold,
        modifier = Modifier
            .hudPanel(color, if (filled) color else color.copy(alpha = 0.08f), 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
internal fun Section(title: String) = T(title, Cp.Yellow, 13.sp, weight = FontWeight.Bold)

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


/** One field of an [Editor]: free text, several lines, a whole number, or one of [choices]. */
private class Field(val key: String, val label: String, val initial: String, val lines: Int = 1, val number: Boolean = false, val choices: List<String> = emptyList())

/**
 * Form for one item: the fields, SAVE (only the fields as typed, numbers as numbers), and
 * DELETE behind a confirmation. Edits wait in the outbox until the phone syncs.
 */
@Composable
private fun Editor(key: String, title: String, fields: List<Field>, onSave: (Map<String, Any>) -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit) {
    val values = remember(key) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, it.initial) } } }
    var deleteArmed by remember(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button("◄ RETOUR", Cp.Cyan, onClick = onClose)
        T(title, Cp.Yellow, 22.sp, weight = FontWeight.Bold)
        fields.forEach { f ->
            T(f.label.uppercase(), Cp.Dim, 12.sp, weight = FontWeight.Bold)
            if (f.choices.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.choices.forEach { c ->
                        val on = values[f.key] == c
                        T(
                            c.uppercase(), if (on) Cp.Background else Cp.Cyan, 12.sp, weight = FontWeight.Bold,
                            modifier = Modifier.hudPanel(Cp.Cyan.copy(alpha = 0.6f), if (on) Cp.Cyan else Cp.Panel, 4.dp)
                                .clickable { values[f.key] = c }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().hudPanel(Cp.Cyan.copy(alpha = 0.5f), cut = 6.dp).padding(horizontal = 12.dp, vertical = 9.dp)) {
                    BasicTextField(
                        values[f.key].orEmpty(),
                        { v -> values[f.key] = if (f.number) v.filter { it.isDigit() }.take(9) else v },
                        singleLine = f.lines == 1,
                        minLines = f.lines,
                        textStyle = TextStyle(color = Cp.Text, fontFamily = if (f.lines > 1) MonoFont else HudFont, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(Cp.Yellow),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("ENREGISTRER ►", Cp.Yellow, filled = true) {
                onSave(fields.associate { f -> f.key to (if (f.number) (values[f.key]?.toIntOrNull() ?: 0) else values[f.key].orEmpty().trim()) })
                onClose()
            }
            if (onDelete != null) {
                Button(if (deleteArmed) "CONFIRMER LA SUPPRESSION" else "SUPPRIMER", Cp.Red) {
                    if (deleteArmed) { onDelete(); onClose() } else deleteArmed = true
                }
            }
        }
        T("Enregistré ici tout de suite, envoyé au téléphone à sa prochaine synchro.", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
    }
}

@Composable
private fun ListHeader(query: String, onQuery: (String) -> Unit, hint: String, newLabel: String, onNew: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { SearchField(query, onQuery, hint) }
        Button(newLabel, Cp.Yellow, onClick = onNew)
    }
}

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

/** Inventory states, as on the phone (kit/KitStore.kt). */
private val States = listOf("En service", "En stock", "À réparer", "HS", "Prêté")

@Composable
fun InventoryScreen(state: LinkState) {
    val kit = state.kit
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<KitData.Equipment?>(null) }
    open?.let { e ->
        val known = kit.equipment.any { it.id == e.id }
        Editor(
            e.id,
            if (known) e.name.ifBlank { e.code } else "Nouvel équipement",
            listOf(
                Field("name", "Nom", e.name), Field("code", "Code (code-barres, QR…)", e.code), Field("serial", "N° de série", e.serial),
                Field("location", "Emplacement", e.location), Field("state", "État", e.state.ifBlank { States[0] }, choices = States),
                Field("notes", "Notes", e.notes, lines = 6),
            ),
            onSave = { state.edit("equipment", e.id, it) },
            onDelete = if (known) ({ state.remove("equipment", e.id) }) else null,
            onClose = { open = null },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListHeader(query, { query = it }, "Rechercher (nom, code, série, emplacement…)", "+ ÉQUIPEMENT") {
            open = KitData.Equipment(state.newId(), "", "", "", "", States[0], "", 0)
        }
        if (kit.equipment.isEmpty()) return@Column EmptyKit()
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

/** Units, as on the phone (kit/Stock.kt). */
private val Units = listOf("pcs", "m", "boîte", "rouleau")

@Composable
fun StockScreen(state: LinkState) {
    val kit = state.kit
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<KitData.StockItem?>(null) }
    open?.let { s ->
        val known = kit.stock.any { it.id == s.id }
        Editor(
            s.id,
            if (known) s.name else "Nouveau consommable",
            listOf(
                Field("name", "Article", s.name), Field("unit", "Unité", s.unit.ifBlank { Units[0] }, choices = (Units + s.unit).filter { it.isNotBlank() }.distinct()),
                Field("qty", "Quantité", s.qty.toString(), number = true), Field("threshold", "Seuil d'alerte (0 = aucun)", s.threshold.toString(), number = true),
                Field("location", "Emplacement", s.location), Field("notes", "Notes", s.notes, lines = 4),
            ),
            onSave = { state.edit("stock", s.id, it) },
            onDelete = if (known) ({ state.remove("stock", s.id) }) else null,
            onClose = { open = null },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListHeader(query, { query = it }, "Rechercher un consommable", "+ CONSOMMABLE") {
            open = KitData.StockItem(state.newId(), "", Units[0], 0, 0, "", "", 0)
        }
        if (kit.stock.isEmpty()) return@Column EmptyKit()
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
            onClick = { open = it },
        )
    }
}

private val PoeModes = listOf("Non", "PoE", "PoE+", "PoE++", "Passif")
private val TestResults = listOf("Non testé", "OK", "Défaut")

@Composable
fun CablesScreen(state: LinkState) {
    val kit = state.kit
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<KitData.Cable?>(null) }
    open?.let { c ->
        val known = kit.cables.any { it.id == c.id }
        Editor(
            c.id,
            if (known) "Câble ${c.outlet}" else "Nouveau câble",
            listOf(
                Field("site", "Site", c.site), Field("outlet", "Prise", c.outlet), Field("switch", "Switch", c.switchName), Field("port", "Port", c.port),
                Field("vlan", "VLAN", c.vlan), Field("length", "Longueur (m)", c.length),
                Field("poe", "PoE", c.poe.ifBlank { PoeModes[0] }, choices = PoeModes), Field("test", "Test", c.test.ifBlank { TestResults[0] }, choices = TestResults),
                Field("notes", "Notes", c.notes, lines = 3),
            ),
            onSave = { state.edit("cable", c.id, it) },
            onDelete = if (known) ({ state.remove("cable", c.id) }) else null,
            onClose = { open = null },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListHeader(query, { query = it }, "Rechercher (site, prise, switch, VLAN…)", "+ CÂBLE") {
            open = KitData.Cable(state.newId(), "", "", "", "", "", "", PoeModes[0], TestResults[0], "", "")
        }
        if (kit.cables.isEmpty()) return@Column EmptyKit()
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
            onClick = { open = it },
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
fun ClientsScreen(state: LinkState) {
    val kit = state.kit
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<KitData.Client?>(null) }
    editing?.let { c ->
        val known = kit.clients.any { it.id == c.id }
        Editor(
            c.id,
            if (known) c.name else "Nouveau client",
            listOf(
                Field("name", "Nom", c.name), Field("contact", "Contact", c.contact), Field("phone", "Téléphone", c.phone), Field("email", "E-mail", c.email),
                Field("address", "Adresse", c.address, lines = 2), Field("access", "Accès (horaires, badge, parking…)", c.access, lines = 3),
                Field("instructions", "Consignes", c.instructions, lines = 3), Field("notes", "Notes", c.notes, lines = 3),
            ),
            onSave = { state.edit("client", c.id, it); openId = c.id },
            onDelete = if (known) ({ state.remove("client", c.id); openId = null }) else null,
            onClose = { editing = null },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListHeader(query, { query = it }, "Rechercher un client", "+ CLIENT") {
            editing = KitData.Client(state.newId(), "", "", "", "", "", "", "", "", emptyList(), emptyList())
        }
        if (kit.clients.isEmpty()) return@Column EmptyKit()
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(c.name, Cp.Yellow, 22.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button("MODIFIER", Cp.Cyan) { editing = c }
                }
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
                if (c.contracts.isNotEmpty() || c.quotes.isNotEmpty()) T("Contrats et devis se modifient sur le téléphone.", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
                val visits = kit.interventions.filter { it.client.equals(c.name, ignoreCase = true) }
                if (visits.isNotEmpty()) {
                    Section("INTERVENTIONS (${visits.size})")
                    visits.forEach { T("${LinkState.time(it.started)} · ${it.title}", Cp.Text, 14.sp) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- fleet

@Composable
fun FleetScreen(state: LinkState) {
    var query by remember { mutableStateOf("") }
    var renewArmed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(
            "Deck Probe, installé sur les PC du parc, envoie leurs infos ici (nom, modèle, n° de série, OS, CPU, RAM, disques, IP, MAC). " +
                "Chaque PC devient une fiche de l'inventaire, envoyée au téléphone à la synchro suivante.",
            Cp.Text, 14.sp, MonoFont, FontWeight.Normal,
        )
        state.fleetError?.let { T(it, Cp.Red, 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("PRÉPARER LE DÉPLOIEMENT ►", Cp.Yellow, filled = true) {
                val dir = state.preparePack()
                runCatching { Desktop.getDesktop().open(dir) }
            }
            Button(if (renewArmed) "CONFIRMER : NOUVELLE CLÉ" else "NOUVELLE CLÉ DU PARC", Cp.Red) {
                if (renewArmed) state.renewFleetKey()
                renewArmed = !renewArmed
            }
        }
        T(
            "Le pack (dossier « deploiement ») contient l'agent, install.cmd à lancer en administrateur ou en script de démarrage GPO, et un LISEZMOI. " +
                "Ouvre le port TCP ${Fleet.PORT} du pare-feu de ce PC pour les agents.",
            Cp.Dim, 12.sp, MonoFont, FontWeight.Normal,
        )
        SearchField(query, { query = it }, "Rechercher un PC (nom, modèle, série, IP, utilisateur…)")
        val rows = state.fleet.filter { r -> matches(query, r.hostname, r.model, r.serial, r.user, r.os, *r.network.map { it.ipv4 + " " + it.mac }.toTypedArray()) }
        T("${rows.size} PC", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
        Table(
            rows,
            listOf(
                Col("PC", 1.3f) { it.hostname },
                Col("Modèle", 1.6f) { listOf(it.manufacturer, it.model).filter { s -> s.isNotBlank() }.joinToString(" ") },
                Col("Série", 1.1f) { it.serial },
                Col("OS", 1.5f) { it.os },
                Col("IP", 1.1f) { it.network.mapNotNull { n -> n.ipv4.takeIf { ip -> ip.isNotBlank() } }.joinToString(", ") },
                Col("Utilisateur", 1.2f) { it.user },
                Col("Vu le", 1f) { LinkState.time(it.seen) },
            ),
        )
    }
}
