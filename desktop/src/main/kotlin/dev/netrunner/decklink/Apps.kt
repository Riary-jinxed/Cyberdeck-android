package dev.netrunner.decklink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * The PC apps of the project (Deck Link itself, As//ka PC, the ones to come), installed and
 * updated from here like the phone does for its apps (app/…/system/DeckApps.kt).
 *
 * Each app has its own GitHub releases, tagged "<id>-v<version>" and never marked latest, with a
 * .msi for Windows and a .deb for Linux. A new app shows up as soon as its first release is out:
 * its name is the release title before " v", its Windows name (in « Programmes ») is that same
 * name, and its Debian package is the .deb file name before "-<version>". [KNOWN] covers the
 * apps that do not follow that.
 */
object Apps {
    private const val RELEASES = "https://api.github.com/repos/Riary-jinxed/Cyberdeck-android/releases?per_page=100"
    private val TAG = Regex("^([a-z0-9]+)-v(\\d[\\w.-]*)$")
    private val json = Json { ignoreUnknownKeys = true }

    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val extension = if (windows) ".msi" else ".deb"

    /** The installer names of apps whose release title differs from them. */
    data class Known(val title: String, val windowsName: String, val debName: String, val about: String)

    private val KNOWN = mapOf(
        "decklink" to Known("Deck Link", "Deck Link", "deck-link", "Ce programme : la synchro du kit avec le téléphone."),
        "askapc" to Known("As//ka PC", "Aska", "aska", "As//ka sur le bureau, toujours devant, avec son cerveau local (Ollama)."),
    )

    /** This Deck Link, as packaged (see build.gradle.kts). */
    const val SELF = "decklink"
    val version: String = Apps::class.java.getResourceAsStream("/decklink-version.txt")?.use { it.readBytes().decodeToString().trim() } ?: "0"

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    data class App(
        val id: String,
        val title: String,
        val about: String,
        val version: String,
        val notes: String,
        val asset: Asset,
        val windowsName: String,
        val debName: String,
        val installed: String? = null,
    ) {
        val self get() = id == SELF
        val updatable get() = installed != null && isNewer(version, installed)
    }

    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The latest release of each app that has an installer for this system, from the releases JSON. */
    fun parse(body: String, ext: String = extension): List<App> {
        val latest = linkedMapOf<String, App>()
        json.parseToJsonElement(body).jsonArray.forEach { e ->
            val r = e.jsonObject
            if (r.bool("draft") || r.bool("prerelease")) return@forEach
            val (id, version) = TAG.matchEntire(r.text("tag_name"))?.destructured ?: return@forEach
            val file = r["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.text("name").endsWith(ext) } ?: return@forEach
            if (latest[id]?.let { !isNewer(version, it.version) } == true) return@forEach
            val name = file.text("name")
            val known = KNOWN[id]
            val title = known?.title ?: r.text("name").substringBefore(" v").ifBlank { id }
            latest[id] = App(
                id = id,
                title = title,
                about = known?.about.orEmpty(),
                version = version,
                notes = r.text("body").take(400),
                asset = Asset(name, file.text("browser_download_url"), file["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    file.text("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")),
                windowsName = known?.windowsName ?: title,
                debName = known?.debName ?: name.removeSuffix(ext).substringBefore("-$version").lowercase(),
            )
        }
        // Deck Link first, then by name.
        return latest.values.sortedWith(compareBy({ it.id != SELF }, { it.title.lowercase() }))
    }

    /** Every app with its installed version. Throws on network errors. */
    fun list(): List<App> {
        val conn = (URI(RELEASES).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DeckLink/$version")
        }
        val body = try {
            if (conn.responseCode == 403 || conn.responseCode == 429) error("GitHub limite les demandes pour l'instant, réessaie dans une heure.")
            conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
        val found = parse(body)
        val registry = if (windows) runCatching {
            uninstallEntries(listOf("HKCU", "HKLM").joinToString("\n") { run("reg", "query", "$it\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "/s") })
        }.getOrDefault(emptyList()) else emptyList()
        return found.map { app ->
            app.copy(installed = when {
                app.self -> version
                windows -> registry.firstOrNull { it["DisplayName"].equals(app.windowsName, ignoreCase = true) }?.let { it["DisplayVersion"] ?: "0" }
                else -> debVersion(app.debName)
            })
        }
    }

    /** The values of each key of a `reg query … /s` listing. */
    fun uninstallEntries(output: String): List<Map<String, String>> {
        val entries = mutableListOf<MutableMap<String, String>>()
        val value = Regex("^\\s+(\\S[^\\t]*?)\\s{2,}REG_\\w+\\s{2,}(.*)$")
        output.lineSequence().forEach { line ->
            when {
                line.startsWith("HKEY_") -> entries += mutableMapOf()
                else -> value.find(line.trimEnd())?.let { m -> entries.lastOrNull()?.put(m.groupValues[1], m.groupValues[2]) }
            }
        }
        return entries.filter { it.isNotEmpty() }
    }

    private fun debVersion(pkg: String): String? = runCatching {
        val out = run("dpkg-query", "-W", "-f=\${db:Status-Abbrev} \${Version}", pkg).trim()
        if (out.startsWith("ii")) out.substringAfter(' ').trim() else null
    }.getOrNull()

    private fun run(vararg command: String): String {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = p.inputStream.use { it.readBytes().decodeToString() }
        p.waitFor()
        return out
    }

    /** Downloads [app]'s installer, checks its SHA-256 when GitHub gives one, reports 0 to 1. */
    fun download(app: App, onProgress: (Float) -> Unit): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "decklink-apps").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, app.asset.name.substringAfterLast('/').substringAfterLast('\\'))
        val conn = (URI(app.asset.url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DeckLink/$version")
        }
        val sha = MessageDigest.getInstance("SHA-256")
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: app.asset.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sha.update(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        if (app.asset.sha256 != null && !got.equals(app.asset.sha256, ignoreCase = true)) {
            file.delete()
            error("Le fichier téléchargé est abîmé (empreinte différente), rien n'a été installé.")
        }
        return file
    }

    // ---- Where each app goes, on Windows (a .deb always installs under /opt).

    private val foldersFile get() = File(File(Paths.home, "config").also { it.mkdirs() }, "apps.properties")

    private fun folders() = java.util.Properties().apply { if (foldersFile.isFile) foldersFile.inputStream().use { load(it) } }

    fun remember(app: App, dir: File) {
        runCatching { foldersFile.outputStream().use { out -> folders().apply { setProperty(app.id, dir.absolutePath) }.store(out, "Deck Link : dossier de chaque appli") } }
    }

    /**
     * Where [app] is, or goes: Deck Link's own folder for itself; for the others, the folder it was
     * installed in (as Deck Link remembers it, or as the app wrote it in %APPDATA%\<name>\install.dir),
     * else next to Deck Link.
     */
    fun folder(app: App): File? {
        if (!windows) return null
        if (app.self) return Paths.installDir
        folders().getProperty(app.id)?.let { return File(it) }
        if (app.installed != null) {
            System.getenv("APPDATA")?.let { File(File(it, app.windowsName), "install.dir") }?.takeIf { it.isFile }
                ?.readText()?.trim()?.takeIf { it.isNotBlank() }?.let { return File(it) }
            return null
        }
        return besideDeckLink(app)
    }

    fun besideDeckLink(app: App): File? = Paths.installDir?.parentFile?.let { File(it, app.windowsName) }

    /** The command that installs [file] into [dir] (null: the installer's own default). */
    fun installCommand(file: File, dir: File?): List<String> = if (windows) {
        listOfNotNull("msiexec", "/i", file.absolutePath, "/passive", dir?.let { "INSTALLDIR=\"${it.absolutePath.trimEnd('\\')}\"" })
    } else {
        listOf("pkexec", "apt-get", "install", "-y", "--allow-downgrades", file.absolutePath)
    }

    val canInstall: Boolean get() = windows || File("/usr/bin/pkexec").canExecute() && File("/usr/bin/apt-get").canExecute()

    /** Installs another app and waits; null when it went well, else what went wrong. */
    fun install(file: File, app: App, dir: File? = folder(app)): String? {
        // On Windows through a script: msiexec wants INSTALLDIR="…" as written, which Java's quoting would change.
        val command = if (windows) {
            val script = File(file.parentFile, "install.cmd")
            script.writeText("@echo off\r\nchcp 65001 >nul\r\n${cmdLine(installCommand(file, dir))}\r\nexit /b %errorlevel%\r\n")
            listOf("cmd", "/c", script.absolutePath)
        } else installCommand(file, dir)
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = p.inputStream.use { it.readBytes().decodeToString() }
        return when (val code = p.waitFor()) {
            0, 3010 -> { if (dir != null) remember(app, dir); null }
            1602, 126 -> "Installation annulée."
            1618 -> "Une autre installation est en cours, réessaie après."
            else -> "L'installation de ${app.title} a échoué (code $code). " + out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(200)
        }
    }

    /**
     * Updates Deck Link itself. On Windows a small script waits for this window to close, installs
     * into the same folder and opens the new version; on Linux the package installs now and the new
     * version opens once this one quits. Null when the caller should quit now, else what went wrong.
     */
    fun installSelf(file: File, app: App): String? {
        val launcher = System.getProperty("jpackage.app-path")
        val dir = file.parentFile
        if (windows) {
            val script = File(dir, "update.cmd")
            val install = cmdLine(installCommand(file, Paths.installDir))
            script.writeText(
                listOfNotNull("@echo off", "chcp 65001 >nul", "timeout /t 3 /nobreak >nul", install, launcher?.let { "start \"\" \"$it\"" })
                    .joinToString("\r\n") + "\r\n",
            )
            ProcessBuilder("cmd", "/c", script.absolutePath).start()
            return null
        }
        install(file, app)?.let { return it }
        if (launcher != null) {
            val script = File(dir, "relaunch.sh")
            script.writeText("#!/bin/sh\nsleep 3\nexec '${launcher.replace("'", "'\\''")}'\n")
            ProcessBuilder("setsid", "sh", script.absolutePath).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
        return null
    }

    private fun cmdLine(args: List<String>) = args.joinToString(" ") { if (it.contains(' ') && !it.contains('"')) "\"$it\"" else it }

    private fun JsonObject.text(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
    private fun JsonObject.bool(key: String) = text(key) == "true"
}
