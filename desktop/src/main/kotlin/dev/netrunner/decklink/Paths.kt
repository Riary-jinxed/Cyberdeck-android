package dev.netrunner.decklink

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
    private val installDir: File? = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile }
        ?.let { if (it.name == "bin") it.parentFile else it }

    val home: File = run {
        System.getenv("DECKLINK_HOME")?.let { return@run File(it) }
        installDir?.takeIf { writable(it) } ?: File(System.getProperty("user.home"), "DeckLink")
    }.also { it.mkdirs() }

    /** True when Deck Link could not use its install folder and fell back to the home folder. */
    val fellBack: Boolean get() = installDir != null && home != installDir

    val sync: File = File(home, "sync").also { it.mkdirs() }
    val kit: File get() = File(sync, "kit")
    private val configFile = File(File(home, "config").also { it.mkdirs() }, "decklink.properties")

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

    fun pcName(): String = props().getProperty("name")
        ?: System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC"

    fun lastSync(): String? = props().getProperty("last")

    fun setLastSync(text: String) = save(props().apply { setProperty("last", text) })
}
