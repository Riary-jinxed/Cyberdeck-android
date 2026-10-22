package dev.netrunner.files.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel
import dev.netrunner.deck.hudShape
import dev.netrunner.files.data.FileOps
import dev.netrunner.files.data.Zip
import dev.netrunner.files.net.Smb
import dev.netrunner.files.net.SmbStore
import dev.netrunner.files.system.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shares of the runner's own NAS over SMB: browse, download into the explorer's folder, send the clipboard there. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetworkTab(explorer: Explorer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mutex = remember { Mutex() }
    var servers by remember { mutableStateOf(SmbStore.servers(context)) }
    var editing by remember { mutableStateOf<Smb.Server?>(null) }
    var link by remember { mutableStateOf<Smb.Link?>(null) }
    var server by remember { mutableStateOf<Smb.Server?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<Smb.Entry>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var naming by remember { mutableStateOf<Pair<String, Smb.Entry?>?>(null) }
    var deleting by remember { mutableStateOf<List<Smb.Entry>?>(null) }
    var preview by remember { mutableStateOf<Pair<File, PreviewType>?>(null) }

    DisposableEffect(Unit) {
        onDispose { link?.let { l -> Thread { l.close() }.start() } }
    }

    /** Runs [block] on the link, off the main thread and one at a time, then reloads the folder. */
    fun op(label: String, done: String? = null, reload: Boolean = true, then: () -> Unit = {}, block: (Smb.Link) -> Unit) {
        val l = link ?: return
        scope.launch {
            busy = label
            error = null
            val failure = withContext(Dispatchers.IO) {
                mutex.withLock {
                    runCatching {
                        block(l)
                        if (reload) l.list(path) else null
                    }
                }
            }
            busy = null
            failure.onSuccess { list ->
                if (list != null) entries = list
                if (done != null) Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
                then()
            }.onFailure { error = explain(it) }
            explorer.refresh()
        }
    }

    fun go(target: String) {
        path = target
        selection = emptySet()
        op("Lecture…") { }
    }

    fun connect(s: Smb.Server) {
        scope.launch {
            busy = "Connexion à ${s.label}…"
            error = null
            val result = withContext(Dispatchers.IO) { runCatching { Smb.open(s).let { it to it.list("") } } }
            busy = null
            result.onSuccess { (l, list) ->
                link = l
                server = s
                path = ""
                entries = list
            }.onFailure { error = explain(it) }
        }
    }

    fun disconnect() {
        link?.let { l -> Thread { l.close() }.start() }
        link = null
        server = null
        entries = null
        selection = emptySet()
    }

    BackHandler(enabled = link != null) {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            path.isNotEmpty() -> go(Smb.parent(path))
            else -> disconnect()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        val s = server
        if (link == null || s == null) {
            Text("RÉSEAU", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 10.dp))
            Text(
                "Partages SMB de ton NAS (Synology, QNAP, freebox, Windows, Samba…), sur le même réseau ou par VPN. " +
                    "Le mot de passe est chiffré sur le téléphone.",
                color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(Modifier.padding(vertical = 4.dp)) { Chip("+ SERVEUR", Cp.Yellow) { editing = Smb.Server(id = System.currentTimeMillis().toString(36), name = "", host = "", share = "") } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (servers.isEmpty()) Message("Aucun serveur. Ajoute ton NAS avec + SERVEUR.")
                servers.forEach { srv ->
                    Row(
                        Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 8.dp)
                            .combinedClickable(onClick = { connect(srv) }, onLongClick = { editing = srv })
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("◈", color = Cp.Cyan, fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(srv.label, color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "smb://${srv.host}/${srv.share}" + if (srv.user.isNotBlank()) " · ${srv.user}" else " · invité",
                                color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Chip("MODIFIER", Cp.Dim) { editing = srv }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "smb://${s.host}/${s.share}/$path".trimEnd('/'), color = Cp.Cyan, fontFamily = MonoFont, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Chip("◂ DÉCONNECTER", Cp.Dim) { disconnect() }
                Chip("+ DOSSIER", Cp.Cyan) { naming = "NOUVEAU DOSSIER" to null }
                Chip("ACTUALISER", Cp.Cyan) { go(path) }
                val clip = explorer.clipboard
                if (clip.isNotEmpty()) {
                    Chip("ENVOYER ICI (${clip.size})", Cp.Yellow) {
                        val files = clip
                        explorer.clipboard = emptyList()
                        op("Envoi…", "Envoyé sur le NAS") { l ->
                            files.forEach { f -> l.upload(f, path) { n -> busy = "Envoi… ${FileOps.size(n)}" } }
                        }
                    }
                }
            }
            val list = entries
            Box(Modifier.weight(1f).fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f), cut = 8.dp)) {
                LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                    if (path.isNotEmpty()) {
                        item {
                            Text(
                                "▴ ..", color = Cp.Dim, fontFamily = MonoFont, fontSize = 16.sp,
                                modifier = Modifier.fillMaxWidth().clickable { go(Smb.parent(path)) }.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (list != null && list.isEmpty()) item { Message("Dossier vide.") }
                    items(list.orEmpty(), key = { it.path }) { e ->
                        val on = e.path in selection
                        val kind = if (e.isDir) FileOps.Kind.DIR else FileOps.kind(File(e.name))
                        Row(
                            Modifier.fillMaxWidth().background(if (on) Cp.Yellow.copy(alpha = 0.16f) else Color.Transparent)
                                .combinedClickable(
                                    onLongClick = { selection = if (on) selection - e.path else selection + e.path },
                                    onClick = {
                                        when {
                                            selection.isNotEmpty() -> selection = if (on) selection - e.path else selection + e.path
                                            e.isDir -> go(e.path)
                                            else -> {
                                                val cache = File(context.cacheDir, "smb").apply { deleteRecursively(); mkdirs() }
                                                var got: File? = null
                                                op(
                                                    "Ouverture…", reload = false,
                                                    then = { got?.let { f -> previewType(f)?.let { preview = f to it } ?: Storage.open(context, f) } },
                                                ) { l -> got = l.download(e, cache) { n -> busy = "Téléchargement… ${FileOps.size(n)}" } }
                                            }
                                        }
                                    },
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (e.isDir) "▸" else kind.glyph, color = kindColor(kind), fontFamily = MonoFont, fontSize = 16.sp, modifier = Modifier.width(26.dp))
                            Text(
                                e.name + if (e.isDir) "/" else "", color = if (on) Cp.Yellow else kindColor(kind), fontFamily = MonoFont, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                                if (!e.isDir) Text(FileOps.size(e.size), color = Cp.Text.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 12.sp)
                                Text(DATE.format(Date(e.modified)), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            if (selection.isNotEmpty()) {
                val picked = list.orEmpty().filter { it.path in selection }
                ActionBar("${picked.size} SÉLECTIONNÉ${if (picked.size > 1) "S" else ""}") {
                    Chip("TOUT", Cp.Cyan) { selection = list.orEmpty().map { it.path }.toSet() }
                    Chip("TÉLÉCHARGER", Cp.Yellow) {
                        val target = explorer.dir
                        selection = emptySet()
                        op("Téléchargement…", "Téléchargé dans ${Storage.pretty(target)}", reload = false) { l ->
                            picked.forEach { e -> l.download(e, target) { n -> busy = "Téléchargement… ${FileOps.size(n)}" } }
                        }
                    }
                    if (picked.size == 1) Chip("RENOMMER", Cp.Cyan) { naming = "RENOMMER" to picked[0] }
                    Chip("SUPPR.", Cp.Red) { deleting = picked }
                }
            }
        }
        busy?.let { Text("> $it", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        error?.let { Text(it, color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    editing?.let { srv ->
        ServerDialog(
            srv, isNew = servers.none { it.id == srv.id },
            onDismiss = { editing = null },
            onDelete = { SmbStore.delete(context, srv.id); servers = SmbStore.servers(context); editing = null },
        ) { saved ->
            SmbStore.save(context, saved)
            servers = SmbStore.servers(context)
            editing = null
        }
    }
    naming?.let { (title, entry) ->
        NameDialog(title, entry?.name ?: "", if (entry == null) "CRÉER" else "RENOMMER", onDismiss = { naming = null }) { name ->
            naming = null
            selection = emptySet()
            val clean = name.trim()
            if (clean.isEmpty() || clean.contains('/') || clean.contains('\\')) {
                error = "Nom invalide"
            } else if (entry == null) {
                op("Création…", "Dossier créé") { it.mkdir(path, clean) }
            } else {
                op("Renommage…", "Renommé") { it.rename(entry, clean) }
            }
        }
    }
    deleting?.let { picked ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Cp.Panel,
            shape = hudShape(14.dp),
            title = { Text("SUPPRIMER SUR LE NAS ?", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Text(
                    (if (picked.size == 1) "« ${picked[0].name} »" else "${picked.size} éléments") + ", sans corbeille côté téléphone.",
                    color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp,
                )
            },
            confirmButton = {
                HudButton("SUPPRIMER", color = Cp.Red, filled = true) {
                    deleting = null
                    selection = emptySet()
                    op("Suppression…", "Supprimé") { l -> picked.forEach { l.delete(it) } }
                }
            },
            dismissButton = { HudButton("ANNULER", color = Cp.Dim) { deleting = null } },
        )
    }
    preview?.let { (file, type) ->
        PreviewDialog(file, type, onDismiss = { preview = null }) {
            preview = null
            val target = explorer.dir
            scope.fileOp(context, explorer, "Archive extraite dans ${Storage.pretty(target)}") { Zip.extract(file, target) }
        }
    }
}

@Composable
private fun ServerDialog(server: Smb.Server, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Smb.Server) -> Unit) {
    var s by remember(server.id) { mutableStateOf(server) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cp.Panel,
        shape = hudShape(14.dp),
        title = { Text(if (isNew) "NOUVEAU SERVEUR" else "SERVEUR", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Nom (facultatif)", s.name) { s = s.copy(name = it) }
                Field("Adresse : IP ou nom (ex. 192.168.1.20)", s.host, KeyboardType.Uri) { s = s.copy(host = it.trim()) }
                Field("Dossier partagé (ex. Public)", s.share) { s = s.copy(share = it.trim()) }
                Field("Utilisateur (vide = invité)", s.user) { s = s.copy(user = it.trim()) }
                Field("Mot de passe", s.password, KeyboardType.Password, secret = true) { s = s.copy(password = it) }
                Field("Domaine (facultatif)", s.domain) { s = s.copy(domain = it.trim()) }
            }
        },
        confirmButton = {
            HudButton("ENREGISTRER", color = Cp.Yellow, filled = true) { if (s.host.isNotBlank() && s.share.isNotBlank()) onSave(s) }
        },
        dismissButton = {
            Row {
                if (!isNew) HudButton("SUPPR.", color = Cp.Red, onClick = onDelete)
                HudButton("ANNULER", color = Cp.Dim, onClick = onDismiss)
            }
        },
    )
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, secret: Boolean = false, onChange: (String) -> Unit) {
    TextField(
        value, onChange, singleLine = true, colors = hudFieldColors(),
        label = { Text(label, fontFamily = HudFont, fontSize = 12.sp) },
        textStyle = TextStyle(fontFamily = MonoFont, fontSize = 15.sp),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val DATE = SimpleDateFormat("dd/MM/yy HH:mm", Locale.FRANCE)

/** smbj status codes and socket errors, in plain words. */
private fun explain(e: Throwable): String {
    val text = generateSequence(e) { it.cause }.joinToString(" ") { (it.message ?: "") + " " + it.javaClass.simpleName }
    return when {
        "LOGON_FAILURE" in text || "WRONG_PASSWORD" in text -> "Identifiants refusés par le NAS."
        "BAD_NETWORK_NAME" in text -> "Dossier partagé introuvable : vérifie son nom."
        "ACCESS_DENIED" in text -> "Accès refusé par le NAS."
        "OBJECT_NAME_COLLISION" in text -> "Un élément porte déjà ce nom."
        "OBJECT_NAME_NOT_FOUND" in text || "OBJECT_PATH_NOT_FOUND" in text -> "Élément introuvable (déjà supprimé ?)."
        generateSequence(e) { it.cause }.any { it is UnknownHostException } -> "Adresse inconnue : essaie l'IP du NAS."
        generateSequence(e) { it.cause }.any { it is ConnectException || it is SocketTimeoutException } ->
            "NAS injoignable : même Wi-Fi ou VPN ? SMB activé ?"
        else -> e.message ?: e.javaClass.simpleName
    }
}
