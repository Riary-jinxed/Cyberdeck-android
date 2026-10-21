package dev.netrunner.weather.data

import org.json.JSONObject
import kotlin.math.roundToInt

/** A forecast from Open-Meteo, parsed from its JSON. Plain Kotlin + org.json, tested on the JVM. */
data class Forecast(
    val place: String,
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
    val fetched: Long,
) {
    data class Current(
        val temp: Double, val feels: Double, val humidity: Int, val wind: Double, val windDir: Int, val gusts: Double,
        val pressure: Double, val precip: Double, val code: Int, val isDay: Boolean, val time: String,
    )
    /** [time] is local ISO "2026-10-21T15:00". */
    data class Hour(val time: String, val temp: Double, val rainChance: Int, val code: Int, val isDay: Boolean)
    data class Day(val date: String, val code: Int, val max: Double, val min: Double, val rainChance: Int, val rain: Double, val sunrise: String, val sunset: String, val uv: Double)

    companion object {
        const val FIELDS_CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
        const val FIELDS_HOURLY = "temperature_2m,precipitation_probability,weather_code,is_day"
        const val FIELDS_DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,sunrise,sunset,uv_index_max"

        fun url(lat: Double, lon: Double) =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=$FIELDS_CURRENT" +
                "&hourly=$FIELDS_HOURLY&daily=$FIELDS_DAILY&timezone=auto&forecast_days=7&wind_speed_unit=kmh"

        /** Keeps the next 24 hours from the current time on. */
        fun parse(json: String, place: String, fetched: Long): Forecast {
            val o = JSONObject(json)
            val c = o.getJSONObject("current")
            val current = Current(
                temp = c.optDouble("temperature_2m"), feels = c.optDouble("apparent_temperature"),
                humidity = c.optInt("relative_humidity_2m"), wind = c.optDouble("wind_speed_10m"), windDir = c.optInt("wind_direction_10m"),
                gusts = c.optDouble("wind_gusts_10m", 0.0), pressure = c.optDouble("pressure_msl"), precip = c.optDouble("precipitation", 0.0),
                code = c.optInt("weather_code"), isDay = c.optInt("is_day", 1) == 1, time = c.optString("time"),
            )
            val h = o.getJSONObject("hourly")
            val times = h.getJSONArray("time")
            val all = (0 until times.length()).map { i ->
                Hour(
                    times.getString(i), h.getJSONArray("temperature_2m").optDouble(i),
                    h.getJSONArray("precipitation_probability").optInt(i), h.getJSONArray("weather_code").optInt(i),
                    h.getJSONArray("is_day").optInt(i, 1) == 1,
                )
            }
            val hourNow = current.time.take(13) // "2026-10-21T15"
            val start = all.indexOfFirst { it.time.take(13) >= hourNow }.coerceAtLeast(0)
            val d = o.getJSONObject("daily")
            val dates = d.getJSONArray("time")
            val days = (0 until dates.length()).map { i ->
                Day(
                    dates.getString(i), d.getJSONArray("weather_code").optInt(i),
                    d.getJSONArray("temperature_2m_max").optDouble(i), d.getJSONArray("temperature_2m_min").optDouble(i),
                    d.getJSONArray("precipitation_probability_max").optInt(i), d.getJSONArray("precipitation_sum").optDouble(i, 0.0),
                    d.getJSONArray("sunrise").optString(i), d.getJSONArray("sunset").optString(i), d.getJSONArray("uv_index_max").optDouble(i, 0.0),
                )
            }
            return Forecast(place, current, all.drop(start).take(24), days, fetched)
        }
    }
}

/** WMO weather codes, wind and formatting, in French. */
object Sky {
    /** Label and glyph for a WMO code. */
    fun describe(code: Int, day: Boolean = true): Pair<String, String> = when (code) {
        0 -> "Ciel dégagé" to if (day) "☀" else "☾"
        1 -> "Peu nuageux" to if (day) "🌤" else "☾"
        2 -> "Partiellement nuageux" to "⛅"
        3 -> "Couvert" to "☁"
        45, 48 -> "Brouillard" to "≋"
        51, 53, 55 -> "Bruine" to "☂"
        56, 57 -> "Bruine verglaçante" to "☂"
        61 -> "Pluie faible" to "☂"
        63 -> "Pluie" to "☔"
        65 -> "Forte pluie" to "☔"
        66, 67 -> "Pluie verglaçante" to "☔"
        71 -> "Neige faible" to "❄"
        73 -> "Neige" to "❄"
        75 -> "Forte neige" to "❄"
        77 -> "Grains de neige" to "❄"
        80 -> "Averses faibles" to "☂"
        81 -> "Averses" to "☔"
        82 -> "Violentes averses" to "☔"
        85, 86 -> "Averses de neige" to "❄"
        95 -> "Orage" to "⚡"
        96, 99 -> "Orage et grêle" to "⚡"
        else -> "Inconnu" to "?"
    }

    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    /** Where the wind comes from: 0° → "N", 225° → "SO". */
    fun compass(degrees: Int): String = COMPASS[(((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

    fun temp(v: Double): String = if (v.isNaN()) "—" else "${v.roundToInt()}°"

    /** "2026-10-21T07:58" → "07:58". */
    fun clock(iso: String): String = iso.substringAfter('T', iso).take(5)

    /** Mission brief tone: a one-line advice from the day's numbers. */
    fun advice(day: Forecast.Day, current: Forecast.Current): String = when {
        day.code >= 95 -> "Orages prévus : reste à couvert."
        day.rainChance >= 60 -> "Pluie probable (${day.rainChance} %) : prends de quoi te couvrir."
        current.wind >= 50 || current.gusts >= 70 -> "Vent fort : attention aux rafales."
        day.max >= 30 -> "Forte chaleur : hydrate-toi."
        day.min <= 0 -> "Gel possible : couvre-toi."
        day.uv >= 6 -> "UV élevés : protège-toi du soleil."
        else -> "Conditions favorables pour la mission."
    }
}
