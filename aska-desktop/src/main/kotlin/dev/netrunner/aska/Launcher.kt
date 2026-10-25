package dev.netrunner.aska

import java.io.File

/** Opens a program of the Start menu (or of the Linux applications menu) by its name. */
object Launcher {
    data class Entry(val name: String, val file: File)

    /** Start menu shortcuts (Steam puts its games there too), or .desktop files. */
    fun entries(): List<Entry> = if (isWindows) {
        listOfNotNull(System.getenv("APPDATA"), System.getenv("ProgramData"))
            .map { File(it, "Microsoft/Windows/Start Menu/Programs") }
            .flatMap { dir -> dir.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.lowercase() in setOf("lnk", "url") }.toList() }
            .map { Entry(it.nameWithoutExtension, it) }
            .filterNot { DeckLink.normalize(it.name).let { n -> n.startsWith("uninstall") || n.startsWith("desinstaller") || n.contains("readme") } }
    } else {
        listOf(File("/usr/share/applications"), File(System.getProperty("user.home"), ".local/share/applications"), File("/var/lib/flatpak/exports/share/applications"))
            .flatMap { dir -> dir.listFiles { f -> f.extension == "desktop" }.orEmpty().toList() }
            .mapNotNull { f ->
                val lines = runCatching { f.readLines() }.getOrNull() ?: return@mapNotNull null
                if (lines.any { it.trim() == "NoDisplay=true" }) return@mapNotNull null
                lines.firstOrNull { it.startsWith("Name=") }?.removePrefix("Name=")?.let { Entry(it, f) }
            }
    }.distinctBy { DeckLink.normalize(it.name) }

    /** The entries that best match [query]: an exact name first, then a name that starts with it, then one that contains it. */
    fun find(query: String, all: List<Entry> = entries()): List<Entry> {
        val q = DeckLink.normalize(query).trim()
        if (q.isEmpty()) return emptyList()
        val scored = all.mapNotNull { e ->
            val n = DeckLink.normalize(e.name)
            when {
                n == q -> 0
                n.startsWith(q) -> 1
                n.split(Regex("[^a-z0-9]+")).any { it.startsWith(q) } -> 2
                n.contains(q) -> 3
                q.split(" ").all { n.contains(it) } -> 4
                else -> null
            }?.let { it to e }
        }
        val best = scored.minOfOrNull { it.first } ?: return emptyList()
        return scored.filter { it.first == best }.map { it.second }.sortedBy { it.name.length }
    }

    /** Launches the program and says what happened. */
    fun launch(query: String): String {
        val found = find(query)
        if (found.isEmpty()) return "Aucun programme du menu Démarrer ne s'appelle « $query »."
        val exact = DeckLink.normalize(found[0].name) == DeckLink.normalize(query).trim()
        if (!exact && found.size > 6) return "Trop de programmes correspondent à « $query », il faut préciser : " + found.take(6).joinToString(", ") { it.name } + "…"
        val entry = found.first()
        val command = if (isWindows) listOf("cmd", "/c", "start", "", entry.file.absolutePath) else listOf("gtk-launch", entry.file.nameWithoutExtension)
        ProcessBuilder(command).redirectErrorStream(true).start()
        return "Lancé : ${entry.name}." + if (found.size > 1) " (Autres possibilités : " + found.drop(1).take(4).joinToString(", ") { it.name } + ")" else ""
    }
}
