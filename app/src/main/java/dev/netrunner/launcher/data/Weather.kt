package dev.netrunner.launcher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ZoneConditions(
    val tempC: Double,
    val feelsC: Double,
    val code: Int,
    val windKmh: Double,
    val humidity: Int,
    val isDay: Boolean,
    val fetchedAt: Long,
    /** The next few hours, soonest first (may be empty). */
    val hours: List<HourForecast> = emptyList(),
) {
    /** WMO weather code as a Night City status line. */
    val label: String
        get() = when (code) {
            0 -> if (isDay) "CIEL DÉGAGÉ" else "NUIT CLAIRE"
            1, 2 -> "SMOG LÉGER"
            3 -> "COUVERTURE TOTALE"
            45, 48 -> "BROUILLARD TOXIQUE"
            in 51..57 -> "BRUINE ACIDE"
            in 61..67 -> "PLUIE ACIDE"
            in 71..77 -> "NEIGE RADIOACTIVE"
            in 80..82 -> "AVERSES"
            85, 86 -> "AVERSES DE NEIGE"
            in 95..99 -> "ORAGE ÉLECTRIQUE"
            else -> "CONDITIONS INCONNUES"
        }

    val icon: String get() = weatherIcon(code, isDay)
}

/** One hour of forecast: local hour (0-23), temperature and WMO code. */
data class HourForecast(val hour: Int, val tempC: Double, val code: Int, val isDay: Boolean) {
    val icon: String get() = weatherIcon(code, isDay)
}

private fun weatherIcon(code: Int, isDay: Boolean) = when (code) {
    0 -> if (isDay) "☀" else "☾"
    1, 2, 3 -> "☁"
    45, 48 -> "≋"
    in 51..67, in 80..82 -> "☂"
    in 71..77, 85, 86 -> "❄"
    in 95..99 -> "ϟ"
    else -> "?"
}

/**
 * Current weather from Open-Meteo (free, no key, no account). Uses the last known coarse
 * location, and caches the result for 30 minutes so the HUD makes at most two requests an hour.
 */
object Weather {
    private const val TTL = 30 * 60_000L
    private var cache: ZoneConditions? = null

    fun granted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Blocking: call from a background dispatcher. */
    fun current(context: Context): ZoneConditions? {
        cache?.let { if (System.currentTimeMillis() - it.fetchedAt < TTL) return it }
        val location = lastLocation(context) ?: return cache
        return runCatching { fetch(location.latitude, location.longitude) }
            .onSuccess { cache = it }
            .getOrNull() ?: cache
    }

    @Suppress("MissingPermission")
    private fun lastLocation(context: Context): Location? {
        if (!granted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun fetch(lat: Double, lon: Double): ZoneConditions {
        val url = URL(
            String.format(
                Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day" +
                    "&hourly=temperature_2m,weather_code,is_day&forecast_hours=5&timezone=auto",
                lat, lon,
            )
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            return ZoneConditions(
                tempC = current.getDouble("temperature_2m"),
                feelsC = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
                code = current.getInt("weather_code"),
                windKmh = current.optDouble("wind_speed_10m", 0.0),
                humidity = current.optInt("relative_humidity_2m", 0),
                isDay = current.optInt("is_day", 1) == 1,
                fetchedAt = System.currentTimeMillis(),
                hours = runCatching { hours(json, current.optString("time")) }.getOrDefault(emptyList()),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** The next [count] full hours after [now] (local ISO time, as Open-Meteo returns with timezone=auto). */
    private fun hours(json: JSONObject, now: String, count: Int = 3): List<HourForecast> {
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val days = hourly.optJSONArray("is_day")
        return (0 until times.length())
            .filter { times.getString(it) > now }
            .take(count)
            .map {
                HourForecast(
                    hour = times.getString(it).substring(11, 13).toInt(),
                    tempC = temps.getDouble(it),
                    code = codes.getInt(it),
                    isDay = (days?.optInt(it, 1) ?: 1) == 1,
                )
            }
    }
}
