package dev.netrunner.aska

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** How often she speaks up on her own. */
enum class Chatter(val label: String, val everyMinutes: Int) {
    Quiet("Discrète", 0),
    Normal("Normale", 45),
    Chatty("Bavarde", 15),
}

@Serializable
data class Settings(
    /** What she calls the runner. */
    val handle: String = "V",
    val ollamaUrl: String = "http://localhost:11434",
    /** Ollama model; empty picks an installed Gemma, else the first installed model. */
    val model: String = "",
    /** For her weather pose; empty leaves the weather out. */
    val city: String = "",
    val chatter: Chatter = Chatter.Normal,
    /** Bounces along when the runner types (Bongo Cat). */
    val keyboard: Boolean = true,
    val scale: Float = 1f,
    /** Where she sits on the desktop; -1 puts her in the bottom right corner. */
    val x: Int = -1,
    val y: Int = -1,
    /** Deck Link's folder; empty looks in the usual places. */
    val deckLink: String = "",
    val autostart: Boolean = false,
    /** Game mode: smaller, and she keeps her remarks for herself. */
    val game: Boolean = false,
)

@Serializable
data class Turn(val fromUser: Boolean, val text: String)

/** Her settings and memory of the conversation, in the user's config folder. */
object Store {
    val dir: File = run {
        System.getenv("ASKA_HOME")?.let { return@run File(it) }
        val appData = System.getenv("APPDATA")
        when {
            isWindows && appData != null -> File(appData, "Aska")
            else -> File(System.getenv("XDG_CONFIG_HOME") ?: File(System.getProperty("user.home"), ".config").path, "aska")
        }
    }.also { it.mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val settingsFile get() = File(dir, "settings.json")
    private val chatFile get() = File(dir, "chat.json")

    fun settings(): Settings = runCatching { json.decodeFromString<Settings>(settingsFile.readText()) }.getOrDefault(Settings())

    fun save(settings: Settings) {
        runCatching { settingsFile.writeText(json.encodeToString(Settings.serializer(), settings)) }
    }

    fun chat(): List<Turn> = runCatching { json.decodeFromString<List<Turn>>(chatFile.readText()) }.getOrDefault(emptyList())

    fun saveChat(turns: List<Turn>) {
        runCatching { chatFile.writeText(json.encodeToString(turns.takeLast(KEPT_TURNS))) }
    }

    private val tapsFile get() = File(dir, "taps.txt")

    /** The keys counted since she was installed, like Bongo Cat's counter. */
    fun taps(): Long = runCatching { tapsFile.readText().trim().toLong() }.getOrDefault(0L)

    fun saveTaps(count: Long) {
        runCatching { tapsFile.writeText(count.toString()) }
    }

    /** Tells Deck Link where she is installed (install.dir), so its updates land in the same folder. */
    fun recordInstall() {
        val app = System.getProperty("jpackage.app-path") ?: return
        val home = File(app).absoluteFile.parentFile.let { if (it.name == "bin") it.parentFile else it }
        runCatching { File(dir, "install.dir").writeText(home.absolutePath) }
    }

    /** Held while she runs, so a second launch does not put two of her on the screen. */
    private var lock: FileLock? = null

    fun lockSingleInstance(): Boolean = runCatching {
        lock = RandomAccessFile(File(dir, "aska.lock"), "rw").channel.tryLock()
        lock != null
    }.getOrDefault(true)

    private const val KEPT_TURNS = 60
}

val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
