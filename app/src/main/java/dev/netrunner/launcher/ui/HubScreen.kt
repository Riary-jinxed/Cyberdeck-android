package dev.netrunner.launcher.ui

import android.widget.Toast
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

/** A shortcut on the hub page: a label, a glyph and what it opens. */
data class HubShortcut(val label: String, val glyph: String, val color: @Composable () -> Color, val onClick: () -> Unit)

/**
 * The deck's app hub (swipe left by default): every Netrunner app installed from the repository,
 * the ones still to install, and shortcuts (Claude, As//ka, stats…).
 */
@Composable
fun HubScreen(
    apps: List<AppEntry>,
    shortcuts: List<HubShortcut>,
    onLaunch: (AppEntry) -> Unit,
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelHeader(title = "// APPLIS DU DECK", accent = Cp.Yellow, onClose = onClose)

        HubLabel("RACCOURCIS")
        shortcuts.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    val color = s.color()
                    Column(
                        Modifier
                            .weight(1f)
                            .hudPanel(borderColor = color.copy(alpha = 0.7f), fill = color.copy(alpha = 0.06f))
                            .clickable {
                                UiSound.play(context, UiSound.Cue.Tap)
                                s.onClick()
                            }
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.glyph, color = color, fontFamily = HudFont, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(s.label, color = Cp.Text, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
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
private fun HubLabel(text: String) {
    Text(text, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

/** Default shortcuts; the launcher passes the actions. Add new entries here as the deck grows. */
@Composable
fun rememberHubShortcuts(
    openAska: () -> Unit,
    openStats: () -> Unit,
    openKit: () -> Unit,
    openTerminal: () -> Unit,
    openMessages: () -> Unit,
): List<HubShortcut> {
    val context = LocalContext.current
    return remember {
        listOf(
            HubShortcut("CLAUDE", "✦", { Cp.Yellow }) { ClaudeApp.open(context) },
            HubShortcut("AS//KA", "◈", { Cp.Red }, openAska),
            HubShortcut("STATS", "▤", { Cp.Cyan }, openStats),
            HubShortcut("KIT", "⚒", { Cp.Cyan }, openKit),
            HubShortcut("TERMINAL", ">_", { Cp.Yellow }, openTerminal),
            HubShortcut("SMS", "✉", { Cp.Cyan }, openMessages),
        )
    }
}
