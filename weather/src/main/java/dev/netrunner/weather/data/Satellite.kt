package dev.netrunner.weather.data

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.cos

/**
 * Meteosat Third Generation pictures from EUMETView, EUMETSAT's free map service (WMS, no key).
 * Plain Kotlin, tested on the JVM.
 */
object Satellite {
    private const val WMS = "https://view.eumetsat.int/geoserver/wms"

    enum class Product(val label: String, val layer: String, val detail: String) {
        COLOUR("COULEUR", "mtg_fd:rgb_geocolour", "Vraies couleurs le jour, nuages infrarouges la nuit"),
        INFRARED("INFRAROUGE", "mtg_fd:ir105_hrfi", "Température des sommets nuageux : plus c'est blanc, plus c'est haut et froid"),
        FOG("BROUILLARD", "mtg_fd:rgb_fog", "Brouillard et nuages bas, utile au petit matin"),
    }

    /** Half the height of the picture, in degrees of latitude; EUROPE is fixed on the continent. */
    enum class Area(val label: String, val halfLat: Double) {
        LOCAL("LOCAL", 1.6), REGION("RÉGION", 4.5), EUROPE("EUROPE", 17.0),
    }

    data class Box(val west: Double, val south: Double, val east: Double, val north: Double) {
        /** Where [lat], [lon] falls in the picture, 0..1 from the left and from the top. */
        fun locate(lat: Double, lon: Double): Pair<Float, Float> =
            ((lon - west) / (east - west)).toFloat() to ((north - lat) / (north - south)).toFloat()
    }

    /**
     * A box around the place, [aspect] wide for 1 high, in plain lat/lon degrees: longitudes are
     * stretched by 1/cos(latitude) so that the picture is not squashed.
     */
    fun box(lat: Double, lon: Double, area: Area, aspect: Double = 1.0): Box {
        val cLat = if (area == Area.EUROPE) 50.0 else lat
        val cLon = if (area == Area.EUROPE) 10.0 else lon
        val halfLon = area.halfLat * aspect / cos(Math.toRadians(cLat)).coerceAtLeast(0.2)
        return Box(cLon - halfLon, (cLat - area.halfLat).coerceAtLeast(-80.0), cLon + halfLon, (cLat + area.halfLat).coerceAtMost(80.0))
    }

    private fun bbox(b: Box) = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.ROOT, b.west, b.south, b.east, b.north)

    /** WMS 1.1.1 keeps lon,lat order in EPSG:4326 boxes. */
    fun imageUrl(product: Product, b: Box, width: Int, height: Int, time: String?): String =
        "$WMS?service=WMS&version=1.1.1&request=GetMap&layers=${product.layer}&styles=&srs=EPSG:4326" +
            "&bbox=${bbox(b)}&width=$width&height=$height&format=image/jpeg" + (time?.let { "&time=$it" } ?: "")

    /** Coastlines and borders in white, on a transparent background. */
    fun overlayUrl(b: Box, width: Int, height: Int, styled: Boolean = true): String =
        "$WMS?service=WMS&version=1.1.1&request=GetMap&layers=backgrounds:ne_10m_coastline,backgrounds:ne_boundary_lines_land" +
            "&styles=${if (styled) "whitelines,whitelines" else ","}&srs=EPSG:4326&bbox=${bbox(b)}&width=$width&height=$height" +
            "&format=image/png&transparent=true"

    fun capabilitiesUrl(product: Product): String {
        val (ws, name) = product.layer.split(':')
        return "https://view.eumetsat.int/geoserver/$ws/$name/ows?service=WMS&version=1.3.0&request=GetCapabilities"
    }

    private val RANGE = Regex("""(\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d(?:\.\d+)?)?Z)/(\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d(?:\.\d+)?)?Z)/PT(\d+)M""")

    /** The newest picture time and the step between pictures (minutes), from a GetCapabilities document. */
    fun latest(capabilities: String): Pair<Instant, Int>? {
        val m = RANGE.findAll(capabilities).lastOrNull() ?: return null
        return runCatching { Instant.parse(m.groupValues[2]) to m.groupValues[3].toInt() }.getOrNull()
    }

    /** When the service can't say: half an hour ago, on a 10-minute mark. */
    fun guessLatest(now: Instant): Instant = now.minus(30, ChronoUnit.MINUTES).let { it.truncatedTo(ChronoUnit.HOURS).plus((it.atZone(java.time.ZoneOffset.UTC).minute / 10 * 10).toLong(), ChronoUnit.MINUTES) }

    /** [count] picture times ending at [latest], [every] minutes apart, oldest first. */
    fun frames(latest: Instant, count: Int, every: Int): List<String> =
        (count - 1 downTo 0).map { latest.minus((it * every).toLong(), ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES).toString() }
}
