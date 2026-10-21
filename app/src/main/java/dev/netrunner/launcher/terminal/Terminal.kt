package dev.netrunner.launcher.terminal

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.launcher.AppEntry
import dev.netrunner.launcher.LauncherViewModel.Panel
import dev.netrunner.launcher.kit.Subnet
import dev.netrunner.launcher.call.DialerActivity
import dev.netrunner.launcher.lock.CyberLock
import dev.netrunner.launcher.ui.Cp
import dev.netrunner.launcher.ui.Faction
import dev.netrunner.launcher.ui.Wallpaper
import dev.netrunner.launcher.ui.WallpaperMode
import dev.netrunner.launcher.system.ClaudeApp
import dev.netrunner.launcher.system.Ghost
import dev.netrunner.launcher.system.SystemInfo
import dev.netrunner.launcher.system.UiSound
import dev.netrunner.launcher.system.toGb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Tone { Command, Normal, Ok, Warn, Error, Dim }

data class TermLine(val text: String, val tone: Tone = Tone.Normal)

/**
 * A small command interpreter. Every write to [lines] happens on the main thread;
 * network work hops to Dispatchers.IO and comes back to print.
 */
class Terminal(
    private val context: Context,
    private val scope: CoroutineScope,
    private val apps: () -> List<AppEntry>,
    private val launch: (AppEntry) -> Unit,
    private val openPanel: (Panel) -> Unit,
) {
    val lines = mutableStateListOf<TermLine>()

    var busy by mutableStateOf(false)
        private set

    val prompt: String =
        "netrunner@" + SystemInfo.deviceName(context).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-') + ":~$"

    private var job: Job? = null

    /** Text shown as a QR code over the terminal, or null. */
    var qr by mutableStateOf<String?>(null)

    private var torchOn = false

    init {
        out("CYBERDECK OS v0.8 — Netrunner", Tone.Ok)
        out("Tape « help » pour la liste des commandes.", Tone.Dim)
    }

    fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        out("$prompt $text", Tone.Command)
        UiSound.play(context, UiSound.Cue.Tap)
        val parts = text.split(Regex("\\s+"))
        val name = parts.first().lowercase(Locale.ROOT)
        val args = parts.drop(1)

        if (busy && name != "stop") {
            out("Une commande tourne encore. Tape « stop » pour l'interrompre.", Tone.Warn)
            return
        }

        when (name) {
            "help", "aide", "?" -> help()
            "clear", "cls" -> lines.clear()
            "exit", "quit" -> openPanel(Panel.None)
            "wifi", "wardrive" -> openPanel(Panel.Wifi)
            "notifs", "feed" -> openPanel(Panel.Feed)
            "sms", "messages", "msg" -> openPanel(Panel.Messages)
            "control", "settings", "cc" -> openPanel(Panel.Quick)
            "config", "reglages", "diag", "diagnostic" -> openPanel(Panel.Settings)
            "claude", "glitch", "chat", "ai", "ia" -> openClaude()
            "call", "dial", "tel" -> dial(args.joinToString(""))
            "keyboard", "kb", "clavier" -> keyboard()
            "lock", "verrou" -> lock(args.firstOrNull())
            "torch", "lampe" -> torch(args.firstOrNull())
            "timer", "minuteur" -> timer(args.joinToString(""))
            "calc", "=" -> calc(args.joinToString(" "))
            "subnet", "cidr" -> subnet(args)
            "kit", "inventaire", "inv", "checklist" -> openPanel(Panel.Kit)
            "qr" -> qrCode(args.joinToString(" "))
            "speedtest", "speed" -> runJob { speedtest() }
            "ghost", "furtif" -> ghost(args.firstOrNull())
            "theme", "faction" -> theme(args.firstOrNull())
            "sound", "son" -> sound(args.firstOrNull())
            "wallpaper", "fond" -> wallpaper(args.firstOrNull())
            "stop" -> stop()
            "whoami" -> out(prompt.removeSuffix(":~$"), Tone.Ok)
            "date" -> out(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm:ss", Locale.getDefault())))
            "sysinfo", "neofetch" -> sysinfo()
            "battery", "bat" -> battery()
            "ip", "ifconfig", "netinfo" -> netinfo()
            "apps", "ls" -> listApps(args.joinToString(" "))
            "open", "run", "launch" -> open(args.joinToString(" "))
            "scan", "nmap" -> runJob { scan() }
            "ports" -> runJob { ports(args.firstOrNull()) }
            "ping" -> runJob { ping(args.firstOrNull()) }
            else -> {
                UiSound.play(context, UiSound.Cue.Error)
                out("commande inconnue : $name (tape « help »)", Tone.Error)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun out(text: String, tone: Tone = Tone.Normal) {
        lines += TermLine(text, tone)
        if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
    }

    /** Adds a line and returns a function that rewrites it in place (progress bars). */
    private fun liveLine(text: String, tone: Tone = Tone.Dim): (String) -> Unit {
        out(text, tone)
        val line = lines.last()
        return { updated ->
            val index = lines.indexOf(line).takeIf { it >= 0 } ?: lines.lastIndex
            if (index >= 0) lines[index] = TermLine(updated, tone)
        }
    }

    private fun runJob(block: suspend () -> Unit) {
        busy = true
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                out("^C interrompu", Tone.Warn)
                throw e
            } catch (e: Exception) {
                out("erreur : ${e.message ?: e.javaClass.simpleName}", Tone.Error)
            } finally {
                busy = false
            }
        }
    }

    private fun openClaude() {
        if (ClaudeApp.open(context)) {
            out("[jack-in] liaison avec Claude...", Tone.Ok)
        } else {
            out("appli Claude introuvable : ouverture du Play Store", Tone.Warn)
        }
    }

    private fun dial(number: String) {
        val intent = Intent(context, DialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (number.isNotBlank()) intent.data = Uri.fromParts("tel", number, null)
        context.startActivity(intent)
        out("[holo] ouverture de la liaison ${number.ifBlank { "" }}".trimEnd(), Tone.Ok)
    }

    private fun torch(arg: String?) {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera?.cameraIdList?.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            out("aucun flash détecté", Tone.Error)
            return
        }
        val on = when (arg) {
            "on" -> true
            "off" -> false
            else -> !torchOn
        }
        runCatching { camera.setTorchMode(id, on) }
            .onSuccess {
                torchOn = on
                out(if (on) "lampe : ON" else "lampe : OFF", Tone.Ok)
            }
            .onFailure { out("lampe indisponible (caméra occupée ?)", Tone.Error) }
    }

    private fun timer(spec: String) {
        val seconds = parseDuration(spec)
        if (seconds == null || seconds <= 0) {
            out("usage : timer 5m | timer 90s | timer 1h30", Tone.Error)
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Netrunner")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { out("minuteur armé : ${formatDuration(seconds)}", Tone.Ok) }
            .onFailure { out("aucune appli Horloge pour le minuteur", Tone.Error) }
    }

    private fun parseDuration(spec: String): Int? {
        val s = spec.lowercase(Locale.ROOT).replace(" ", "")
        s.toIntOrNull()?.let { return it * 60 } // bare number = minutes
        Regex("^(\\d+)h(\\d+)$").find(s)?.let { return it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 }
        val match = Regex("^(?:(\\d+)h)?(?:(\\d+)(?:min|m))?(?:(\\d+)s)?$").find(s) ?: return null
        val (h, m, sec) = match.destructured
        if (h.isEmpty() && m.isEmpty() && sec.isEmpty()) return null
        return (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (sec.toIntOrNull() ?: 0)
    }

    private fun formatDuration(seconds: Int): String = buildString {
        if (seconds >= 3600) append("${seconds / 3600} h ")
        if (seconds % 3600 >= 60) append("${seconds % 3600 / 60} min ")
        if (seconds % 60 > 0) append("${seconds % 60} s")
    }.trim()

    /** subnet 192.168.1.0/24 [/26] : plan d'adressage, et découpage si un 2e préfixe est donné. */
    private fun subnet(args: List<String>) {
        val split = args.lastOrNull()?.takeIf { args.size > 1 && it.startsWith("/") }?.drop(1)?.toIntOrNull()
        val spec = (if (split != null) args.dropLast(1) else args).joinToString(" ")
        val info = Subnet.parse(spec)
        if (info == null) {
            out("usage : subnet 192.168.1.0/24 [/26]  ou  subnet 10.0.0.5 255.255.255.0", Tone.Error)
            return
        }
        Subnet.report(info).forEach { (k, v) -> out("${k.padEnd(18, '.')} $v", Tone.Ok) }
        if (split != null) {
            val parts = Subnet.split(info, split)
            if (parts.isEmpty()) {
                out("découpage impossible : /$split doit être plus long que /${info.prefix}", Tone.Error)
            } else {
                val total = 1L shl (split - info.prefix)
                out("découpage en /$split : $total sous-réseaux de ${parts.first().hosts} hôtes", Tone.Warn)
                parts.forEach { p ->
                    out("${Subnet.text(p.network)}/$split  ${Subnet.text(p.firstHost)} → ${Subnet.text(p.lastHost)}  bc ${Subnet.text(p.broadcast)}")
                }
                if (total > parts.size) out("… ${total - parts.size} de plus (voir le panneau KIT)", Tone.Dim)
            }
        }
    }

    private fun calc(expression: String) {
        if (expression.isBlank()) {
            out("usage : calc 12*(3+4)", Tone.Error)
            return
        }
        runCatching { Calc.eval(expression) }
            .onSuccess { out("= ${Calc.format(it)}", Tone.Ok) }
            .onFailure { out("erreur : ${it.message}", Tone.Error) }
    }

    private fun qrCode(text: String) {
        if (text.isBlank()) {
            out("usage : qr <texte ou lien>", Tone.Error)
            return
        }
        qr = text
        out("QR généré (touche-le pour fermer)", Tone.Ok)
    }

    private suspend fun speedtest() {
        out("> cible : speed.cloudflare.com", Tone.Dim)
        val pings = (1..3).mapNotNull {
            withContext(Dispatchers.IO) {
                runCatching {
                    val start = System.nanoTime()
                    http("https://speed.cloudflare.com/__down?bytes=0").use { it.inputStream.readBytes() }
                    (System.nanoTime() - start) / 1_000_000
                }.getOrNull()
            }
        }
        if (pings.isEmpty()) {
            out("hors ligne : impossible de joindre le serveur", Tone.Error)
            return
        }
        out("latence    ${pings.min()} ms", Tone.Ok)
        val down = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = 15_000_000
                val start = System.nanoTime()
                var total = 0L
                http("https://speed.cloudflare.com/__down?bytes=$bytes").use { conn ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                        }
                    }
                }
                total * 8.0 / ((System.nanoTime() - start) / 1e9) / 1e6
            }.getOrNull()
        }
        out(if (down != null) "descendant %.1f Mb/s".format(down) else "descendant échec", if (down != null) Tone.Ok else Tone.Error)
        val up = withContext(Dispatchers.IO) {
            runCatching {
                val payload = ByteArray(4_000_000)
                val start = System.nanoTime()
                http("https://speed.cloudflare.com/__up").use { conn ->
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(payload.size)
                    conn.outputStream.use { it.write(payload) }
                    conn.inputStream.readBytes()
                }
                payload.size * 8.0 / ((System.nanoTime() - start) / 1e9) / 1e6
            }.getOrNull()
        }
        out(if (up != null) "montant    %.1f Mb/s".format(up) else "montant    échec", if (up != null) Tone.Ok else Tone.Error)
    }

    private fun http(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
        }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun ghost(arg: String?) {
        val lines = if (arg == "off") Ghost.disengage(context) else Ghost.engage(context)
        out(if (Ghost.isOn(context)) "MODE FURTIF ENGAGÉ" else "MODE FURTIF COUPÉ", Tone.Warn)
        lines.forEach { out(it, Tone.Dim) }
    }

    private fun theme(arg: String?) {
        val faction = Faction.entries.firstOrNull { it.id == arg?.lowercase(Locale.ROOT) && it != Faction.Ghost }
        if (faction == null) {
            out("faction actuelle : ${Cp.faction.label}", Tone.Ok)
            out("usage : theme netrunner | arasaka | militech | netwatch", Tone.Dim)
            return
        }
        Cp.select(context, faction)
        UiSound.play(context, UiSound.Cue.Glitch)
        out("allégeance transférée : ${faction.label}", Tone.Ok)
    }

    private fun sound(arg: String?) {
        val on = when (arg) {
            "on" -> true
            "off" -> false
            else -> !UiSound.isEnabled(context)
        }
        UiSound.setEnabled(context, on)
        out(if (on) "sons d'interface : ON" else "sons d'interface : OFF", Tone.Ok)
    }

    private fun wallpaper(arg: String?) {
        val mode = WallpaperMode.entries.firstOrNull { it.id == arg?.lowercase(Locale.ROOT) }
        if (mode == null) {
            out("fond actuel : ${Wallpaper.mode.label}", Tone.Ok)
            out("usage : wallpaper breach | rain | off", Tone.Dim)
            return
        }
        Wallpaper.select(context, mode)
        out("fond d'écran : ${mode.label}", Tone.Ok)
    }

    private fun lock(arg: String?) {
        when (arg) {
            "off" -> {
                CyberLock.setEnabled(context, false)
                out("verrou cyber désactivé", Tone.Warn)
            }
            "on", null -> {
                CyberLock.setEnabled(context, true)
                val missing = CyberLock.missingStep(context)
                if (missing == null) {
                    out("verrou cyber actif : il apparaîtra à la prochaine mise en veille", Tone.Ok)
                } else {
                    context.startActivity(missing)
                    out("autorisation requise : accorde-la puis éteins l'écran", Tone.Warn)
                }
            }
            else -> out("usage : lock [on|off]", Tone.Error)
        }
    }

    private fun keyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
        if (enabled) {
            imm.showInputMethodPicker()
            out("choisis « Clavier Netrunner » dans la liste", Tone.Ok)
        } else {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            out("active « Clavier Netrunner », puis relance « keyboard »", Tone.Warn)
        }
    }

    private fun help() {
        listOf(
            "scan            balaye ton réseau local et liste les appareils",
            "ports [ip]      cherche les ports ouverts (passerelle par défaut)",
            "ping <hôte>     teste la latence (ex : ping 1.1.1.1)",
            "ip              interface, adresse, passerelle, DNS, signal Wi-Fi",
            "sysinfo         appareil, SoC, mémoire, stockage, uptime",
            "battery         niveau, charge et température",
            "apps [filtre]   liste les applis installées",
            "open <appli>    lance une appli (ex : open spotify)",
            "wifi            scanner Wi-Fi et traque de borne",
            "notifs          flux des notifications (SMS, WhatsApp, mails)",
            "sms             messagerie SMS façon jeu",
            "control         panneau de réglages rapides",
            "config / diag   réglages Netrunner et diagnostic des autorisations",
            "call [numéro]   ouvre la holo-liaison (appels)",
            "keyboard        active / choisit le clavier Netrunner",
            "lock [on|off]   écran de verrouillage cyberdeck",
            "ghost [off]     mode furtif (silence, HUD sombre, radios)",
            "theme <faction> netrunner, arasaka, militech, netwatch",
            "wallpaper <m>   fond animé : breach, rain, off",
            "sound [on|off]  sons et vibrations d'interface",
            "torch [on|off]  lampe torche",
            "timer <durée>   minuteur (ex : timer 5m, timer 1h30, timer 90s)",
            "calc <expr>     calculatrice (ex : calc (12+8)*1,2^2)",
            "subnet <ip/n> [/m]  calculateur de sous-réseaux (ex : subnet 192.168.1.0/24 /26)",
            "kit             inventaire, checklists et sous-réseaux",
            "qr <texte>      affiche un QR code (lien, texte, Wi-Fi…)",
            "speedtest       latence, débit descendant et montant",
            "claude          ouvre l'appli Claude (ton compte)",
            "stop            interrompt la commande en cours",
            "clear / exit    efface l'écran / ferme le terminal",
        ).forEach { out(it) }
    }

    private fun sysinfo() {
        val mem = SystemInfo.memory(context)
        val disk = SystemInfo.storage()
        val battery = SystemInfo.battery(context)
        val up = SystemInfo.uptimeMillis() / 60_000
        out(SystemInfo.deviceName(context).uppercase(Locale.ROOT), Tone.Ok)
        out("OS         Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        out("Modèle     ${Build.MANUFACTURER} ${Build.MODEL}")
        out("SoC        ${SystemInfo.socModel()} · ${Runtime.getRuntime().availableProcessors()} cœurs")
        out("RAM        ${(mem.totalBytes - mem.availBytes).toGb()} / ${mem.totalBytes.toGb()}")
        out("Stockage   ${(disk.totalBytes - disk.availBytes).toGb()} / ${disk.totalBytes.toGb()}")
        out("Uptime     ${up / 60} h ${up % 60} min")
        out("Batterie   ${battery.level} %" + if (battery.charging) " (en charge)" else "")
    }

    private fun battery() {
        val b = SystemInfo.battery(context)
        val bar = "█".repeat((b.level.coerceIn(0, 100) + 9) / 10).padEnd(10, '░')
        out("[$bar] ${b.level} %", if (b.level < 20) Tone.Error else Tone.Ok)
        out("État         " + if (b.charging) "en charge" else "sur batterie")
        b.temperatureC?.let { out("Température  ${"%.1f".format(Locale.FRANCE, it)} °C") }
    }

    private fun netinfo() {
        val lan = NetTools.lanInfo(context)
        if (lan == null) {
            out("Aucun réseau actif.", Tone.Error)
            return
        }
        out("Interface  ${lan.interfaceName}" + if (lan.onWifi) " (Wi-Fi)" else " (mobile)", Tone.Ok)
        out("Adresse    ${lan.address}/${lan.prefixLength}")
        out("Passerelle ${lan.gateway ?: "?"}")
        out("DNS        ${lan.dns.joinToString(", ").ifEmpty { "?" }}")
        if (lan.onWifi) {
            NetTools.wifiSignal(context)?.let { (rssi, speed) ->
                out("Signal     $rssi dBm · $speed Mbit/s")
            }
        }
    }

    private fun listApps(filter: String) {
        val list = apps().filter { filter.isBlank() || it.label.contains(filter, ignoreCase = true) }
        list.forEach { out("${it.label.padEnd(24).take(24)} ${it.packageName}") }
        out("${list.size} applis", Tone.Dim)
    }

    private fun open(query: String) {
        if (query.isBlank()) {
            out("usage : open <nom de l'appli>", Tone.Warn)
            return
        }
        val all = apps()
        val app = all.firstOrNull { it.label.equals(query, ignoreCase = true) }
            ?: all.firstOrNull { it.label.startsWith(query, ignoreCase = true) }
            ?: all.firstOrNull { it.label.contains(query, ignoreCase = true) }
        if (app == null) {
            out("aucune appli ne correspond à « $query »", Tone.Error)
            return
        }
        out("jack-in → ${app.label}", Tone.Ok)
        launch(app)
    }

    private suspend fun scan() {
        val lan = NetTools.lanInfo(context)
        if (lan == null || !lan.onWifi) {
            out("Connecte-toi à un Wi-Fi pour scanner le réseau local.", Tone.Error)
            return
        }
        val targets = NetTools.subnet24(lan.address)
        out("Cible ${targets.first().substringBeforeLast('.')}.0/24 · moi ${lan.address} · passerelle ${lan.gateway ?: "?"}", Tone.Ok)
        val progress = liveLine("Balayage... 0 %")
        val start = System.currentTimeMillis()
        val gate = Semaphore(SCAN_PARALLELISM)
        var done = 0
        val found = mutableListOf<HostResult>()

        coroutineScope {
            targets.map { ip ->
                async {
                    val result = gate.withPermit { NetTools.probeHost(ip) }
                    done++
                    progress("Balayage... ${done * 100 / targets.size} %")
                    if (result != null) {
                        found += result
                        val tag = when (ip) {
                            lan.address -> "  (ce téléphone)"
                            lan.gateway -> "  (passerelle)"
                            else -> ""
                        }
                        val ports = if (result.openPorts.isEmpty()) "" else "  " + result.openPorts.joinToString(",")
                        out("[+] ${ip.padEnd(15)}$ports$tag", Tone.Ok)
                    }
                }
            }.awaitAll()
        }
        progress("Balayage terminé en ${"%.1f".format(Locale.FRANCE, (System.currentTimeMillis() - start) / 1000.0)} s")

        if (found.isEmpty()) {
            out("Aucun appareil n'a répondu.", Tone.Warn)
            return
        }
        out("Résolution des noms...", Tone.Dim)
        val names = coroutineScope {
            found.sortedBy { it.ip.substringAfterLast('.').toInt() }
                .map { host -> async { host to NetTools.hostname(host.ip) } }
                .awaitAll()
        }
        names.filter { it.second != null }.forEach { (host, name) -> out("    ${host.ip.padEnd(15)} $name") }
        out("${found.size} appareils trouvés.", Tone.Ok)
    }

    private suspend fun ports(target: String?) {
        val host = target ?: NetTools.lanInfo(context)?.gateway
        if (host == null) {
            out("usage : ports <ip>", Tone.Warn)
            return
        }
        out("Scan de ${NetTools.KnownPorts.size} ports courants sur $host", Tone.Ok)
        val results = coroutineScope {
            NetTools.KnownPorts.keys.map { port ->
                async(Dispatchers.IO) { port to NetTools.tcp(host, port, PORT_TIMEOUT_MS) }
            }.awaitAll()
        }
        val open = results.filter { it.second == PortState.Open }.map { it.first }
        if (open.isEmpty()) {
            out("Aucun port ouvert parmi les ports courants.", Tone.Warn)
        } else {
            open.forEach { out("[OUVERT] ${NetTools.portLabel(it)}", Tone.Ok) }
        }
        val silent = results.all { it.second == PortState.Silent }
        if (silent) out("L'hôte ne répond pas (éteint ou filtré).", Tone.Dim)
    }

    private suspend fun ping(target: String?) {
        val host = target ?: NetTools.lanInfo(context)?.gateway ?: "1.1.1.1"
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder("ping", "-c", "4", "-W", "2", host).redirectErrorStream(true).start()
        }
        try {
            flow {
                process.inputStream.bufferedReader().useLines { seq -> seq.forEach { emit(it) } }
            }.flowOn(Dispatchers.IO).collect { line ->
                val tone = when {
                    line.contains("time=") -> Tone.Ok
                    line.contains("unknown host", ignoreCase = true) -> Tone.Error
                    else -> Tone.Normal
                }
                if (line.isNotBlank()) out(line, tone)
            }
        } finally {
            process.destroy()
        }
    }

    private companion object {
        const val MAX_LINES = 600
        const val SCAN_PARALLELISM = 48
        const val PORT_TIMEOUT_MS = 700
    }
}
