package dev.netrunner.weather.data

import org.json.JSONArray
import org.json.JSONObject

/** A place to forecast: a saved city, or the phone's position (when [gps]). */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double, val gps: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("n", name).put("d", detail).put("la", lat).put("lo", lon).put("g", gps)

    companion object {
        fun fromJson(o: JSONObject) = Place(o.optString("n"), o.optString("d"), o.optDouble("la"), o.optDouble("lo"), o.optBoolean("g"))

        fun searchUrl(query: String) =
            "https://geocoding-api.open-meteo.com/v1/search?count=8&language=fr&format=json&name=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")

        /** Open-Meteo geocoding results: "Lyon" with "Auvergne-Rhône-Alpes, France". */
        fun parseSearch(json: String): List<Place> {
            val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                val detail = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
                Place(r.optString("name"), detail, r.optDouble("latitude"), r.optDouble("longitude"))
            }
        }

        fun listToJson(places: List<Place>): String = JSONArray().apply { places.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<Place> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
