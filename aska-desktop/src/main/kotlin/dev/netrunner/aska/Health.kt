package dev.netrunner.aska

import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

/** How the PC is doing: processor, memory, disks, graphics card and temperatures when the system gives them. */
data class Health(
    val cpuPercent: Int?,
    val ramUsed: Long,
    val ramTotal: Long,
    val disks: List<Disk>,
    val gpu: Gpu?,
    /** Processor temperature in °C, when a sensor can be read (Linux, or LibreHardwareMonitor on Windows). */
    val cpuTemp: Int?,
) {
    data class Disk(val name: String, val free: Long, val total: Long) {
        val freePercent get() = if (total > 0) (free * 100 / total).toInt() else 100
    }

    data class Gpu(val name: String, val temp: Int?, val load: Int?, val memUsed: Long?, val memTotal: Long?)

    val ramPercent get() = if (ramTotal > 0) (ramUsed * 100 / ramTotal).toInt() else 0

    fun text(): String = buildString {
        cpuPercent?.let { append("Processeur : $it %") }
        cpuTemp?.let { append(" à $it °C") }
        if (cpuPercent != null || cpuTemp != null) appendLine()
        appendLine("Mémoire : ${gb(ramUsed)} / ${gb(ramTotal)} ($ramPercent %)")
        gpu?.let { g ->
            appendLine(
                "Carte graphique : ${g.name}" + listOfNotNull(
                    g.temp?.let { "$it °C" }, g.load?.let { "charge $it %" },
                    if (g.memUsed != null && g.memTotal != null) "VRAM ${gb(g.memUsed)} / ${gb(g.memTotal)}" else null,
                ).joinToString(", ", prefix = " : ").takeIf { it.length > 3 }.orEmpty(),
            )
        }
        disks.forEach { d -> appendLine("Disque ${d.name} : ${gb(d.free)} libres sur ${gb(d.total)} (${d.freePercent} %)") }
        if (cpuTemp == null && isWindows) append("Température du processeur : Windows ne la donne pas sans LibreHardwareMonitor lancé.")
    }.trim()

    /** What worries her, if anything, as « key|sentence ». */
    fun alerts(): List<String> = buildList {
        if (ramPercent >= 92) add("ram|La mémoire est pleine à $ramPercent %")
        disks.filter { it.total > 20_000_000_000 && (it.freePercent < 5 || it.free < 5_000_000_000) }
            .forEach { add("disk${it.name}|Le disque ${it.name} n'a plus que ${gb(it.free)} de libre") }
        gpu?.temp?.takeIf { it >= 85 }?.let { add("gpu|La carte graphique chauffe à $it °C") }
        cpuTemp?.takeIf { it >= 90 }?.let { add("cpu|Le processeur chauffe à $it °C") }
    }

    companion object {
        fun gb(bytes: Long) = String.format(Locale.FRENCH, "%.1f Go", bytes / 1e9)

        fun read(): Health {
            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            val cpu = os?.cpuLoad?.takeIf { it >= 0 }?.let { (it * 100).toInt() }
            val total = os?.totalMemorySize ?: 0L
            val free = os?.freeMemorySize ?: 0L
            return Health(cpu, total - free, total, disks(), gpu(), cpuTemp())
        }

        private fun disks(): List<Disk> =
            if (isWindows) File.listRoots().filter { it.totalSpace > 0 }.map { Disk(it.path.trimEnd('\\'), it.usableSpace, it.totalSpace) }
            else listOf(File("/"), File(System.getProperty("user.home"))).map { Disk(it.path, it.usableSpace, it.totalSpace) }
                .distinctBy { it.total to it.free }

        /** NVIDIA cards through nvidia-smi, which comes with the driver. */
        private fun gpu(): Gpu? {
            val out = run("nvidia-smi", "--query-gpu=name,temperature.gpu,utilization.gpu,memory.used,memory.total", "--format=csv,noheader,nounits") ?: return null
            val f = out.lines().first().split(",").map { it.trim() }
            if (f.size < 5) return null
            return Gpu(f[0], f[1].toIntOrNull(), f[2].toIntOrNull(), f[3].toLongOrNull()?.times(1_048_576), f[4].toLongOrNull()?.times(1_048_576))
        }

        /** Null once the system turned out not to give it: no point asking every minute. */
        @Volatile private var lhm: Boolean? = null

        private fun cpuTemp(): Int? = if (isWindows) windowsTemp() else linuxTemp()

        private fun linuxTemp(): Int? {
            // coretemp (Intel) or k10temp (AMD) first, else the thermal zone of the package.
            File("/sys/class/hwmon").listFiles()?.forEach { mon ->
                val name = runCatching { File(mon, "name").readText().trim() }.getOrNull()
                if (name in setOf("coretemp", "k10temp", "zenpower")) {
                    runCatching { File(mon, "temp1_input").readText().trim().toInt() / 1000 }.getOrNull()?.let { return it }
                }
            }
            return File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }
                ?.firstOrNull { runCatching { File(it, "type").readText().trim() }.getOrNull() in setOf("x86_pkg_temp", "cpu-thermal", "acpitz") }
                ?.let { runCatching { File(it, "temp").readText().trim().toInt() / 1000 }.getOrNull() }
        }

        /** LibreHardwareMonitor, when it runs, publishes its sensors through WMI. */
        private fun windowsTemp(): Int? {
            if (lhm == false) return null
            val out = run(
                "powershell", "-NoProfile", "-Command",
                "Get-CimInstance -Namespace root/LibreHardwareMonitor -ClassName Sensor -ErrorAction Stop | " +
                    "Where-Object { \$_.SensorType -eq 'Temperature' -and (\$_.Name -match 'Package|Tctl|Core \\(Tctl') } | " +
                    "Select-Object -First 1 -ExpandProperty Value",
                seconds = 6,
            )
            val t = out?.lines()?.firstOrNull()?.replace(',', '.')?.toDoubleOrNull()?.toInt()
            if (lhm == null) lhm = t != null
            return t
        }

        private fun run(vararg command: String, seconds: Long = 4): String? = runCatching {
            val p = ProcessBuilder(*command).redirectErrorStream(true).start()
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            if (p.exitValue() != 0) return null
            p.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
