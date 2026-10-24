package dev.netrunner.decklink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.sync.Discovery
import dev.netrunner.sync.PairingOffer
import dev.netrunner.sync.SyncServer
import java.net.BindException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The running link: the sync server, the discovery responder, and what the screens show. */
class LinkState {
    var code by mutableStateOf(Paths.code())
        private set
    var kit by mutableStateOf(KitData.load(Paths.kit))
        private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var lastSync by mutableStateOf(Paths.lastSync())
        private set
    /** Set when the port is taken (another Deck Link already open) or the network refused it. */
    var fatal by mutableStateOf<String?>(null)
        private set
    val log = mutableStateListOf<String>()
    val pcName = Paths.pcName()

    private val server = SyncServer(Paths.sync, { code }, onEvent = ::onEvent)
    private val responder = Discovery.Responder(name = { pcName })

    init {
        try {
            server.start()
            responder.start()
            note("en écoute sur le port ${server.port} · dossier ${Paths.sync}")
        } catch (e: BindException) {
            fatal = "Le port ${server.port} est déjà utilisé : Deck Link est sans doute déjà ouvert (regarde dans la zone de notification)."
        } catch (e: Exception) {
            fatal = "Impossible d'ouvrir le port ${server.port} : ${e.message}"
        }
    }

    /** What the phone scans: the code plus the first local address (the phone also searches the network). */
    fun offer(): String = PairingOffer(code, addresses().firstOrNull() ?: "0.0.0.0", server.port, pcName).encode()

    fun addresses(): List<String> = Discovery.localAddresses()

    /** A new code: the phone must scan again, and the old code stops working at once. */
    fun renewCode() {
        code = Paths.newCode()
        note("nouveau code d'appairage : le téléphone doit rescanner le QR")
    }

    fun reload() {
        kit = KitData.load(Paths.kit)
    }

    fun stop() {
        server.stop()
        responder.stop()
    }

    private fun onEvent(e: SyncServer.Event) {
        when (e) {
            is SyncServer.Event.Started -> {
                progress = 0 to e.wanted
                note("${e.device} : ${e.files} fichiers, ${e.wanted} à recevoir")
            }
            is SyncServer.Event.Progress -> progress = e.done to e.total
            is SyncServer.Event.Finished -> {
                progress = null
                val r = e.report
                val text = "${Time.format(Instant.ofEpochMilli(r.at))} · ${r.device} · ${r.stored} reçu(s), ${r.removed} retiré(s)" +
                    if (r.skipped > 0) ", ${r.skipped} ignoré(s)" else ""
                lastSync = text
                Paths.setLastSync(text)
                note("synchro terminée : $text")
                reload()
            }
            is SyncServer.Event.Failed -> {
                progress = null
                note("échec : ${e.reason}")
            }
        }
    }

    private fun note(text: String) {
        log.add(0, "${Time.format(Instant.now())}  $text")
        while (log.size > 200) log.removeAt(log.lastIndex)
    }

    companion object {
        val Time: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm").withZone(ZoneId.systemDefault())
        val Day: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy").withZone(ZoneId.systemDefault())

        fun time(ms: Long) = if (ms <= 0) "" else Time.format(Instant.ofEpochMilli(ms))
        fun day(ms: Long) = if (ms <= 0) "" else Day.format(Instant.ofEpochMilli(ms))
    }
}

