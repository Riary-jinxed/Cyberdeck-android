package dev.netrunner.decklink

import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * The inventory of the PC Deck Link runs on, in the same shape as a Deck Probe report: on Windows
 * it runs the bundled agent with -Print (same fields as the fleet), on Linux it reads /sys, /proc
 * and /etc/os-release. Blocking: call it off the UI thread.
 */
object LocalProbe {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    fun run(): Fleet.Report = if (windows) windows() else linux()

    private fun windows(): Fleet.Report {
        val script = File.createTempFile("deck-probe", ".ps1")
        try {
            script.writeBytes(Fleet::class.java.getResourceAsStream("/agent/deck-probe.ps1")!!.use { it.readBytes() })
            // The Windows Update search inside can take up to two minutes.
            val out = exec("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.path, "-Print")
                ?: error("PowerShell n'a pas répondu")
            val line = out.lines().lastOrNull { it.trimStart().startsWith("{") } ?: error("PowerShell : ${out.trim().take(200)}")
            return Fleet.parse(line) ?: error("rapport illisible")
        } finally {
            script.delete()
        }
    }

    private fun linux(): Fleet.Report {
        fun read(path: String) = runCatching { File(path).readText().trim() }.getOrDefault("")
        fun dmi(name: String) = read("/sys/class/dmi/id/$name")
        val osRelease = read("/etc/os-release").lines().associate { it.substringBefore('=') to it.substringAfter('=').trim('"') }
        val kb = read("/proc/meminfo").lines().firstOrNull { it.startsWith("MemTotal:") }
            ?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
        return Fleet.Report(
            hostname = hostname(),
            domain = exec("hostname", "-d")?.trim().orEmpty(),
            user = System.getProperty("user.name").orEmpty(),
            manufacturer = dmi("sys_vendor"),
            model = listOf(dmi("product_name"), dmi("product_version")).filter { it.isNotBlank() && it.lowercase() !in Placeholders }.joinToString(" "),
            // Readable by root only on most distros: the sheet then matches by name.
            serial = dmi("product_serial"),
            os = osRelease["PRETTY_NAME"].orEmpty().ifEmpty { System.getProperty("os.name") + " " + System.getProperty("os.version") },
            osBuild = System.getProperty("os.version").orEmpty(),
            cpu = read("/proc/cpuinfo").lines().firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim().orEmpty(),
            ramGB = Math.round(kb / 1024.0 / 1024.0),
            disks = linuxDisks(),
            network = nics(),
            seen = System.currentTimeMillis(),
            software = linuxPackages(),
            updates = Fleet.Updates(rebootRequired = File("/var/run/reboot-required").exists()),
        )
    }

    private val Placeholders = setOf("to be filled by o.e.m.", "default string", "system product name", "not specified", "none")

    private val DiskTypes = setOf("ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "zfs", "vfat", "exfat", "ntfs", "ntfs3", "fuseblk")

    /** Mounted file systems on real disks, by mount point, with their free space (like the C:, D: of Windows). */
    private fun linuxDisks(): List<Fleet.Disk> = runCatching { read("/proc/mounts").lines() }.getOrDefault(emptyList())
        .map { it.split(' ') }
        .filter { it.size > 2 && it[2] in DiskTypes && it[0].startsWith("/dev/") }
        .distinctBy { it[0] }
        .mapNotNull { m ->
            val store = runCatching { java.nio.file.Files.getFileStore(java.nio.file.Path.of(m[1].replace("\\040", " "))) }.getOrNull() ?: return@mapNotNull null
            val size = runCatching { store.totalSpace }.getOrDefault(0L)
            if (size <= 0) return@mapNotNull null
            Fleet.Disk(m[1].replace("\\040", " "), Math.round(size / GB), (runCatching { store.usableSpace }.getOrDefault(0L) / GB).toLong())
        }
        .sortedBy { it.name }

    private const val GB = 1024.0 * 1024 * 1024

    /** Installed packages: dpkg (Debian, Ubuntu…) or rpm (Fedora, openSUSE…). */
    private fun linuxPackages(): List<Fleet.Software> {
        val dpkg = exec("dpkg-query", "-W", "-f", "\${Package}\t\${Version}\t\${Maintainer}\n")
        val rpm = if (dpkg == null) exec("rpm", "-qa", "--qf", "%{NAME}\t%{VERSION}-%{RELEASE}\t%{VENDOR}\n") else null
        return (dpkg ?: rpm).orEmpty().lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 2 || f[0].isBlank()) null else Fleet.Software(f[0], f[1], f.getOrElse(2) { "" }.substringBefore(" <"))
        }.sortedBy { it.name }
    }

    /** Cards that are up, with a MAC address and an IPv4 address. */
    private fun nics(): List<Fleet.Nic> = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        .mapNotNull { n ->
            val mac = runCatching { n.hardwareAddress }.getOrNull()?.takeIf { it.size == 6 } ?: return@mapNotNull null
            val ipv4 = n.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().joinToString(", ") { it.hostAddress }
            if (ipv4.isEmpty()) return@mapNotNull null
            Fleet.Nic(n.displayName.orEmpty().ifEmpty { n.name }, mac.joinToString(":") { "%02X".format(it) }, ipv4)
        }

    private fun hostname(): String = System.getenv("COMPUTERNAME")
        ?: System.getenv("HOSTNAME")
        ?: read("/etc/hostname").ifEmpty { null }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        ?: "PC"

    private fun read(path: String) = runCatching { File(path).readText().trim() }.getOrDefault("")

    /** Standard output of a command, or null when it fails or takes over three minutes. */
    private fun exec(vararg command: String): String? = runCatching {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        if (!p.waitFor(180, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() == 0) out else null
    }.getOrNull()
}
