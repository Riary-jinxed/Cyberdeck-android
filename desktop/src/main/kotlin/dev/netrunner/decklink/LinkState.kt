package dev.netrunner.decklink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.sync.Discovery
import dev.netrunner.sync.PairingOffer
import dev.netrunner.sync.SyncServer
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.BindException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The running link: the sync server, the discovery responder, and what the screens show. */
class LinkState {
    var code by mutableStateOf(Paths.code())
        private set
    val outbox = Outbox(Paths.outbox)
    var kit by mutableStateOf(KitData.load(Paths.kit, outbox.changes()))
        private set
    var fleetKey by mutableStateOf(Paths.fleetKey())
        private set
    var fleet by mutableStateOf(Fleet.reports())
        private set
    /** Set when the agents' port could not be opened (the phone sync still works). */
    var fleetError by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var lastSync by mutableStateOf(Paths.lastSync())
        private set
    /** Set when the port is taken (another Deck Link already open) or the network refused it. */
    var fatal by mutableStateOf<String?>(null)
        private set
    val log = mutableStateListOf<String>()
    /** Set while this PC's own inventory is being read. */
    var probing by mutableStateOf(false)
        private set
    val pcName = Paths.pcName()

    val backups = Backups(Paths.kit, Paths.backups)
    var backupList by mutableStateOf(backups.backups())
        private set

    private val server = SyncServer(Paths.sync, { code }, outbox = outbox, onEvent = ::onEvent, backups = backups)
    private val responder = Discovery.Responder(name = { pcName })
    private var stopFleet: () -> Unit = {}
    private var stopAska: () -> Unit = {}

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
        if (fatal == null) {
            try {
                stopFleet = Fleet.serve({ fleetKey }, ::onReport, ::note)
            } catch (e: Exception) {
                fleetError = "Port ${Fleet.PORT} indisponible pour les agents Deck Probe : ${e.message}"
            }
            // Without it As//ka only reads the kit: nothing else depends on it.
            runCatching { stopAska = AskaLink.serve(::askaEdit, ::note) }
            // Once added, this PC's sheet follows its changes (RAM, disks, IP) at each launch.
            if (Paths.probeSelf()) addThisPc()
        }
    }

    // ------------------------------------------------------------------ edits made on the PC

    /** Saves an edit of a [kind] item ("equipment", "stock", "client", "cable"); the phone gets it at the next sync. */
    fun edit(kind: String, id: String, fields: Map<String, Any>) {
        outbox.put(kind, id, fields.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
        reload()
    }

    /** An edit from As//ka, on her server's thread: applied on the screens' side. */
    private fun askaEdit(kind: String, id: String, fields: Map<String, Any>) {
        javax.swing.SwingUtilities.invokeAndWait { edit(kind, id, fields) }
    }

    fun remove(kind: String, id: String) {
        outbox.delete(kind, id)
        reload()
    }

    fun newId(): String = System.currentTimeMillis().toString(36) + (0..999).random().toString(36)

    // ------------------------------------------------------------------ fleet

    @Synchronized
    private fun onReport(r: Fleet.Report) {
        val change = Fleet.toEquipment(r, kit)
        if (change != null) {
            outbox.put("equipment", change.first, change.second)
            note("parc : fiche de ${r.hostname} ${if (kit.equipment.any { it.id == change.first }) "mise à jour" else "créée"}")
        }
        reload()
        fleet = Fleet.reports()
    }

    /** Reads the inventory of the PC Deck Link runs on and adds or updates its sheet, like an agent's report. */
    fun addThisPc() {
        if (probing) return
        probing = true
        note("lecture des infos de ce PC…")
        Thread({
            val result = runCatching { LocalProbe.run().also { Fleet.save(it) } }
            javax.swing.SwingUtilities.invokeLater {
                probing = false
                result.onSuccess { r ->
                    if (Fleet.toEquipment(r, kit) == null) note("parc : la fiche de ${r.hostname} est déjà à jour")
                    onReport(r)
                    Paths.setProbeSelf(true)
                }.onFailure { note("impossible de lire les infos de ce PC : ${it.message}") }
            }
        }, "local-probe").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ documents and backups

    /** What needs looking at, from the fleet and the kit. */
    fun alerts(): List<Alerts.Alert> = Alerts.compute(kit, fleet)

    /** A dated copy of the kit; [force] also when nothing changed since the last one. */
    fun backupNow(force: Boolean = true) = background("sauvegarde") {
        val b = backups.take(force)
        javax.swing.SwingUtilities.invokeLater {
            backupList = backups.backups()
            if (b != null) note("sauvegarde du kit : ${b.file.name} (${b.files} fichiers)")
            else if (force) note("rien à sauvegarder : le kit est vide")
        }
    }

    /** The whole kit and the fleet in one Excel workbook, opened once written. */
    fun exportExcel() = background("export Excel") {
        val f = File(Paths.exports.apply { mkdirs() }, "deck-link-${FileStamp.format(Instant.now())}.xlsx")
        Excel.write(f, Excel.kitWorkbook(kit, fleet))
        done("export Excel : ${f.name}", f)
    }

    /** The PDF report of an intervention, opened once written. */
    fun report(i: KitData.Intervention) = background("rapport PDF") {
        val name = (i.title.ifBlank { "intervention" } + " " + LinkState.day(i.started)).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60)
        val f = File(File(Paths.exports, "rapports").apply { mkdirs() }, "rapport-$name.pdf")
        Pdf.interventionReport(i, kit, File(File(Paths.kit, "logbook"), i.id), f)
        done("rapport PDF : ${f.name}", f)
    }

    /**
     * A sheet of QR labels for [items]. A sheet without a code gets "NR-<id>", as on the phone, so
     * its label scans back to it; that new code reaches the phone at the next sync.
     */
    fun labels(items: List<KitData.Equipment>, skip: Int) {
        val coded = items.map { e ->
            if (e.code.isNotBlank()) e else e.copy(code = "NR-" + e.id.uppercase()).also { edit("equipment", e.id, mapOf("code" to it.code)) }
        }
        background("étiquettes") {
            val f = File(Paths.exports.apply { mkdirs() }, "etiquettes-${FileStamp.format(Instant.now())}.pdf")
            Pdf.labels(coded.map { Pdf.Label(it.code, it.name.ifBlank { it.serial }, listOf(it.location, it.serial).filter { s -> s.isNotBlank() }.joinToString(" · ")) }, f, skip)
            done("${coded.size} étiquette(s) : ${f.name}", f)
        }
    }

    private fun done(text: String, file: File) = javax.swing.SwingUtilities.invokeLater {
        note(text)
        runCatching { java.awt.Desktop.getDesktop().open(file) }
    }

    private fun background(what: String, block: () -> Unit) {
        Thread({
            runCatching(block).onFailure { e -> javax.swing.SwingUtilities.invokeLater { note("échec ($what) : ${e.message ?: e.javaClass.simpleName}") } }
        }, what).apply { isDaemon = true }.start()
    }

    fun preparePack(): java.io.File = Fleet.writePack(pcName, addresses(), fleetKey).also { note("pack de déploiement prêt : $it") }

    fun renewFleetKey() {
        fleetKey = Paths.newFleetKey()
        note("nouvelle clé du parc : prépare à nouveau le pack et redéploie-le")
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
        kit = KitData.load(Paths.kit, outbox.changes())
    }

    fun stop() {
        server.stop()
        responder.stop()
        stopFleet()
        stopAska()
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
                    (if (r.applied > 0) ", ${r.applied} modif(s) du PC appliquée(s)" else "") +
                    if (r.skipped > 0) ", ${r.skipped} ignoré(s)" else ""
                lastSync = text
                Paths.setLastSync(text)
                note("synchro terminée : $text")
                reload()
                if (r.stored > 0 || r.removed > 0) backupNow(force = false)
            }
            is SyncServer.Event.BackupSent -> note("${e.device} a téléchargé la sauvegarde ${e.path} pour la restaurer")
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
        private val FileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm").withZone(ZoneId.systemDefault())

        fun time(ms: Long) = if (ms <= 0) "" else Time.format(Instant.ofEpochMilli(ms))
        fun day(ms: Long) = if (ms <= 0) "" else Day.format(Instant.ofEpochMilli(ms))
    }
}

