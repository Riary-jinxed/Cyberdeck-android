package dev.netrunner.aska

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The runner's Steam library: games, time played, last played. Read from Steam's own files, nothing sent. */
object Steam {
    data class Game(val id: String, val name: String, val minutes: Long, val twoWeeks: Long, val lastPlayed: Long)

    fun home(): File? {
        val candidates = if (isWindows) listOfNotNull(
            runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam", "SteamPath") }.getOrNull(),
            System.getenv("ProgramFiles(x86)")?.let { "$it/Steam" },
        ) else listOf("~/.steam/steam", "~/.local/share/Steam", "~/.var/app/com.valvesoftware.Steam/.local/share/Steam")
            .map { it.replaceFirst("~", System.getProperty("user.home")) }
        return candidates.map { File(it) }.firstOrNull { File(it, "userdata").isDirectory }
    }

    fun games(home: File? = home()): List<Game> {
        home ?: return emptyList()
        val names = names(home)
        val byId = HashMap<String, Game>()
        File(home, "userdata").listFiles().orEmpty().forEach { user ->
            val config = File(user, "config/localconfig.vdf").takeIf { it.isFile } ?: return@forEach
            val apps = Vdf.parse(config.readText()).path("UserLocalConfigStore", "Software", "Valve", "Steam", "apps") ?: return@forEach
            apps.forEach { (id, v) ->
                val app = v as? Map<*, *> ?: return@forEach
                fun n(key: String) = (app[key] as? String)?.toLongOrNull() ?: 0L
                val g = Game(id, names[id] ?: "jeu $id", n("Playtime"), n("Playtime2wks"), n("LastPlayed"))
                if (g.minutes > 0 || g.lastPlayed > 0) byId.merge(id, g) { a, b -> if (a.lastPlayed >= b.lastPlayed) a else b }
            }
        }
        return byId.values.sortedByDescending { it.lastPlayed }
    }

    /** Installed games' names, from the appmanifest of every library. */
    private fun names(home: File): Map<String, String> {
        val libraries = mutableListOf(File(home, "steamapps"))
        File(home, "steamapps/libraryfolders.vdf").takeIf { it.isFile }?.let { f ->
            Vdf.parse(f.readText()).path("libraryfolders")?.values?.forEach { lib ->
                ((lib as? Map<*, *>)?.get("path") as? String)?.let { libraries += File(it, "steamapps") }
            }
        }
        return libraries.distinct().flatMap { dir -> dir.listFiles { f -> f.name.startsWith("appmanifest_") }.orEmpty().toList() }
            .mapNotNull { f -> Vdf.parse(f.readText()).path("AppState")?.let { (it["appid"] as? String ?: return@mapNotNull null) to (it["name"] as? String ?: return@mapNotNull null) } }
            .toMap()
    }

    fun text(games: List<Game> = games(), today: LocalDate = LocalDate.now()): String {
        if (games.isEmpty()) return "Je ne trouve pas Steam sur ce PC (ou aucun jeu joué)."
        val recent = games.take(8).joinToString("\n") { g ->
            val last = if (g.lastPlayed > 0) Instant.ofEpochSecond(g.lastPlayed).atZone(ZoneId.systemDefault()).toLocalDate() else null
            "- ${g.name} : ${hours(g.minutes)} au total" + (if (g.twoWeeks > 0) ", ${hours(g.twoWeeks)} ces deux semaines" else "") +
                (last?.let { ", joué " + when (it) { today -> "aujourd'hui"; today.minusDays(1) -> "hier"; else -> "le $it" } } ?: "")
        }
        val most = games.sortedByDescending { it.minutes }.take(3).joinToString(", ") { "${it.name} (${hours(it.minutes)})" }
        return "Joués récemment :\n$recent\nLes plus joués : $most"
    }

    fun hours(minutes: Long) = if (minutes < 60) "$minutes min" else "${minutes / 60} h" + (minutes % 60).let { if (it > 0) " %02d".format(it) else "" }
}

/** Valve's KeyValues text format: "key" "value" and "key" { … }. */
object Vdf {
    fun parse(text: String): Map<String, Any> {
        val tokens = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"|([{}])").findAll(text).map { it.groupValues[1].takeIf { _ -> it.groupValues[2].isEmpty() } ?: it.groupValues[2] }.iterator()
        // The value of a quoted token is the token itself; braces arrive as « { » and « } » (never quoted in these files).
        fun block(): Map<String, Any> {
            val map = LinkedHashMap<String, Any>()
            while (tokens.hasNext()) {
                val key = tokens.next()
                if (key == "}") return map
                if (!tokens.hasNext()) break
                val value = tokens.next()
                map[key] = if (value == "{") block() else value.replace("\\\\", "\\")
            }
            return map
        }
        return block()
    }
}

/** Follows the keys of a parsed VDF, ignoring case. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any>.path(vararg keys: String): Map<String, Any>? {
    var at: Map<String, Any> = this
    for (k in keys) at = at.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value as? Map<String, Any> ?: return null
    return at
}
