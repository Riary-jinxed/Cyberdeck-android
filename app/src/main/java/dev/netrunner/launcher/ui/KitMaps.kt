package dev.netrunner.launcher.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.kit.Coverage
import dev.netrunner.launcher.kit.Logbook
import dev.netrunner.launcher.kit.NetMap
import dev.netrunner.launcher.kit.Survey
import dev.netrunner.launcher.kit.SurveyPoint
import dev.netrunner.launcher.terminal.NetTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

@Composable
internal fun MapsTab() {
    var mode by remember { mutableStateOf("Réseau") }
    Column(Modifier.fillMaxSize()) {
        KitChips(listOf("Réseau", "Couverture Wi-Fi"), mode) { mode = it }
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        if (mode == "Réseau") NetMapView() else CoverageView()
    }
}

/** Files [bitmap] in the photos of the most recent open intervention on [site], if any. */
private fun fileInLogbook(context: android.content.Context, site: String, bitmap: Bitmap, name: String): Boolean {
    val i = Logbook.openOn(site) ?: return false
    val f = java.io.File(context.cacheDir, "shared/$name.png").apply { parentFile?.mkdirs() }
    runCatching { f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrElse { return false }
    return Logbook.importPhoto(context, i.id, android.net.Uri.fromFile(f), 3000).also { f.delete() }
}

/** A rendered picture the user can pinch to zoom and drag, with share and logbook actions. */
@Composable
private fun BitmapViewer(bitmap: Bitmap, site: String, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), fill = Color.White, cut = 6.dp)
                .pointerInput(bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
        ) {
            Image(
                image,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitButton("PARTAGER ►", Cp.Yellow, Modifier.weight(1f), filled = true) { shareBitmap(context, bitmap, name, "Partager") }
            val open = Logbook.openOn(site)
            KitButton(if (open != null) "→ CARNET" else "→ CARNET (AUCUNE EN COURS)", if (open != null) Cp.Cyan else Cp.Dim, Modifier.weight(1f)) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { fileInLogbook(context.applicationContext, site, bitmap, name) }
                    Toast.makeText(
                        context,
                        if (ok) "Ajouté aux photos de « ${open?.title?.ifBlank { site } ?: site} »" else "Ouvre une intervention sur ce site dans le carnet",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}

@Composable
private fun SiteChips(sites: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sites.forEach { s ->
            val on = s == selected
            Text(
                s,
                color = if (on) Cp.Background else Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 4.dp)
                    .clickable { onSelect(s) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------- network map

@Composable
private fun NetMapView() {
    val sites = remember { NetMap.sites() }
    var site by remember { mutableStateOf(sites.firstOrNull()) }
    if (sites.isEmpty()) {
        Text(
            "> aucun site documenté. La carte se dessine à partir des câbles relevés (onglet CÂBLES), des VLAN (ADRESSAGE), des baies et de l'inventaire.",
            color = Cp.Dim,
            fontFamily = MonoFont,
            fontSize = 12.sp,
        )
        return
    }
    val current = site ?: return
    val rendered by produceState<Pair<NetMap.Model, Bitmap>?>(null, current) {
        value = null
        value = withContext(Dispatchers.Default) { NetMap.model(current).let { m -> m to NetMap.render(m) } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SiteChips(sites, current) { site = it }
        val r = rendered
        when {
            r == null -> Text("> dessin de la carte…", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
            r.first.empty -> Text("> rien de relevé pour ce site (câbles, baies ou équipements)", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
            else -> {
                Text(
                    "${r.first.switches.size} switchs · ${r.first.switches.sumOf { it.ports.size }} ports · ${r.first.racks.size} baies · pince pour zoomer",
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
                BitmapViewer(r.second, current, "plan-reseau-$current", Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------- coverage

@Composable
private fun CoverageView() {
    var open by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    Coverage.get(open)?.let { s ->
        BackHandler { open = null }
        SurveyView(s, onBack = { open = null })
        return
    }
    if (creating) {
        BackHandler { creating = false }
        SurveyCreator(onDone = { id -> creating = false; open = id })
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("// RELEVÉS DE COUVERTURE", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("[ + NOUVEAU ]", color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { creating = true }.padding(6.dp))
            }
        }
        if (Coverage.items.isEmpty()) {
            item {
                Text(
                    "> prends une photo du plan d'évacuation ou importe un plan, puis tape sur le plan à chaque endroit où tu te tiens : le signal du Wi-Fi auquel le téléphone est connecté est noté. La carte de chaleur se calcule entre les mesures.",
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                )
            }
        }
        items(Coverage.items.toList(), key = { it.id }) { s ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
                    .clickable { open = s.id }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(s.name.ifBlank { "Relevé" }.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(s.site, "${s.points.size} mesures", DateFmt.format(java.time.Instant.ofEpochMilli(s.created))).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun SurveyCreator(onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var site by remember { mutableStateOf(Logbook.get(Logbook.focusId)?.site.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val sites = remember { Logbook.knownSites() }
    fun create(uri: android.net.Uri) {
        busy = true
        scope.launch {
            val s = withContext(Dispatchers.IO) { Coverage.create(context.applicationContext, site, name.ifBlank { "Plan" }, uri) }
            busy = false
            if (s == null) Toast.makeText(context, "Image illisible", Toast.LENGTH_SHORT).show() else onDone(s.id)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) create(uri) }
    val cameraFile = remember { java.io.File(context.cacheDir, "shared/plan-camera.jpg").apply { parentFile?.mkdirs() } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) create(android.net.Uri.fromFile(cameraFile))
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow("Nouveau relevé", onBack = { onDone(null) }) }
        item { KitField("Site", site, { site = it }) }
        if (sites.isNotEmpty()) item { SiteChips(sites.filter { site.isBlank() || it.contains(site.trim(), ignoreCase = true) }.take(8), site) { site = it } }
        item { KitField("Nom (étage, bâtiment…)", name, { name = it }) }
        item {
            Text("Le plan :", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton(if (busy) "…" else "📷 PHOTO DU PLAN", Cp.Yellow, Modifier.weight(1f), filled = true) {
                    if (busy) return@KitButton
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile)
                    runCatching { camera.launch(uri) }.onFailure { Toast.makeText(context, "Aucune appli photo", Toast.LENGTH_SHORT).show() }
                }
                KitButton(if (busy) "…" else "IMAGE ►", Cp.Cyan, Modifier.weight(1f)) {
                    if (!busy) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }
}

@Composable
private fun SurveyView(s: Survey, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var heatmap by remember { mutableStateOf<Bitmap?>(null) }
    var ssid by remember { mutableStateOf<String?>(null) }
    var measuring by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf<SurveyPoint?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val plan by produceState<ImageBitmap?>(null, s.id) {
        value = withContext(Dispatchers.IO) {
            Logbook.decode(context, android.net.Uri.fromFile(Coverage.plan(context, s.id)), 1600)?.asImageBitmap()
        }
    }
    val active = rememberScreenActive()
    val live by produceState<Pair<Int, Int>?>(null, active) {
        while (active) {
            value = NetTools.wifiSignal(context)
            delay(2000)
        }
    }

    heatmap?.let { bmp ->
        BackHandler { heatmap = null }
        Column(Modifier.fillMaxSize()) {
            BackRow("Carte de chaleur", s.name, onBack = { heatmap = null })
            val ssids = s.points.map { it.ssid }.filter { it.isNotBlank() }.distinct()
            if (ssids.size > 1) {
                SiteChips(listOf("Tous") + ssids, ssid ?: "Tous") { v ->
                    ssid = if (v == "Tous") null else v
                    scope.launch { heatmap = withContext(Dispatchers.Default) { Coverage.render(context, s, ssid) } }
                }
            }
            BitmapViewer(bmp, s.site, "couverture-${s.site}-${s.name}", Modifier.weight(1f).padding(top = 8.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackRow(s.name.ifBlank { "Relevé" }, listOf(s.site, "${s.points.size} mesures").filter { it.isNotBlank() }.joinToString(" · "), onBack)
        Text(
            live?.let { (rssi, link) -> "SIGNAL ACTUEL : $rssi dBm · $link Mbit/s" } ?: "PAS DE WI-FI : connecte le téléphone au réseau à mesurer",
            color = live?.let { Color(Coverage.color(it.first)) } ?: Cp.Red,
            fontFamily = HudFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (measuring) "> mesure en cours, ne bouge pas…" else "> tape là où tu te tiens pour mesurer · appui long sur un point pour le retirer",
            color = Cp.Dim,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        val img = plan
        if (img == null) {
            Text("> chargement du plan…", color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .aspectRatio(img.width.toFloat() / img.height)
                    .pointerInput(s.points) {
                        detectTapGestures(
                            onTap = { pos ->
                                if (measuring) return@detectTapGestures
                                val x = pos.x / size.width
                                val y = pos.y / size.height
                                measuring = true
                                scope.launch {
                                    val r = Coverage.measure(context)
                                    measuring = false
                                    if (r == null) {
                                        Toast.makeText(context, "Pas de connexion Wi-Fi", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Coverage.addPoint(context, s.id, SurveyPoint(x, y, r.rssi, r.ssid, r.bssid, r.frequencyMhz, System.currentTimeMillis()))
                                    }
                                }
                            },
                            onLongPress = { pos ->
                                val near = s.points.minByOrNull { hypot(it.x * size.width - pos.x, it.y * size.height - pos.y) }
                                if (near != null && hypot(near.x * size.width - pos.x, near.y * size.height - pos.y) < 60.dp.toPx()) armed = near
                            },
                        )
                    }
            ) {
                Image(img, contentDescription = "Plan", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    s.points.forEach { p ->
                        val c = Offset(p.x * size.width, p.y * size.height)
                        drawCircle(Color(Coverage.color(p.rssi)), radius = 9.dp.toPx(), center = c)
                        drawCircle(if (p == armed) Color.Red else Color.White, radius = 9.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }
        armed?.let { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Point ${p.rssi} dBm · ${p.ssid}", color = Cp.Text, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.weight(1f))
                KitButton("RETIRER", Cp.Red) { Coverage.removePoint(context, s.id, p); armed = null }
                KitButton("✕", Cp.Dim) { armed = null }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitButton("CARTE DE CHALEUR ►", Cp.Yellow, Modifier.weight(1f), filled = true) {
                if (s.points.isEmpty()) {
                    Toast.makeText(context, "Ajoute d'abord des mesures", Toast.LENGTH_SHORT).show()
                    return@KitButton
                }
                scope.launch { heatmap = withContext(Dispatchers.Default) { Coverage.render(context, s, ssid) } }
            }
            KitButton(if (confirmDelete) "CONFIRMER" else "SUPPRIMER", Cp.Red, Modifier.weight(1f)) {
                if (confirmDelete) {
                    Coverage.delete(context, s.id)
                    onBack()
                } else {
                    confirmDelete = true
                }
            }
        }
    }
}
