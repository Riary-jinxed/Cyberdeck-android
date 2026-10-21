package dev.netrunner.weather.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.weather.data.Place
import dev.netrunner.weather.data.WeatherRepo

/** What the screens show: the forecast, where it is for, and the saved places. */
class WeatherState(context: Context) {
    private val app = context.applicationContext

    var forecast by mutableStateOf(WeatherRepo.cached(app))
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** null: the phone's position. */
    var selected by mutableStateOf(WeatherRepo.selected(app))
        private set
    var saved by mutableStateOf(WeatherRepo.saved(app))
        private set

    fun hasLocation() = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh() {
        if (loading) return
        loading = true
        error = null
        try {
            val place = selected ?: run {
                if (!hasLocation()) throw IllegalStateException("Autorise la position ou choisis une ville dans LIEUX.")
                val loc = WeatherRepo.position(app) ?: throw IllegalStateException("Position introuvable : active la localisation ou choisis une ville.")
                Place(WeatherRepo.placeName(app, loc), "Position actuelle", loc.latitude, loc.longitude, gps = true)
            }
            forecast = WeatherRepo.forecast(app, place)
        } catch (e: Exception) {
            error = when (e) {
                is IllegalStateException -> e.message
                else -> "Réseau indisponible" + (forecast?.let { " : dernières données affichées." } ?: ".")
            }
        } finally {
            loading = false
        }
    }

    fun select(place: Place?) {
        selected = place
        WeatherRepo.setSelected(app, place)
    }

    fun save(place: Place) {
        saved = (saved.filterNot { it.lat == place.lat && it.lon == place.lon } + place)
        WeatherRepo.setSaved(app, saved)
    }

    fun remove(place: Place) {
        saved = saved - place
        WeatherRepo.setSaved(app, saved)
        if (selected == place) select(null)
    }
}
