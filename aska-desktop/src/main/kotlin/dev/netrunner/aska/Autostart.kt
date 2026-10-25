package dev.netrunner.aska

import java.io.File

/** Starts her with the session: the Run key on Windows, ~/.config/autostart on Linux. */
object Autostart {
    /** The installed launcher; null when run from Gradle. */
    private val launcher: String? = System.getProperty("jpackage.app-path")

    val available get() = launcher != null

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    private val desktopFile get() = File(System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "autostart/aska.desktop")

    fun set(on: Boolean): Boolean = runCatching {
        val app = launcher ?: return false
        if (isWindows) {
            val command = if (on) listOf("reg", "add", RUN_KEY, "/v", "Aska", "/t", "REG_SZ", "/d", "\"$app\"", "/f")
            else listOf("reg", "delete", RUN_KEY, "/v", "Aska", "/f")
            ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0 || !on
        } else {
            if (on) {
                desktopFile.parentFile.mkdirs()
                desktopFile.writeText(
                    """
                    [Desktop Entry]
                    Type=Application
                    Name=As//ka
                    Comment=La compagne du deck, sur le bureau
                    Exec="$app"
                    X-GNOME-Autostart-enabled=true
                    """.trimIndent() + "\n",
                )
            } else {
                desktopFile.delete()
            }
            true
        }
    }.getOrDefault(false)
}
