package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The weather of the runner's city, from Open-Meteo (free, no key), for her pose. */
object Weather {

    data class Now(val pose: Pose, val label: String, val temperature: Double, val day: Boolean) {
        fun note() = "$label, ${temperature.toInt()} °C"
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private var place: Triple<String, Double, Double>? = null

    suspend fun now(city: String): Now? = withContext(Dispatchers.IO) {
        if (city.isBlank()) return@withContext null
        runCatching {
            val (_, lat, lon) = place?.takeIf { it.first == city } ?: locate(city)?.also { place = it } ?: return@runCatching null
            val o = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,wind_speed_10m,is_day")
                ?.get("current")?.jsonObject ?: return@runCatching null
            fun d(key: String) = o[key]?.jsonPrimitive?.doubleOrNull
            read(d("weather_code")?.toInt() ?: return@runCatching null, d("temperature_2m") ?: 15.0, d("wind_speed_10m") ?: 0.0, d("is_day")?.toInt() != 0)
        }.getOrNull()
    }

    private fun locate(city: String): Triple<String, Double, Double>? {
        val q = URLEncoder.encode(city, Charsets.UTF_8)
        val r = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=fr")
            ?.get("results")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        return Triple(city, r["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null, r["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null)
    }

    private fun get(url: String): JsonObject? {
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        return json.parseToJsonElement(response.body()).jsonObject.takeIf { it["error"]?.jsonPrimitive?.contentOrNull == null }
    }

    /** WMO weather codes to her poses. */
    fun read(code: Int, temperature: Double, wind: Double, day: Boolean): Now = when {
        code >= 95 -> Now(Pose.Storm, "orage", temperature, day)
        code in 71..77 || code in 85..86 -> Now(Pose.Snow, "neige", temperature, day)
        code in 51..67 || code in 80..82 -> Now(Pose.Rain, "pluie", temperature, day)
        code == 45 || code == 48 -> Now(Pose.Fog, "brouillard", temperature, day)
        wind >= 40 -> Now(Pose.Wind, "grand vent", temperature, day)
        temperature >= 30 -> Now(Pose.Heat, "canicule", temperature, day)
        temperature <= 0 -> Now(Pose.Cold, "grand froid", temperature, day)
        code >= 2 -> Now(Pose.Cloudy, "nuageux", temperature, day)
        else -> Now(Pose.Sun, "ciel dégagé", temperature, day)
    }
}
