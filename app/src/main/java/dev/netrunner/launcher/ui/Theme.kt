package dev.netrunner.launcher.ui

import android.content.Context
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
import dev.netrunner.launcher.R

data class Palette(
    val yellow: Color,
    val cyan: Color,
    val red: Color,
    val background: Color,
    val panel: Color,
    val dim: Color,
    val text: Color,
)

/** Background pattern drawn behind the HUD, one per faction. */
enum class Motif { Grid, Pinstripe, Hazard, Dots, Rings, Stencil, Spray, Plain }

/** Which corners the HUD panels cut. */
enum class PanelCut { Diagonal, Mirror, Octagon, Tab, Notch }

/** Typeface of the home clock. */
enum class ClockFace { Display, Hud, Mono }

/** Screen frame drawn over the home screen: corner marks, a targeting reticle, a corporate rule, ritual arcs. */
enum class Frame { Corners, Reticle, Rule, Arcs }

/**
 * Faction themes. "Yellow", "Cyan" and "Red" are palette roles (primary, secondary, alert),
 * so every screen follows the faction without knowing about it. Beyond the colours, each
 * faction has its own background pattern, panel cut, clock typeface, screen frame, place
 * line under the clock, glitch behaviour and feed voice (see FactionFeed).
 */
enum class Faction(
    val id: String,
    val label: String,
    val palette: Palette,
    val motif: Motif = Motif.Grid,
    val cut: PanelCut = PanelCut.Diagonal,
    val motto: String = "SYSTÈMES EN LIGNE",
    val clock: ClockFace = ClockFace.Display,
    val frame: Frame = Frame.Corners,
    val place: String = "NIGHT CITY",
    /** Glitch bursts on the clock and the CRT effect; corporate themes stay clean. */
    val glitch: Boolean = true,
) {
    Netrunner(
        "netrunner", "NETRUNNER",
        Palette(Color(0xFFFCEE0A), Color(0xFF00F0FF), Color(0xFFFF003C), Color(0xFF07070D), Color(0xFF11111B), Color(0xFF5C5C74), Color(0xFFE8E8F0)),
        motto = "JACK-IN, BREACH, GHOST-OUT",
    ),
    Arasaka(
        "arasaka", "ARASAKA",
        Palette(Color(0xFFFF1E3C), Color(0xFFF2F2F2), Color(0xFFFFB400), Color(0xFF080506), Color(0xFF160B0D), Color(0xFF6E5458), Color(0xFFF5ECEC)),
        Motif.Pinstripe, PanelCut.Mirror, "LOYAUTÉ // DISCIPLINE // PROFIT",
        ClockFace.Hud, Frame.Rule, "ARASAKA TOWER", glitch = false,
    ),
    Militech(
        "militech", "MILITECH",
        Palette(Color(0xFFFFA81E), Color(0xFFB5D334), Color(0xFFFF4A1C), Color(0xFF080806), Color(0xFF15150E), Color(0xFF6B6A52), Color(0xFFEEEBD8)),
        Motif.Hazard, PanelCut.Octagon, "ARMEMENT CERTIFIÉ // PRÊT AU COMBAT",
        ClockFace.Display, Frame.Reticle, "BADLANDS // FOB MILITECH", glitch = false,
    ),
    Netwatch(
        "netwatch", "NETWATCH",
        Palette(Color(0xFF3DA5FF), Color(0xFF7CFFE1), Color(0xFFFF3B6B), Color(0xFF05070D), Color(0xFF0C1220), Color(0xFF4F6280), Color(0xFFE4F0FF)),
        Motif.Dots, PanelCut.Diagonal, "LIAISON SOUS SURVEILLANCE",
        ClockFace.Mono, Frame.Corners, "SECTEUR SOUS ÉCOUTE",
    ),
    VoodooBoys(
        "voodoo", "VOODOO BOYS",
        Palette(Color(0xFF2BF5A6), Color(0xFFB36BFF), Color(0xFFFF2E63), Color(0xFF030806), Color(0xFF09140F), Color(0xFF42645A), Color(0xFFD9F7EA)),
        Motif.Rings, PanelCut.Tab, "LES LOAS ÉCOUTENT AU-DELÀ DU BLACKWALL",
        ClockFace.Hud, Frame.Arcs, "PACIFICA",
    ),
    Barghest(
        "barghest", "BARGHEST",
        // Acid green spray paint over black and gunmetal, like the militia's tagged armour.
        Palette(Color(0xFFA4D63A), Color(0xFFB9BEC2), Color(0xFFFF3B30), Color(0xFF0A0B0A), Color(0xFF161816), Color(0xFF5E6559), Color(0xFFE7EAE2)),
        Motif.Spray, PanelCut.Notch, "DOGTOWN APPARTIENT À BARGHEST",
        ClockFace.Mono, Frame.Reticle, "DOGTOWN", glitch = false,
    ),
    Ghost(
        "ghost", "GHOST",
        Palette(Color(0xFFB8C4CC), Color(0xFF6FA8B8), Color(0xFFC0485C), Color(0xFF030304), Color(0xFF0B0C0E), Color(0xFF3E444C), Color(0xFFAEB6BE)),
        Motif.Plain, PanelCut.Diagonal, "FURTIF", ClockFace.Hud, Frame.Corners, "HORS RÉSEAU", glitch = false,
    );

    companion object {
        // "dogtown" was the first name of the Barghest theme (v1.4.7).
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: if (id == "dogtown") Barghest else Netrunner
    }
}

/** The home and lock screen clock typeface for this faction. */
val Faction.clockFont: FontFamily
    get() = when (clock) {
        ClockFace.Display -> DisplayFont
        ClockFace.Hud -> HudFont
        ClockFace.Mono -> MonoFont
    }

/** The narrower faces get more size, to keep the same presence as Orbitron. */
val Faction.clockScale: Float get() = if (clock == ClockFace.Display) 1f else 1.18f

/** Glitch effects: by faction, always or never. */
enum class GlitchMode(val label: String) { Auto("Auto"), On("Toujours"), Off("Jamais") }

/** Active palette. Backed by Compose state, so switching faction recolours every screen live. */
object Cp {
    private var palette by mutableStateOf(Faction.Netrunner.palette)
    var faction by mutableStateOf(Faction.Netrunner)
        private set

    val Yellow get() = palette.yellow
    val Cyan get() = palette.cyan
    val Red get() = palette.red
    val Background get() = palette.background
    val Panel get() = palette.panel
    val Dim get() = palette.dim
    val Text get() = palette.text

    var glitchMode by mutableStateOf(GlitchMode.Auto)
        private set

    /** True when glitch bursts should play, from the setting and the faction. */
    val glitchOn: Boolean
        get() = when (glitchMode) {
            GlitchMode.Auto -> faction.glitch
            GlitchMode.On -> true
            GlitchMode.Off -> false
        }

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        apply(Faction.of(prefs(context).getString(KEY, null)))
        glitchMode = GlitchMode.entries.firstOrNull { it.name == prefs(context).getString(KEY_GLITCH, null) } ?: GlitchMode.Auto
    }

    fun setGlitchMode(context: Context, mode: GlitchMode) {
        glitchMode = mode
        prefs(context).edit().putString(KEY_GLITCH, mode.name).apply()
    }

    fun select(context: Context, faction: Faction) {
        prefs(context).edit().putString(KEY, faction.id).apply()
        apply(faction)
    }

    private fun apply(f: Faction) {
        faction = f
        palette = f.palette
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
    private const val KEY = "faction"
    private const val KEY_GLITCH = "glitch_mode"
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
fun NetrunnerTheme(content: @Composable () -> Unit) {
    Cp.load(LocalContext.current.applicationContext)
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
