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
enum class Motif { Grid, Pinstripe, Hazard, Dots, Rings, Stencil, Plain }

/** Which corners the HUD panels cut. */
enum class PanelCut { Diagonal, Mirror, Octagon, Tab, Notch }

/**
 * Faction colour schemes. "Yellow", "Cyan" and "Red" are roles (primary, secondary, alert),
 * so every screen follows the faction without knowing about it. Each faction also has its own
 * background pattern, panel cut and boot line.
 */
enum class Faction(
    val id: String,
    val label: String,
    val palette: Palette,
    val motif: Motif = Motif.Grid,
    val cut: PanelCut = PanelCut.Diagonal,
    val motto: String = "SYSTÈMES EN LIGNE",
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
    ),
    Militech(
        "militech", "MILITECH",
        Palette(Color(0xFFFFA81E), Color(0xFFB5D334), Color(0xFFFF4A1C), Color(0xFF080806), Color(0xFF15150E), Color(0xFF6B6A52), Color(0xFFEEEBD8)),
        Motif.Hazard, PanelCut.Octagon, "ARMEMENT CERTIFIÉ // PRÊT AU COMBAT",
    ),
    Netwatch(
        "netwatch", "NETWATCH",
        Palette(Color(0xFF3DA5FF), Color(0xFF7CFFE1), Color(0xFFFF3B6B), Color(0xFF05070D), Color(0xFF0C1220), Color(0xFF4F6280), Color(0xFFE4F0FF)),
        Motif.Dots, PanelCut.Diagonal, "LIAISON SOUS SURVEILLANCE",
    ),
    VoodooBoys(
        "voodoo", "VOODOO BOYS",
        Palette(Color(0xFF2BF5A6), Color(0xFFB36BFF), Color(0xFFFF2E63), Color(0xFF030806), Color(0xFF09140F), Color(0xFF42645A), Color(0xFFD9F7EA)),
        Motif.Rings, PanelCut.Tab, "LES LOAS ÉCOUTENT AU-DELÀ DU BLACKWALL",
    ),
    Dogtown(
        "dogtown", "DOGTOWN",
        Palette(Color(0xFFFF5A2E), Color(0xFFFFC857), Color(0xFFFF1F4B), Color(0xFF0A0605), Color(0xFF1A0F0B), Color(0xFF75594C), Color(0xFFF4E7DC)),
        Motif.Stencil, PanelCut.Notch, "ZONE FRANCHE // ICI, C'EST NOUS LA LOI",
    ),
    Ghost(
        "ghost", "GHOST",
        Palette(Color(0xFFB8C4CC), Color(0xFF6FA8B8), Color(0xFFC0485C), Color(0xFF030304), Color(0xFF0B0C0E), Color(0xFF3E444C), Color(0xFFAEB6BE)),
        Motif.Plain, PanelCut.Diagonal, "FURTIF",
    );

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Netrunner
    }
}

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

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        apply(Faction.of(prefs(context).getString(KEY, null)))
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
