package dev.netrunner.deck

import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Same factions as the launcher, so every deck app can match it. */
data class Palette(
    val yellow: Color,
    val cyan: Color,
    val red: Color,
    val background: Color,
    val panel: Color,
    val dim: Color,
    val text: Color,
)

/** Background pattern drawn behind the screens, one per faction. */
enum class Motif { Grid, Pinstripe, Hazard, Dots, Rings, Stencil, Spray, Plain }

/** Which corners the HUD panels cut. */
enum class PanelCut { Diagonal, Mirror, Octagon, Tab, Notch }

/** Glitch effects: by faction, always or never (same names as the launcher's setting). */
enum class GlitchMode(val label: String) { Auto("Auto"), On("Toujours"), Off("Jamais") }

/**
 * Faction themes, the same as the launcher's. "Yellow", "Cyan" and "Red" are roles (primary,
 * secondary, alert), so every screen follows the faction without knowing about it; each one
 * also has its background pattern, panel cut and glitch behaviour.
 */
enum class Faction(
    val id: String,
    val label: String,
    val palette: Palette,
    val motif: Motif = Motif.Grid,
    val cut: PanelCut = PanelCut.Diagonal,
    /** Glitch bursts; corporate themes stay clean. */
    val glitch: Boolean = true,
) {
    Netrunner(
        "netrunner", "NETRUNNER",
        Palette(Color(0xFFFCEE0A), Color(0xFF00F0FF), Color(0xFFFF003C), Color(0xFF07070D), Color(0xFF11111B), Color(0xFF5C5C74), Color(0xFFE8E8F0)),
    ),
    Arasaka(
        "arasaka", "ARASAKA",
        Palette(Color(0xFFFF1E3C), Color(0xFFF2F2F2), Color(0xFFFFB400), Color(0xFF080506), Color(0xFF160B0D), Color(0xFF6E5458), Color(0xFFF5ECEC)),
        Motif.Pinstripe, PanelCut.Mirror, glitch = false,
    ),
    Militech(
        "militech", "MILITECH",
        Palette(Color(0xFFFFA81E), Color(0xFFB5D334), Color(0xFFFF4A1C), Color(0xFF080806), Color(0xFF15150E), Color(0xFF6B6A52), Color(0xFFEEEBD8)),
        Motif.Hazard, PanelCut.Octagon, glitch = false,
    ),
    Netwatch(
        "netwatch", "NETWATCH",
        Palette(Color(0xFF3DA5FF), Color(0xFF7CFFE1), Color(0xFFFF3B6B), Color(0xFF05070D), Color(0xFF0C1220), Color(0xFF4F6280), Color(0xFFE4F0FF)),
        Motif.Dots, PanelCut.Diagonal,
    ),
    VoodooBoys(
        "voodoo", "VOODOO BOYS",
        Palette(Color(0xFF2BF5A6), Color(0xFFB36BFF), Color(0xFFFF2E63), Color(0xFF030806), Color(0xFF09140F), Color(0xFF42645A), Color(0xFFD9F7EA)),
        Motif.Rings, PanelCut.Tab,
    ),
    Barghest(
        "barghest", "BARGHEST",
        Palette(Color(0xFFA4D63A), Color(0xFFB9BEC2), Color(0xFFFF3B30), Color(0xFF0A0B0A), Color(0xFF161816), Color(0xFF5E6559), Color(0xFFE7EAE2)),
        Motif.Spray, PanelCut.Notch, glitch = false,
    ),
    Ghost(
        "ghost", "GHOST",
        Palette(Color(0xFFB8C4CC), Color(0xFF6FA8B8), Color(0xFFC0485C), Color(0xFF030304), Color(0xFF0B0C0E), Color(0xFF3E444C), Color(0xFFAEB6BE)),
        Motif.Plain, PanelCut.Diagonal, glitch = false,
    );

    companion object {
        // "dogtown" was the first name of the Barghest theme in the launcher.
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: if (id == "dogtown") Barghest else Netrunner
    }
}

/**
 * Active theme. Backed by Compose state, so a change recolours every screen live. By default it
 * follows the launcher's faction and glitch setting (read from its theme provider each time an
 * app comes back to the front); picking a faction in an app's CONFIG keeps that app on its own.
 */
object Cp {
    private var palette by mutableStateOf(Faction.Netrunner.palette)
    var faction by mutableStateOf(Faction.Netrunner)
        private set
    var glitchMode by mutableStateOf(GlitchMode.Auto)
        private set
    /** Following the launcher's theme. */
    var follow by mutableStateOf(true)
        private set
    /** The launcher answered: its theme is the one shown. */
    var linked by mutableStateOf(false)
        private set

    val Yellow get() = palette.yellow
    val Cyan get() = palette.cyan
    val Red get() = palette.red
    val Background get() = palette.background
    val Panel get() = palette.panel
    val Dim get() = palette.dim
    val Text get() = palette.text

    /** True when glitch bursts should play, from the setting and the faction. */
    val glitchOn: Boolean
        get() = when (glitchMode) {
            GlitchMode.Auto -> faction.glitch
            GlitchMode.On -> true
            GlitchMode.Off -> false
        }

    private val LAUNCHER = Uri.parse("content://dev.netrunner.launcher.theme/current")
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        follow = p.getBoolean(KEY_FOLLOW, true)
        apply(Faction.of(p.getString(KEY, null)))
        sync(context)
    }

    /** Takes the launcher's theme when following it; keeps the local one if it can't be read. */
    fun sync(context: Context) {
        if (!follow) { linked = false; return }
        val theme = runCatching {
            context.contentResolver.query(LAUNCHER, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else c.getString(c.getColumnIndexOrThrow("faction")) to c.getColumnIndex("glitch").let { i -> if (i >= 0) c.getString(i) else null }
            }
        }.getOrNull()
        linked = theme != null
        if (theme != null) {
            apply(Faction.of(theme.first))
            glitchMode = GlitchMode.entries.firstOrNull { it.name == theme.second } ?: GlitchMode.Auto
            prefs(context).edit().putString(KEY, faction.id).apply()
        }
    }

    /** Picks a faction for this app only. */
    fun select(context: Context, faction: Faction) {
        follow = false
        linked = false
        prefs(context).edit().putString(KEY, faction.id).putBoolean(KEY_FOLLOW, false).apply()
        apply(faction)
    }

    fun setFollow(context: Context, on: Boolean) {
        follow = on
        prefs(context).edit().putBoolean(KEY_FOLLOW, on).apply()
        if (on) sync(context) else { linked = false; glitchMode = GlitchMode.Auto }
    }

    private fun apply(f: Faction) {
        faction = f
        palette = f.palette
    }

    private fun prefs(context: Context) = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
    private const val KEY = "faction"
    private const val KEY_FOLLOW = "follow_launcher"
}

/** Body and labels: Rajdhani, the narrow technical sans close to the game's HUD. */
val HudFont: FontFamily = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
    Font(R.font.rajdhani_bold, FontWeight.Black),
)

/** Big numbers and titles: Orbitron (variable font). */
@OptIn(ExperimentalTextApi::class)
val DisplayFont: FontFamily = FontFamily(
    Font(R.font.orbitron, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.orbitron, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.orbitron, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

/** Terminal and aligned columns: Share Tech Mono. */
val MonoFont: FontFamily = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))

@Composable
fun DeckTheme(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext
    Cp.load(app)
    // Back from the launcher, where the theme may have changed.
    LifecycleResumeEffect(Unit) {
        Cp.sync(app)
        onPauseOrDispose { }
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cp.Yellow,
            secondary = Cp.Cyan,
            error = Cp.Red,
            background = Cp.Background,
            surface = Cp.Panel,
            surfaceContainer = Cp.Panel,
            onSurface = Cp.Text,
            onBackground = Cp.Text,
        ),
        content = content,
    )
}
