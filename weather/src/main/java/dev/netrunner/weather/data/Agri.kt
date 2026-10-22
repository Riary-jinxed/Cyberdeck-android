package dev.netrunner.weather.data

import org.json.JSONObject
import kotlin.math.max

/**
 * Farming numbers from Open-Meteo: soil, evapotranspiration, water balance, frost, degree days
 * and spraying windows. Plain Kotlin + org.json, tested on the JVM.
 */
data class Agri(
    val now: Now,
    val days: List<Day>,
    /** The next 24 hours from now on. */
    val hours: List<Hour>,
) {
    /** Soil moisture in m³/m³, temperatures in °C, vapour pressure deficit in kPa. */
    data class Now(
        val soil0: Double, val soil6: Double, val soil18: Double,
        val moist1: Double, val moist3: Double, val moist9: Double, val moist27: Double,
        val vpd: Double, val humidity: Int, val wind: Double, val temp: Double,
    )
    data class Day(val date: String, val past: Boolean, val max: Double, val min: Double, val rain: Double, val et0: Double, val sunHours: Double)
    data class Hour(val time: String, val temp: Double, val rain: Double, val wind: Double, val humidity: Int)

    /** Rain minus evapotranspiration, in mm (negative: the soil dries out). */
    val balancePast: Double get() = days.filter { it.past }.sumOf { it.rain - it.et0 }
    val balanceNext: Double get() = days.filter { !it.past }.sumOf { it.rain - it.et0 }
    val rainPast: Double get() = days.filter { it.past }.sumOf { it.rain }
    val rainNext: Double get() = days.filter { !it.past }.sumOf { it.rain }
    val et0Next: Double get() = days.filter { !it.past }.sumOf { it.et0 }

    /** Growing degree days (base [base] °C) over the past week. */
    fun degreeDays(base: Double = 10.0): Double = days.filter { it.past }.sumOf { max(0.0, (it.max + it.min) / 2 - base) }

    /** Coming days whose night falls to 0 °C or below. */
    val frostDays: List<Day> get() = days.filter { !it.past && it.min <= 0.0 }
    /** Coming days at 0..3 °C: white frost is possible on the ground. */
    val coldDays: List<Day> get() = days.filter { !it.past && it.min > 0.0 && it.min <= 3.0 }

    /**
     * Hours fit for spraying: wind under 19 km/h (the legal limit in France), no rain then and
     * for the two hours after, between 5 and 25 °C.
     */
    fun sprayHours(): List<Hour> = hours.indices.filter { i ->
        val h = hours[i]
        h.wind < 19 && h.temp in 5.0..25.0 && (i until minOf(i + 3, hours.size)).all { hours[it].rain <= 0.0 }
    }.map { hours[it] }

    /** The first run of consecutive spray hours: start and end times ("06:00", "10:00"). */
    fun firstSprayWindow(): Pair<String, String>? {
        val ok = sprayHours().map { it.time }.toSet()
        val start = hours.indexOfFirst { it.time in ok }.takeIf { it >= 0 } ?: return null
        var end = start
        while (end + 1 < hours.size && hours[end + 1].time in ok) end++
        val endHour = (Sky.clock(hours[end].time).take(2).toIntOrNull() ?: 0) + 1
        return Sky.clock(hours[start].time) to "%02d:00".format(endHour % 24)
    }

    companion object {
        private const val HOURLY = "temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,vapour_pressure_deficit," +
            "soil_temperature_0cm,soil_temperature_6cm,soil_temperature_18cm," +
            "soil_moisture_0_to_1cm,soil_moisture_1_to_3cm,soil_moisture_3_to_9cm,soil_moisture_9_to_27cm"
        private const val DAILY = "temperature_2m_max,temperature_2m_min,precipitation_sum,et0_fao_evapotranspiration,sunshine_duration"

        fun url(lat: Double, lon: Double) =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m" +
                "&hourly=$HOURLY&daily=$DAILY&timezone=auto&past_days=7&forecast_days=7&wind_speed_unit=kmh"

        fun parse(json: String): Agri {
            val o = JSONObject(json)
            val nowTime = o.getJSONObject("current").optString("time").take(13)
            val h = o.getJSONObject("hourly")
            val times = h.getJSONArray("time")
            fun d(name: String, i: Int) = h.optJSONArray(name)?.optDouble(i) ?: Double.NaN
            val idx = (0 until times.length()).firstOrNull { times.getString(it).take(13) >= nowTime } ?: (times.length() - 1).coerceAtLeast(0)
            val now = Now(
                soil0 = d("soil_temperature_0cm", idx), soil6 = d("soil_temperature_6cm", idx), soil18 = d("soil_temperature_18cm", idx),
                moist1 = d("soil_moisture_0_to_1cm", idx), moist3 = d("soil_moisture_1_to_3cm", idx),
                moist9 = d("soil_moisture_3_to_9cm", idx), moist27 = d("soil_moisture_9_to_27cm", idx),
                vpd = d("vapour_pressure_deficit", idx), humidity = d("relative_humidity_2m", idx).let { if (it.isNaN()) 0 else it.toInt() },
                wind = d("wind_speed_10m", idx), temp = d("temperature_2m", idx),
            )
            val hours = (idx until minOf(idx + 24, times.length())).map { i ->
                Hour(times.getString(i), d("temperature_2m", i), d("precipitation", i).let { if (it.isNaN()) 0.0 else it }, d("wind_speed_10m", i), d("relative_humidity_2m", i).let { if (it.isNaN()) 0 else it.toInt() })
            }
            val daily = o.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val today = nowTime.take(10)
            fun dd(name: String, i: Int, fallback: Double = Double.NaN) = daily.optJSONArray(name)?.optDouble(i, fallback) ?: fallback
            val days = (0 until dates.length()).map { i ->
                val date = dates.getString(i)
                Day(
                    date, past = date < today, max = dd("temperature_2m_max", i), min = dd("temperature_2m_min", i),
                    rain = dd("precipitation_sum", i, 0.0), et0 = dd("et0_fao_evapotranspiration", i, 0.0),
                    sunHours = dd("sunshine_duration", i, 0.0) / 3600,
                )
            }
            return Agri(now, days, hours)
        }

        /** How wet the top soil is, from its volumetric water content (rough, depends on the soil). */
        fun moistureLabel(v: Double): String = when {
            v.isNaN() -> "—"
            v < 0.12 -> "Très sec"
            v < 0.20 -> "Sec"
            v < 0.30 -> "Frais"
            v < 0.38 -> "Humide"
            else -> "Saturé"
        }

        /** Vapour pressure deficit: low means wet leaves and disease risk, high means water stress. */
        fun vpdLabel(v: Double): String = when {
            v.isNaN() -> "—"
            v < 0.4 -> "Air saturé : risque de maladies"
            v < 1.2 -> "Confortable pour les cultures"
            v < 1.6 -> "Transpiration élevée"
            else -> "Stress hydrique"
        }

        /** One-line advice for the field, most urgent first. */
        fun advice(a: Agri): String = when {
            a.frostDays.isNotEmpty() -> "Gel annoncé (${Sky.temp(a.frostDays.minOf { it.min })}) : protège les cultures sensibles."
            a.balanceNext < -15 -> "Le sol va s'assécher (bilan ${"%+.0f".format(a.balanceNext)} mm sur 7 jours) : prévois l'arrosage."
            a.rainNext >= 40 -> "Fortes pluies à venir (${"%.0f".format(a.rainNext)} mm) : sols gorgés, reporte les travaux lourds."
            a.coldDays.isNotEmpty() -> "Nuits fraîches à ${Sky.temp(a.coldDays.minOf { it.min })} : gelée blanche possible au sol."
            a.now.soil6 < 8 -> "Sol froid (${Sky.temp(a.now.soil6)} à 6 cm) : semis lents à lever."
            else -> "Conditions correctes pour les travaux des champs."
        }
    }
}
