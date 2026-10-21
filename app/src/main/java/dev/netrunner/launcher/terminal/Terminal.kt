package dev.netrunner.launcher.terminal

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.launcher.AppEntry
import dev.netrunner.launcher.LauncherViewModel.Panel
import dev.netrunner.launcher.system.SystemInfo
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

    init {
        out("CYBERDECK OS v0.2 — Netrunner", Tone.Ok)
        out("Tape « help » pour la liste des commandes.", Tone.Dim)
    }

    fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        out("$prompt $text", Tone.Command)
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
            "notifs", "feed", "messages" -> openPanel(Panel.Feed)
            "control", "settings", "cc" -> openPanel(Panel.Quick)
            "glitch", "chat", "ai", "ia" -> openPanel(Panel.Chat)
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
            else -> out("commande inconnue : $name (tape « help »)", Tone.Error)
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
            "control         panneau de réglages rapides",
            "glitch          parle à GLITCH, l'IA du cyberdeck",
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
