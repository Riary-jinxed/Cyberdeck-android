package dev.netrunner.weather.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/** Network, position and the cached last forecast. */
object WeatherRepo {
    private fun prefs(context: Context) = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("Erreur ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun forecast(context: Context, place: Place): Forecast {
        val json = get(Forecast.url(place.lat, place.lon))
        val now = System.currentTimeMillis()
        val f = Forecast.parse(json, place.name, now)
        prefs(context).edit().putString("cache_json", json).putString("cache_place", place.name).putLong("cache_time", now).apply()
        return f
    }

    /** The last forecast fetched, to show something offline. */
    fun cached(context: Context): Forecast? {
        val p = prefs(context)
        val json = p.getString("cache_json", null) ?: return null
        return runCatching { Forecast.parse(json, p.getString("cache_place", "").orEmpty(), p.getLong("cache_time", 0)) }.getOrNull()
    }

    suspend fun search(query: String): List<Place> = if (query.isBlank()) emptyList() else Place.parseSearch(get(Place.searchUrl(query)))

    fun saved(context: Context): List<Place> = Place.listFromJson(prefs(context).getString("places", "[]").orEmpty())
    fun setSaved(context: Context, places: List<Place>) = prefs(context).edit().putString("places", Place.listToJson(places)).apply()

    /** The selected place; null means the phone's position. */
    fun selected(context: Context): Place? = prefs(context).getString("selected", null)?.let { runCatching { Place.fromJson(org.json.JSONObject(it)) }.getOrNull() }
    fun setSelected(context: Context, place: Place?) = prefs(context).edit().apply {
        if (place == null) remove("selected") else putString("selected", place.toJson().toString())
    }.apply()

    /** A fresh coarse fix (10 s at most), else the last known one. Needs the location permission. */
    @SuppressLint("MissingPermission")
    suspend fun position(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= 30) {
            for (p in providers) {
                // Before Android 12 the GPS provider refuses a coarse-only app: skip it then.
                val fix = runCatching {
                    withTimeoutOrNull(10_000) {
                        suspendCancellableCoroutine<Location?> { cont ->
                            val signal = CancellationSignal()
                            cont.invokeOnCancellation { signal.cancel() }
                            lm.getCurrentLocation(p, signal, context.mainExecutor) { cont.resume(it) }
                        }
                    }
                }.getOrNull()
                if (fix != null) return fix
            }
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** "Lyon" for a position, or its coordinates when the phone can't name it. */
    @Suppress("DEPRECATION")
    suspend fun placeName(context: Context, loc: Location): String = withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.FRANCE).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: "%.2f, %.2f".format(Locale.ROOT, loc.latitude, loc.longitude)
    }
}
