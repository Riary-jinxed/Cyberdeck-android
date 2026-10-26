package dev.netrunner.decklink

import dev.netrunner.sync.AgentAuth
import dev.netrunner.sync.PairingCode
import java.io.File
import java.util.Properties

/**
 * Where Deck Link keeps things: everything lives in the folder chosen at install time
 * (`sync/` for the phone's copies, `config/` for the pairing code). When that folder is not
 * writable (e.g. installed under Program Files or /opt), it falls back to ~/DeckLink.
 */
object Paths {
    /** The install folder, from the launcher jpackage writes; null when run from Gradle. */
    val installDir: File? = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile }
        ?.let { if (it.name == "bin") it.parentFile else it }

    val home: File = run {
        System.getenv("DECKLINK_HOME")?.let { return@run File(it) }
        installDir?.takeIf { writable(it) } ?: File(System.getProperty("user.home"), "DeckLink")
    }.also { it.mkdirs() }

    /** True when Deck Link could not use its install folder and fell back to the home folder. */
    val fellBack: Boolean get() = installDir != null && home != installDir

    val sync: File = File(home, "sync").also { it.mkdirs() }
    val kit: File get() = File(sync, "kit")
    /**
     * The pairing code, the fleet key and As//ka's token: on Linux and macOS the folder is the
     * owner's only (rwx------), so other accounts of the PC cannot read them.
     */
    val config: File get() = File(home, "config").ownerOnly()
    private val configFile = File(config, "decklink.properties")

    private fun File.ownerOnly(): File = apply {
        mkdirs()
        runCatching { java.nio.file.Files.setPosixFilePermissions(toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
    }

    private fun writable(dir: File): Boolean = runCatching {
        val probe = File(dir, "sync").also { it.mkdirs() }
        File.createTempFile("probe", ".tmp", probe).delete()
    }.getOrDefault(false)

    private fun props() = Properties().apply { if (configFile.isFile) configFile.inputStream().use { load(it) } }

    private fun save(p: Properties) = configFile.outputStream().use { p.store(it, "Deck Link") }

    /** The pairing code, created on first launch and kept until "Nouveau code". */
    fun code(): PairingCode {
        val p = props()
        p.getProperty("code")?.let { PairingCode.parse(it) }?.let { return it }
        return newCode()
    }

    fun newCode(): PairingCode {
        val code = PairingCode.random()
        save(props().apply { setProperty("code", code.text) })
        return code
    }

    /** Key the Deck Probe agents sign their reports with (64 hex characters), created on first use. */
    fun fleetKey(): String = props().getProperty("fleet")?.takeIf { AgentAuth.keyBytes(it) != null } ?: newFleetKey()

    fun newFleetKey(): String {
        val key = AgentAuth.newKey()
        save(props().apply { setProperty("fleet", key) })
        return key
    }

    val outbox: File get() = File(config, "outbox.json")
    /** The last report of each PC of the fleet, one JSON file per PC. */
    val fleet: File get() = File(sync, "parc").also { it.mkdirs() }
    /** The deployment pack carries the fleet key too. */
    val deployment: File get() = File(home, "deploiement").ownerOnly()
    /** Dated copies of the kit (Backups.kt). */
    val backups: File get() = File(home, "sauvegardes")
    /** Excel exports, PDF reports and label sheets. */
    val exports: File get() = File(home, "exports")

    fun pcName(): String = props().getProperty("name")
        ?: System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC"

    /** True once this PC was added to the inventory: its sheet is then refreshed at each launch. */
    fun probeSelf(): Boolean = props().getProperty("probeSelf") == "true"

    fun setProbeSelf(on: Boolean) = save(props().apply { setProperty("probeSelf", on.toString()) })

    fun lastSync(): String? = props().getProperty("last")

    fun setLastSync(text: String) = save(props().apply { setProperty("last", text) })
}
