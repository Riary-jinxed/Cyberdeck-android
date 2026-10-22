package dev.netrunner.launcher.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.Dp
import dev.netrunner.launcher.AppEntry

/** How the launcher boots when its process starts. */
enum class BootStyle(val label: String) { Bios("BIOS"), Breach("BREACH"), Corpo("CORPO"), Off("AUCUNE") }

/** Display preferences, held in Compose state so every screen updates when one changes. */
object DeckSettings {
    var neonIcons by mutableStateOf(true)
        private set
    var bootStyle by mutableStateOf(BootStyle.Bios)
        private set
    /** The runner's handle, shown during boot and on the lock screen greeting. */
    var handle by mutableStateOf("V")
        private set
    /** Opt-in: As//ka may be sensual and explicit during a date, in words only. */
    var adultDates by mutableStateOf(false)
        private set
    /** Where As//ka shows up: her widget and sigil on the home screen, her morning card, her nap on the lock screen. */
    var askaWidget by mutableStateOf(true)
        private set
    var askaSigil by mutableStateOf(true)
        private set
    var askaMorning by mutableStateOf(true)
        private set
    var askaLock by mutableStateOf(true)
        private set
    /** Off by default: notifications from other apps shown as a faction-coloured banner over them. */
    var neonBanners by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        neonIcons = prefs(context).getBoolean("neon_icons", true)
        bootStyle = BootStyle.entries.firstOrNull { it.name == prefs(context).getString("boot_style", null) } ?: BootStyle.Bios
        handle = prefs(context).getString("handle", "V") ?: "V"
        adultDates = prefs(context).getBoolean("adult_dates", false)
        askaWidget = prefs(context).getBoolean("aska_widget", true)
        askaSigil = prefs(context).getBoolean("aska_sigil", true)
        askaMorning = prefs(context).getBoolean("aska_morning", true)
        askaLock = prefs(context).getBoolean("aska_lock", true)
        neonBanners = prefs(context).getBoolean("neon_banners", false)
    }

    fun setNeonBanners(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("neon_banners", on).apply()
        neonBanners = on
    }

    fun setBootStyle(context: Context, style: BootStyle) {
        prefs(context).edit().putString("boot_style", style.name).apply()
        bootStyle = style
    }

    fun setHandle(context: Context, value: String) {
        val clean = value.trim().take(16).ifEmpty { "V" }
        prefs(context).edit().putString("handle", clean).apply()
        handle = clean
    }

    fun setNeonIcons(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("neon_icons", on).apply()
        neonIcons = on
    }

    fun setAdultDates(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("adult_dates", on).apply()
        adultDates = on
    }

    fun setAskaWidget(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("aska_widget", on).apply()
        askaWidget = on
    }

    fun setAskaSigil(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("aska_sigil", on).apply()
        askaSigil = on
    }

    fun setAskaMorning(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("aska_morning", on).apply()
        askaMorning = on
    }

    fun setAskaLock(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("aska_lock", on).apply()
        askaLock = on
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}

/** Asks the CRT layer for a short glitch burst (opening a panel, the app drawer…). */
object GlitchBus {
    var pulse by mutableIntStateOf(0)
        private set

    fun trigger() {
        pulse++
    }
}

/** Monochrome icon in the faction colour: the brightness of the original drives the neon. */
private fun neonFilter(tint: Color): ColorFilter {
    val k = 1.5f
    fun row(c: Float) = floatArrayOf(0.30f * c * k, 0.59f * c * k, 0.11f * c * k, 0f, 0f)
    return ColorFilter.colorMatrix(
        ColorMatrix(row(tint.red) + row(tint.green) + row(tint.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
    )
}

@Composable
fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier, tint: Color = Cp.Yellow) {
    val filter = remember(tint, DeckSettings.neonIcons) { if (DeckSettings.neonIcons) neonFilter(tint) else null }
    Image(app.icon, contentDescription = app.label, colorFilter = filter, modifier = modifier.size(size))
}
