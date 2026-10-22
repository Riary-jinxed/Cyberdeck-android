package dev.netrunner.launcher.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** A reading taken at a spot of the plan: [x], [y] are fractions of the plan's width and height. */
data class SurveyPoint(val x: Float, val y: Float, val rssi: Int, val ssid: String, val bssid: String, val frequencyMhz: Int, val at: Long)

/** A Wi-Fi coverage survey: a floor plan photo and the readings placed on it. */
data class Survey(val id: String, val site: String, val name: String, val created: Long, val points: List<SurveyPoint>)

/**
 * Wi-Fi coverage survey on a floor plan. Each reading is the signal of the phone's own Wi-Fi
 * connection where the technician stands, as Android already reports it: nothing is sent on
 * the network. The heat map is interpolated between the readings.
 */
object Coverage {
    val items = mutableStateListOf<Survey>()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "kit_coverage.json")

    fun plan(context: Context, id: String) = File(File(File(context.filesDir, "coverage"), id), "plan.jpg")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val a = JSONObject(f.readText()).optJSONArray("surveys") ?: return
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val pts = o.optJSONArray("points")?.let { p ->
                    (0 until p.length()).map { p.getJSONObject(it) }.map {
                        SurveyPoint(
                            it.optDouble("x").toFloat(), it.optDouble("y").toFloat(), it.optInt("rssi"), it.optString("ssid"),
                            it.optString("bssid"), it.optInt("freq"), it.optLong("at"),
                        )
                    }
                }.orEmpty()
                items += Survey(o.getString("id"), o.optString("site"), o.optString("name"), o.optLong("created"), pts)
            }
        }
    }

    private fun save(context: Context) {
        val root = JSONObject().put("surveys", JSONArray(items.map { s ->
            JSONObject().put("id", s.id).put("site", s.site).put("name", s.name).put("created", s.created)
                .put("points", JSONArray(s.points.map { p ->
                    JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("rssi", p.rssi).put("ssid", p.ssid)
                        .put("bssid", p.bssid).put("freq", p.frequencyMhz).put("at", p.at)
                }))
        }))
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
        }
    }

    fun get(id: String?) = items.firstOrNull { it.id == id }

    /** Copies the plan picture (downscaled to 2000 px) and creates the survey. Blocking. */
    fun create(context: Context, site: String, name: String, planUri: Uri): Survey? {
        val id = KitStore.newId()
        val bitmap = Logbook.decode(context, planUri, 2000) ?: return null
        val out = plan(context, id)
        out.parentFile?.mkdirs()
        val ok = runCatching { out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) } }.getOrDefault(false)
        bitmap.recycle()
        if (!ok) return null
        val s = Survey(id, site.trim(), name.trim(), System.currentTimeMillis(), emptyList())
        items.add(0, s)
        save(context)
        return s
    }

    fun delete(context: Context, id: String) {
        items.removeAll { it.id == id }
        plan(context, id).parentFile?.deleteRecursively()
        save(context)
    }

    private fun edit(context: Context, id: String, change: (Survey) -> Survey) {
        val k = items.indexOfFirst { it.id == id }
        if (k < 0) return
        items[k] = change(items[k])
        save(context)
    }

    fun addPoint(context: Context, id: String, p: SurveyPoint) = edit(context, id) { it.copy(points = it.points + p) }

    fun removePoint(context: Context, id: String, p: SurveyPoint) = edit(context, id) { it.copy(points = it.points - p) }

    data class Reading(val rssi: Int, val ssid: String, val bssid: String, val frequencyMhz: Int)

    /**
     * Signal of the current Wi-Fi connection, averaged over three samples about half a second
     * apart to smooth the jitter. Null when the phone is not on Wi-Fi.
     */
    @Suppress("DEPRECATION")
    suspend fun measure(context: Context): Reading? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val samples = mutableListOf<Int>()
        var last: android.net.wifi.WifiInfo? = null
        repeat(3) { k ->
            if (k > 0) delay(500)
            val info = wifi.connectionInfo
            if (info != null && info.networkId != -1 && info.rssi > -127) {
                samples += info.rssi
                last = info
            }
        }
        val info = last ?: return null
        val ssid = info.ssid.orEmpty().removeSurrounding("\"").takeIf { it != "<unknown ssid>" }.orEmpty()
        return Reading(samples.average().toInt(), ssid, info.bssid.orEmpty(), info.frequency)
    }

    /** Colour of a signal level, from red (no service) to green (excellent). */
    fun color(rssi: Int): Int = when {
        rssi >= -55 -> Color.rgb(39, 174, 96)
        rssi >= -62 -> Color.rgb(140, 200, 60)
        rssi >= -67 -> Color.rgb(241, 196, 15)
        rssi >= -72 -> Color.rgb(243, 156, 18)
        rssi >= -80 -> Color.rgb(230, 100, 30)
        else -> Color.rgb(192, 57, 43)
    }

    /** Last two bytes of a BSSID, to tell access points apart on the map. */
    fun apTag(bssid: String) = bssid.split(':').takeLast(2).joinToString(":").uppercase()

    /**
     * Plan with the interpolated heat map (inverse distance weighting), the readings and a
     * legend. [ssid] limits the map to one network when the survey mixes several.
     */
    fun render(context: Context, survey: Survey, ssid: String?): Bitmap? {
        val base = BitmapFactory.decodeFile(plan(context, survey.id).path) ?: return null
        val legendH = 120
        val out = Bitmap.createBitmap(base.width, base.height + legendH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(base, 0f, 0f, null)
        val w = base.width
        val h = base.height
        base.recycle()
        val points = survey.points.filter { ssid == null || it.ssid == ssid }
        if (points.isNotEmpty()) {
            // Coarse grid, each cell coloured from the readings around it.
            val cell = maxOf(8, maxOf(w, h) / 90)
            val reach = maxOf(w, h) * 0.22f
            val paint = Paint()
            var gy = 0
            while (gy < h) {
                var gx = 0
                while (gx < w) {
                    val cx = gx + cell / 2f
                    val cy = gy + cell / 2f
                    var num = 0.0
                    var den = 0.0
                    var nearest = Float.MAX_VALUE
                    for (p in points) {
                        val dx = p.x * w - cx
                        val dy = p.y * h - cy
                        val d = sqrt(dx * dx + dy * dy)
                        nearest = minOf(nearest, d)
                        val weight = 1.0 / (d * d + cell * cell)
                        num += p.rssi * weight
                        den += weight
                    }
                    if (nearest < reach) {
                        val fade = 1f - (nearest / reach)
                        paint.color = color((num / den).toInt())
                        paint.alpha = (40 + 90 * fade).toInt()
                        c.drawRect(gx.toFloat(), gy.toFloat(), (gx + cell).toFloat(), (gy + cell).toFloat(), paint)
                    }
                    gx += cell
                }
                gy += cell
            }
            val radius = maxOf(14f, maxOf(w, h) / 70f)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = radius * 1.1f; typeface = Typeface.DEFAULT_BOLD }
            val halo = Paint(label).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f }
            val multiAp = points.map { it.bssid }.distinct().size > 1
            points.forEach { p ->
                val px = p.x * w
                val py = p.y * h
                dot.color = color(p.rssi)
                c.drawCircle(px, py, radius, dot)
                c.drawCircle(px, py, radius, ring)
                val t = "${p.rssi}" + if (multiAp && p.bssid.isNotBlank()) " · ${apTag(p.bssid)}" else ""
                c.drawText(t, px + radius + 6, py + radius / 2, halo)
                c.drawText(t, px + radius + 6, py + radius / 2, label)
            }
        }
        // Legend.
        val top = h.toFloat()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 34, 40); textSize = 26f }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val title = listOf("Couverture Wi-Fi", survey.site, survey.name, ssid ?: points.map { it.ssid }.distinct().filter { it.isNotBlank() }.joinToString(", "))
            .filter { it.isNotBlank() }.joinToString(" · ")
        c.drawText(title, 20f, top + 38, bold)
        val steps = listOf(-50 to "≥ -55", -60 to "-62", -65 to "-67", -70 to "-72", -77 to "-80", -85 to "< -80")
        val bw = (w - 40f) / steps.size
        steps.forEachIndexed { k, (v, l) ->
            val r = RectF(20 + k * bw, top + 56, 20 + (k + 1) * bw - 6, top + 80)
            c.drawRect(r, Paint().apply { color = color(v) })
            c.drawText("$l dBm", r.left, top + 108, text)
        }
        return out
    }
}
