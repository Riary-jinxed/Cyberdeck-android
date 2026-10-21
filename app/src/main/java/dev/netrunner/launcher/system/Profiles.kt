package dev.netrunner.launcher.system

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.launcher.ui.Cp
import dev.netrunner.launcher.ui.Faction
import dev.netrunner.launcher.ui.Wallpaper
import dev.netrunner.launcher.ui.WallpaperMode

/**
 * "Boulot" and "Perso" profiles: each keeps its own quickhacks and folders, faction, background,
 * interface sounds and ringer mode, and switching saves the current one before loading the other.
 */
object Profiles {
    enum class Profile(val label: String) { Perso("PERSO"), Boulot("BOULOT") }

    /** Settings keys that belong to a profile (copied as raw strings or booleans). */
    private val stringKeys = listOf("favorites", "folders", "faction", "wallpaper")
    private const val SOUND_KEY = "ui_sounds"
    private const val RINGER = "ringer"

    var current by mutableStateOf(Profile.Perso)
        private set
    private var loaded = false

    fun load(context: Context, force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        current = Profile.entries.firstOrNull { it.name == prefs(context).getString("profile", null) } ?: Profile.Perso
    }

    /** Ringer mode kept for a profile: normal or vibrate (silent needs Do Not Disturb access). */
    fun ringerFor(context: Context, profile: Profile): Int =
        prefs(context).getInt("profile_${profile.name}_$RINGER", if (profile == Profile.Boulot) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL)

    fun toggleRinger(context: Context, profile: Profile) {
        val next = if (ringerFor(context, profile) == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
        prefs(context).edit().putInt("profile_${profile.name}_$RINGER", next).apply()
        if (profile == current) applyRinger(context, next)
    }

    /** Saves the live setup into the current profile, then loads [target]. Returns false if already on it. */
    fun switchTo(context: Context, target: Profile): Boolean {
        if (target == current) return false
        val p = prefs(context)
        val edit = p.edit()
        stringKeys.forEach { k -> edit.putString("profile_${current.name}_$k", p.getString(k, null)) }
        edit.putBoolean("profile_${current.name}_$SOUND_KEY", UiSound.isEnabled(context))
        edit.putBoolean("profile_${current.name}_saved", true)

        // A profile used for the first time starts as a copy of the current setup.
        if (p.getBoolean("profile_${target.name}_saved", false)) {
            stringKeys.forEach { k ->
                val v = p.getString("profile_${target.name}_$k", null)
                if (v == null) edit.remove(k) else edit.putString(k, v)
            }
        }
        edit.putString("profile", target.name)
        edit.commit()

        current = target
        Cp.select(context, Faction.of(p.getString("faction", null)))
        Wallpaper.select(context, WallpaperMode.of(p.getString("wallpaper", null)))
        if (p.contains("profile_${target.name}_$SOUND_KEY")) {
            UiSound.setEnabled(context, p.getBoolean("profile_${target.name}_$SOUND_KEY", true))
        }
        applyRinger(context, ringerFor(context, target))
        return true
    }

    private fun applyRinger(context: Context, mode: Int) {
        runCatching { context.getSystemService(AudioManager::class.java)?.ringerMode = mode }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
