package dev.netrunner.decklink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** The apps list and what is being installed, checked at launch and then once a day. */
class AppsState(private val quit: () -> Unit) {
    var apps by mutableStateOf<List<Apps.App>>(emptyList())
        private set
    var checking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Per app id: download progress (0 to 1), or null while the installer runs. */
    val busy = mutableStateMapOf<String, Float?>()
    val messages = mutableStateMapOf<String, String>()
    val updates get() = apps.count { it.updatable }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(24 * 60 * 60 * 1000L)
            }
        }
    }

    fun refresh() {
        if (checking) return
        checking = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Apps.list() } }
                .onSuccess { apps = it; error = null }
                .onFailure { error = it.message?.takeIf { m -> m.isNotBlank() } ?: "Pas de réponse de GitHub (internet ?)." }
            checking = false
        }
    }

    fun install(app: Apps.App) {
        if (app.id in busy) return
        busy[app.id] = 0f
        messages.remove(app.id)
        scope.launch {
            val result = runCatching {
                val file = withContext(Dispatchers.IO) { Apps.download(app) { p -> scope.launch { if (busy[app.id] != null) busy[app.id] = p } } }
                busy[app.id] = null
                when {
                    !Apps.windows && !Apps.canInstall -> return@runCatching "Téléchargé. Pour l'installer, dans un terminal : sudo apt install '${file.absolutePath}'"
                    app.self -> withContext(Dispatchers.IO) { Apps.installSelf(file, app) }
                    else -> withContext(Dispatchers.IO) { Apps.install(file, app) }
                }
            }.getOrElse { it.message ?: "Téléchargement impossible." }
            busy.remove(app.id)
            if (result == null && app.self) {
                quit()
                return@launch
            }
            messages[app.id] = result ?: "${app.title} ${app.version} est installé."
            if (result == null) refresh()
        }
    }
}

@Composable
fun AppsScreen(state: AppsState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(
            "Les applis PC du deck, installées et mises à jour d'ici comme sur le téléphone. Deck Link regarde s'il y a du neuf à chaque ouverture, puis une fois par jour.",
            Cp.Text, 14.sp, MonoFont, FontWeight.Normal,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(if (state.checking) "RECHERCHE…" else "VÉRIFIER MAINTENANT", Cp.Cyan) { state.refresh() }
            T("Deck Link ${Apps.version}", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
        }
        state.error?.let { T(it, Cp.Red, 14.sp) }
        if (!Apps.windows && !Apps.canInstall) {
            T("Ici l'installation passe par apt (Debian, Ubuntu, Mint…) : sans pkexec ni apt-get, Deck Link télécharge le paquet et donne la commande à copier.", Cp.Yellow, 13.sp)
        }
        if (state.apps.isEmpty() && !state.checking && state.error == null) T("Aucune appli publiée pour ce système pour l'instant.", Cp.Dim, 14.sp)
        state.apps.forEach { app -> AppCard(app, state) }
    }
}

@Composable
private fun AppCard(app: Apps.App, state: AppsState) {
    val accent = if (app.updatable) Cp.Yellow else Cp.Cyan
    Column(Modifier.fillMaxWidth().hudPanel(accent.copy(alpha = 0.7f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(app.title.uppercase(), accent, 18.sp, TitleFont, FontWeight.Bold)
            Spacer(Modifier.padding(horizontal = 6.dp))
            T(
                when {
                    app.installed == null -> "non installée · ${app.version} disponible"
                    app.updatable -> "installée ${app.installed} → ${app.version} disponible"
                    else -> "à jour · ${app.installed}"
                },
                if (app.updatable) Cp.Yellow else Cp.Dim, 13.sp, MonoFont, FontWeight.Normal,
            )
            Spacer(Modifier.weight(1f))
            val progress = state.busy[app.id]
            when {
                app.id in state.busy -> T(if (progress != null) "TÉLÉCHARGEMENT ${(progress * 100).toInt()} %" else "INSTALLATION…", Cp.Yellow, 14.sp, weight = FontWeight.Bold)
                !Apps.windows && !Apps.canInstall && (app.installed == null || app.updatable) ->
                    Button("TÉLÉCHARGER · ${size(app.asset.size)}", Cp.Cyan) { state.install(app) }
                app.installed == null -> Button("INSTALLER · ${size(app.asset.size)}", Cp.Yellow, filled = true) { state.install(app) }
                app.updatable -> Button("METTRE À JOUR · ${size(app.asset.size)}", Cp.Yellow, filled = true) { state.install(app) }
            }
        }
        state.busy[app.id]?.let { p ->
            Box(Modifier.fillMaxWidth().height(4.dp).background(Cp.Panel)) {
                Box(Modifier.fillMaxWidth(p).height(4.dp).background(Cp.Yellow))
            }
        }
        if (app.about.isNotBlank()) T(app.about, Cp.Text, 14.sp)
        if (app.updatable && app.notes.isNotBlank()) T("Nouveau : " + app.notes.lines().first { it.isNotBlank() }, Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
        if (app.self && app.updatable) {
            T(
                if (Apps.windows) "Deck Link se ferme, s'installe au même endroit (tes données restent) et se rouvre tout seul."
                else "Deck Link installe la mise à jour (mot de passe demandé), puis se rouvre.",
                Cp.Dim, 12.sp, MonoFont, FontWeight.Normal,
            )
        } else if (!app.self && Apps.windows && (app.installed == null || app.updatable)) {
            T("Ferme ${app.title} avant de lancer l'installation.", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
        }
        state.messages[app.id]?.let { T(it, if (it.endsWith("installé.")) Cp.Cyan else if (it.startsWith("Téléchargé")) Cp.Yellow else Cp.Red, 14.sp) }
    }
}

private fun size(bytes: Long): String = when {
    bytes <= 0 -> "?"
    bytes >= 1_000_000_000 -> String.format(Locale.FRENCH, "%.1f Go", bytes / 1e9)
    else -> String.format(Locale.FRENCH, "%.0f Mo", bytes / 1e6)
}
