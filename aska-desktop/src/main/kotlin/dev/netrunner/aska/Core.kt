package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Her core, installed from the app: Ollama itself, then a Gemma model. Sizes from
 * ollama.com/library/gemma4 and docs.ollama.com/windows.
 */
object Core {

    data class Choice(val tag: String, val label: String, val bytes: Long, val note: String)

    val choices = listOf(
        Choice("gemma4:e2b-it-qat", "Légère", 4_300_000_000, "pour une petite carte graphique, ou sans"),
        Choice("gemma4:e4b-it-qat", "Recommandée", 6_100_000_000, "le bon équilibre entre vitesse et esprit"),
        Choice("gemma4:12b-it-qat", "Futée", 7_200_000_000, "demande une carte graphique d'au moins 10 Go"),
    )

    /** What Ollama takes on the disk, its GPU libraries included. */
    const val OLLAMA_BYTES = 4_000_000_000L

    private const val WINDOWS_SETUP = "https://ollama.com/download/OllamaSetup.exe"
    const val LINUX_COMMAND = "curl -fsSL https://ollama.com/install.sh | sh"

    /** Where Ollama keeps its models: $OLLAMA_MODELS, else ~/.ollama/models. */
    fun modelsDir(): File = System.getenv("OLLAMA_MODELS")?.let(::File) ?: File(System.getProperty("user.home"), ".ollama/models")

    /** Free space on the disk that holds [dir], or its nearest existing parent. */
    fun freeSpace(dir: File): Long {
        var d: File? = dir.absoluteFile
        while (d != null && !d.exists()) d = d.parentFile
        return d?.usableSpace ?: 0L
    }

    fun gigabytes(bytes: Long): String = String.format(java.util.Locale.FRENCH, "%.1f Go", bytes / 1e9)

    /** The installed ollama program, if any. */
    fun binary(): File? {
        val names = if (isWindows) listOf("ollama.exe") else listOf("ollama")
        val known = buildList {
            System.getenv("LOCALAPPDATA")?.let { add(File(it, "Programs/Ollama/ollama.exe")) }
            add(File("/usr/local/bin/ollama"))
            add(File("/usr/bin/ollama"))
            add(File(System.getProperty("user.home"), ".local/bin/ollama"))
        }
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            .flatMap { dir -> names.map { File(dir, it) } }
        return (known + onPath).firstOrNull { it.isFile && it.canExecute() }
    }

    /** Starts Ollama's server in the background, when it is installed but not running. */
    fun startServer(): Boolean = runCatching {
        val exe = binary() ?: return false
        ProcessBuilder(exe.path, "serve").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrDefault(false)

    /**
     * Windows: downloads Ollama's installer and runs it without questions (it is an Inno Setup
     * installer), or with its own windows if the quiet install fails.
     */
    suspend fun installWindows(onProgress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        val setup = File(System.getProperty("java.io.tmpdir"), "OllamaSetup.exe")
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
        val response = http.send(HttpRequest.newBuilder(URI.create(WINDOWS_SETUP)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw Ollama.Failed("téléchargement refusé (${response.statusCode()})")
        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        response.body().use { input ->
            setup.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    onProgress(if (total > 0) done.toFloat() / total else null)
                }
            }
        }
        onProgress(null)
        val quiet = ProcessBuilder(setup.path, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART").start().waitFor()
        if (quiet != 0 && ProcessBuilder(setup.path).start().waitFor() != 0) throw Ollama.Failed("l'installation d'Ollama n'a pas abouti")
        setup.delete()
    }
}
