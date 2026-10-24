package dev.netrunner.launcher.kit

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.sync.Discovery
import dev.netrunner.sync.FolderScanner
import dev.netrunner.sync.NotPairedException
import dev.netrunner.sync.PairingCode
import dev.netrunner.sync.PairingOffer
import dev.netrunner.sync.SyncClient
import dev.netrunner.sync.SyncServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirror of the field kit on a PC running Deck Link. The PC shows a QR code holding a pairing
 * code; once scanned, each sync sends the kit files that changed, sealed with that code, over
 * Wi-Fi or USB (adb reverse). The phone stays the reference: the PC keeps a copy, and files
 * deleted here go to the PC's trash folder.
 */
object PcLink {
    data class Status(val ok: Boolean, val text: String, val at: Long)

    private const val PREFS = "pc_link"
    private val scanner = FolderScanner()
    private val busyFlag = AtomicBoolean(false)

    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var status by mutableStateOf<Status?>(null)
        private set
    var pcName by mutableStateOf<String?>(null)
        private set
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        pcName = p.getString("name", null)?.takeIf { p.getString("code", null) != null }
        val at = p.getLong("status_at", 0)
        if (at > 0) status = Status(p.getBoolean("status_ok", false), p.getString("status_text", "").orEmpty(), at)
    }

    fun paired(context: Context) = prefs(context).getString("code", null)?.let { PairingCode.parse(it) } != null

    fun autoEnabled(context: Context) = prefs(context).getBoolean("auto", true)

    fun setAuto(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto", on).apply()

    /** Reads a scanned QR code or a typed code; false if it is not a Deck Link code. */
    fun pair(context: Context, text: String): Boolean {
        val offer = PairingOffer.parse(text)
        val code = offer?.code ?: PairingCode.parse(text) ?: return false
        prefs(context).edit()
            .putString("code", code.text)
            .putString("host", offer?.host)
            .putInt("port", offer?.port ?: SyncServer.DEFAULT_PORT)
            .putString("name", offer?.pcName ?: "PC")
            .remove("status_at")
            .apply()
        pcName = offer?.pcName ?: "PC"
        status = null
        return true
    }

    fun unpair(context: Context) {
        prefs(context).edit().clear().apply()
        pcName = null
        status = null
    }

    /** Called when the kit closes: a quiet sync if a PC is paired and auto sync is on. */
    fun autoSync(context: Context) {
        val app = context.applicationContext
        if (!paired(app) || !autoEnabled(app)) return
        Thread({ runCatching { sync(app) } }, "kit-pc-sync").start()
    }

    /** Blocking: run it off the main thread. Returns the status shown under the button. */
    fun sync(context: Context): Status {
        if (!busyFlag.compareAndSet(false, true)) return status ?: Status(false, "synchro déjà en cours", System.currentTimeMillis())
        busy = true
        progress = null
        try {
            val p = prefs(context)
            val code = p.getString("code", null)?.let { PairingCode.parse(it) }
                ?: return remember(context, Status(false, "aucun PC appairé", System.currentTimeMillis()))
            val port = p.getInt("port", SyncServer.DEFAULT_PORT)
            val root = context.filesDir
            val files = scanner.scan(root) { KitBackup.isKitFile(it) }
            val device = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            var lastError: IOException? = null
            for (address in candidates(p.getString("host", null), port)) {
                try {
                    val r = SyncClient.sync(address, code, device, "kit", files, open = { File(root, it).inputStream() }) { done, total ->
                        progress = done to total
                    }
                    p.edit().putString("host", r.host).apply()
                    val text = buildString {
                        append(if (r.sent == 0) "déjà à jour" else "${r.stored} fichier(s) envoyé(s)")
                        if (r.removed > 0) append(", ${r.removed} retiré(s)")
                        if (r.skipped > 0) append(", ${r.skipped} modifié(s) pendant l'envoi : relance")
                        append(" · via ").append(if (r.host == "127.0.0.1") "USB" else r.host)
                    }
                    return remember(context, Status(true, text, System.currentTimeMillis()))
                } catch (e: NotPairedException) {
                    return remember(context, Status(false, e.message ?: "code refusé", System.currentTimeMillis()))
                } catch (e: IOException) {
                    lastError = e
                }
            }
            val why = lastError?.message?.takeIf { "refused" !in it && "timed out" !in it && "ECONN" !in it }
            return remember(context, Status(false, why ?: "PC introuvable : Deck Link est-il ouvert, sur le même Wi-Fi ?", System.currentTimeMillis()))
        } finally {
            busy = false
            progress = null
            busyFlag.set(false)
        }
    }

    /** USB first (adb reverse makes the PC answer on the phone's own port), then the last address, then a broadcast. */
    private fun candidates(host: String?, port: Int): Sequence<InetSocketAddress> = sequence {
        yield(InetSocketAddress("127.0.0.1", port))
        if (!host.isNullOrBlank()) yield(InetSocketAddress(host, port))
        Discovery.find().filter { it.hostString != host }.forEach { yield(it) }
    }

    private fun remember(context: Context, s: Status): Status {
        status = s
        prefs(context).edit().putBoolean("status_ok", s.ok).putString("status_text", s.text).putLong("status_at", s.at).apply()
        return s
    }
}
