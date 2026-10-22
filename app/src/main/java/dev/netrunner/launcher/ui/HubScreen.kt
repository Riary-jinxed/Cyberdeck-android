package dev.netrunner.launcher.ui

import dev.netrunner.launcher.system.HubPrefs
import dev.netrunner.launcher.call.PhoneContact
import dev.netrunner.launcher.call.DialerActivity
import dev.netrunner.launcher.call.Contacts
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.width
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.AppEntry
import dev.netrunner.launcher.system.ClaudeApp
import dev.netrunner.launcher.system.DeckApps
import dev.netrunner.launcher.system.UiSound
import dev.netrunner.launcher.system.Updater
import kotlinx.coroutines.launch

/** A shortcut on the hub page: a stable id, a label, a glyph, search words and what it opens. */
data class HubShortcut(
    val id: String,
    val label: String,
    val glyph: String,
    val color: @Composable () -> Color,
    val keywords: String = "",
    val onClick: () -> Unit,
)

/**
 * The deck's app hub (swipe left by default): a search over apps, contacts and commands, the
 * quickhacks, the user's shortcuts, recent apps, every Netrunner app installed from the
 * repository and the ones still to install.
 */
@Composable
fun HubScreen(
    apps: List<AppEntry>,
    recents: List<AppEntry>,
    quickhacks: @Composable () -> Unit,
    catalog: List<HubShortcut>,
    onLaunch: (AppEntry) -> Unit,
    onTerminal: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The deck's own apps: same "dev.netrunner." family, launcher excluded.
    val deck = remember(apps) {
        apps.filter { it.packageName.startsWith("dev.netrunner.") && it.packageName != context.packageName }
            .sortedBy { it.label }
    }
    var available by remember { mutableStateOf<List<DeckApps.App>>(emptyList()) }
    var downloading by remember { mutableStateOf<Pair<String, Float>?>(null) }
    LaunchedEffect(deck) {
        available = runCatching { DeckApps.list(context) }.getOrDefault(emptyList())
    }
    val installedPkgs = deck.map { it.packageName }.toSet()
    val updates = available.filter { it.updatable }.associateBy { it.packageName }
    val missing = available.filter { !it.installed && it.packageName !in installedPkgs }

    val chosen = remember { mutableStateListOf<String>().apply { addAll(HubPrefs.load(context)) } }
    var editing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun saveShortcuts() = HubPrefs.save(context, chosen.toList())

    fun install(app: DeckApps.App) {
        if (!Updater.canInstall(context)) {
            Toast.makeText(context, "Autorise Netrunner à installer des applis, puis réessaie", Toast.LENGTH_LONG).show()
            context.startActivity(Updater.installPermissionIntent(context))
            return
        }
        scope.launch {
            downloading = app.id to 0f
            val file = runCatching { DeckApps.download(context, app) { downloading = app.id to it } }.getOrNull()
            downloading = null
            if (file != null) Updater.install(context, file)
            else Toast.makeText(context, "Échec du téléchargement", Toast.LENGTH_SHORT).show()
        }
    }

    if (picking) {
        BackHandler { picking = false }
        ShortcutPicker(
            apps = apps,
            catalog = catalog,
            chosen = chosen,
            onPick = { id ->
                if (id !in chosen) chosen += id
                saveShortcuts()
                picking = false
            },
            onClose = { picking = false },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelHeader(title = "// APPLIS DU DECK", accent = Cp.Yellow, onClose = onClose)

        HubSearchField(query) { query = it }
        if (query.isNotBlank()) {
            BackHandler { query = "" }
            HubSearchResults(query.trim(), apps, catalog, onLaunch, onTerminal)
            return@Column
        }

        quickhacks()

        Row(verticalAlignment = Alignment.CenterVertically) {
            HubLabel("RACCOURCIS", Modifier.weight(1f))
            Text(
                if (editing) "[ OK ]" else "[ ÉDITER ]",
                color = Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { editing = !editing }.padding(top = 4.dp, start = 8.dp),
            )
        }
        val tiles = chosen.mapNotNull { id -> resolveShortcut(id, catalog, apps, onLaunch) }
        val cells: List<HubShortcut?> = if (editing) tiles + null else tiles
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    if (s == null) {
                        // The "add" tile, only while editing.
                        Column(
                            Modifier
                                .weight(1f)
                                .hudPanel(borderColor = Cp.Dim.copy(alpha = 0.6f))
                                .clickable { picking = true }
                                .padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("+", color = Cp.Cyan, fontFamily = HudFont, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("AJOUTER", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        ShortcutTile(
                            s,
                            apps,
                            Modifier.weight(1f),
                            editing = editing,
                            onMove = { delta ->
                                val i = chosen.indexOf(s.id)
                                val j = i + delta
                                if (i >= 0 && j in chosen.indices) {
                                    chosen.add(j, chosen.removeAt(i))
                                    saveShortcuts()
                                }
                            },
                            onRemove = {
                                chosen.remove(s.id)
                                saveShortcuts()
                            },
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (editing) {
            Text(
                "RÉTABLIR LES RACCOURCIS PAR DÉFAUT",
                color = Cp.Dim,
                fontFamily = HudFont,
                fontSize = 11.sp,
                modifier = Modifier.clickable {
                    chosen.clear()
                    chosen.addAll(HubPrefs.Defaults)
                    saveShortcuts()
                },
            )
        }

        if (recents.isNotEmpty()) {
            HubLabel("RÉCENTES")
            AppRow(recents.take(4), onLaunch)
        }

        HubLabel("APPLIS NETRUNNER")
        if (deck.isEmpty()) {
            Text("> aucune appli Netrunner installée", color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
        }
        deck.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { app ->
                    val update = updates[app.packageName]
                    Column(
                        Modifier
                            .weight(1f)
                            .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f))
                            .clickable { onLaunch(app) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(app, 40.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            app.label.removeSuffix(" Netrunner").uppercase(),
                            color = Cp.Text,
                            fontFamily = HudFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        if (update != null) {
                            Text(
                                if (downloading?.first == update.id) "${((downloading?.second ?: 0f) * 100).toInt()}%" else "MAJ ▲",
                                color = Cp.Yellow,
                                fontFamily = HudFont,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable(enabled = downloading == null) { install(update) },
                            )
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (missing.isNotEmpty()) {
            HubLabel("DISPONIBLES")
            missing.forEach { app ->
                val progress = downloading?.takeIf { it.first == app.id }?.second
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
                        .clickable(enabled = downloading == null) { install(app) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.title.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "v${app.release.version} · ${"%.1f".format(app.release.size / 1_048_576f)} Mo",
                            color = Cp.Dim,
                            fontFamily = HudFont,
                            fontSize = 11.sp,
                        )
                    }
                    Text(
                        if (progress != null) "${(progress * 100).toInt()} %" else "INSTALLER ►",
                        color = Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun HubLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 4.dp))
}

/** A shortcut id from [HubPrefs]: a built-in one from [catalog], or an installed app. */
private fun resolveShortcut(id: String, catalog: List<HubShortcut>, apps: List<AppEntry>, onLaunch: (AppEntry) -> Unit): HubShortcut? {
    if (id.startsWith(HubPrefs.APP)) {
        val app = apps.firstOrNull { it.key == id.removePrefix(HubPrefs.APP) } ?: return null
        return HubShortcut(id, app.label.removeSuffix(" Netrunner").uppercase(), "", { Cp.Yellow }) { onLaunch(app) }
    }
    return catalog.firstOrNull { it.id == id }
}

@Composable
private fun ShortcutTile(
    s: HubShortcut,
    apps: List<AppEntry>,
    modifier: Modifier,
    editing: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val color = s.color()
    val app = if (s.id.startsWith(HubPrefs.APP)) apps.firstOrNull { it.key == s.id.removePrefix(HubPrefs.APP) } else null
    Column(
        modifier
            .hudPanel(borderColor = color.copy(alpha = 0.7f), fill = color.copy(alpha = 0.06f))
            .clickable(enabled = !editing) {
                UiSound.play(context, UiSound.Cue.Tap)
                s.onClick()
            }
            .padding(vertical = if (editing) 6.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (app != null) AppIcon(app, 26.dp)
        else Text(s.glyph, color = color, fontFamily = HudFont, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(s.label, color = Cp.Text, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("◄", color = Cp.Cyan, fontSize = 15.sp, modifier = Modifier.clickable { onMove(-1) }.padding(4.dp))
                Text("✕", color = Cp.Red, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onRemove).padding(4.dp))
                Text("►", color = Cp.Cyan, fontSize = 15.sp, modifier = Modifier.clickable { onMove(1) }.padding(4.dp))
            }
        }
    }
}

/** A row of up to four app icons with their names. */
@Composable
private fun AppRow(apps: List<AppEntry>, onLaunch: (AppEntry) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.forEach { app ->
            Column(
                Modifier
                    .weight(1f)
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f))
                    .clickable { onLaunch(app) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(app, 36.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    app.label.uppercase(),
                    color = Cp.Text,
                    fontFamily = HudFont,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        repeat(4 - apps.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun HubSearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 8.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕ ", color = Cp.Cyan, fontFamily = HudFont, fontSize = 15.sp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("applis, contacts, commandes…", color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp),
                cursorBrush = SolidColor(Cp.Yellow),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Text("✕", color = Cp.Dim, fontSize = 15.sp, modifier = Modifier.clickable { onChange("") }.padding(start = 8.dp))
        }
    }
}

private fun matches(text: String, q: String) = text.contains(q, ignoreCase = true)

/** One search over the apps, the contacts and the deck's commands, plus "run it in the terminal". */
@Composable
private fun HubSearchResults(
    query: String,
    apps: List<AppEntry>,
    catalog: List<HubShortcut>,
    onLaunch: (AppEntry) -> Unit,
    onTerminal: (String) -> Unit,
) {
    val context = LocalContext.current
    // Contacts are read once, the first time the search is used.
    var contacts by remember { mutableStateOf<List<PhoneContact>?>(null) }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { runCatching { Contacts.all(context) }.getOrDefault(emptyList()) }
    }
    val appHits = apps.filter { matches(it.label, query) }
        .sortedByDescending { it.label.startsWith(query, ignoreCase = true) }
        .take(8)
    val commandHits = catalog.filter { matches(it.label, query) || matches(it.keywords, query) }
    val digits = query.filter { it.isDigit() }
    val contactHits = contacts.orEmpty().filter {
        matches(it.name, query) || (digits.length >= 3 && it.number.filter(Char::isDigit).contains(digits))
    }.take(6)

    if (appHits.isNotEmpty()) {
        HubLabel("APPLIS")
        appHits.chunked(4).forEach { AppRow(it, onLaunch) }
    }
    if (commandHits.isNotEmpty()) {
        HubLabel("COMMANDES")
        commandHits.forEach { s ->
            ResultRow(s.glyph, s.label, s.keywords.split(' ').take(3).joinToString(" · "), s.color()) {
                UiSound.play(context, UiSound.Cue.Tap)
                s.onClick()
            }
        }
    }
    if (contactHits.isNotEmpty()) {
        HubLabel("CONTACTS")
        contactHits.forEach { c ->
            ResultRow("☏", c.name, c.number, Cp.Cyan) {
                val intent = Intent(context, DialerActivity::class.java)
                    .setData(Uri.fromParts("tel", c.number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
    HubLabel("TERMINAL")
    ResultRow(">_", "exécuter « $query »", "dans le terminal du deck", Cp.Yellow) { onTerminal(query) }
}

@Composable
private fun ResultRow(glyph: String, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = color.copy(alpha = 0.5f), cut = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, color = color, fontFamily = HudFont, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Adds a shortcut: a built-in one not already shown, or any installed app. */
@Composable
private fun ShortcutPicker(
    apps: List<AppEntry>,
    catalog: List<HubShortcut>,
    chosen: List<String>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelHeader(title = "// AJOUTER UN RACCOURCI", accent = Cp.Cyan, onClose = onClose)
        val builtIn = catalog.filter { it.id !in chosen }
        if (builtIn.isNotEmpty()) {
            HubLabel("DU DECK")
            builtIn.forEach { s -> ResultRow(s.glyph, s.label, "", s.color()) { onPick(s.id) } }
        }
        HubLabel("APPLIS")
        HubSearchField(query) { query = it }
        apps.filter { HubPrefs.APP + it.key !in chosen && (query.isBlank() || matches(it.label, query)) }
            .take(40)
            .forEach { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Dim.copy(alpha = 0.4f), cut = 6.dp)
                        .clickable { onPick(HubPrefs.APP + app.key) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app, 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(app.label, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
    }
}

/** Every built-in shortcut; the launcher passes the actions. Add new entries here as the deck grows. */
@Composable
fun rememberHubShortcuts(
    openAska: () -> Unit,
    openStats: () -> Unit,
    openKit: () -> Unit,
    openTerminal: () -> Unit,
    openMessages: () -> Unit,
    openWifi: () -> Unit,
    openFeed: () -> Unit,
    openQuick: () -> Unit,
    openSettings: () -> Unit,
): List<HubShortcut> {
    val context = LocalContext.current
    return remember {
        listOf(
            HubShortcut("claude", "CLAUDE", "✦", { Cp.Yellow }, "ia chat assistant") { ClaudeApp.open(context) },
            HubShortcut("aska", "AS//KA", "◈", { Cp.Red }, "ia mascotte rappel", openAska),
            HubShortcut("stats", "STATS", "▤", { Cp.Cyan }, "temps d'écran batterie data ice", openStats),
            HubShortcut("kit", "KIT", "⚒", { Cp.Cyan }, "terrain inventaire checklist câbles configs mémo ip subnet poe fibre mac", openKit),
            HubShortcut("terminal", "TERMINAL", ">_", { Cp.Yellow }, "console commandes shell", openTerminal),
            HubShortcut("sms", "SMS", "✉", { Cp.Cyan }, "messages", openMessages),
            HubShortcut("wifi", "WI-FI", "≋", { Cp.Cyan }, "réseau wifi", openWifi),
            HubShortcut("feed", "NOTIFS", "◉", { Cp.Yellow }, "notifications flux", openFeed),
            HubShortcut("quick", "CONTRÔLE", "◧", { Cp.Cyan }, "réglages rapides lampe son", openQuick),
            HubShortcut("settings", "CONFIG", "⚙", { Cp.Yellow }, "réglages paramètres thème mise à jour diagnostic", openSettings),
        )
    }
}
