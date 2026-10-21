package dev.netrunner.launcher.system

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/** Home screen gestures, each bound to an action picked in the settings. */
object Gestures {
    enum class Gesture(val label: String, val default: Action) {
        SwipeUp("Glisser vers le haut", Action.Drawer),
        SwipeDown("Glisser vers le bas", Action.Notifications),
        SwipeLeft("Glisser vers la gauche", Action.Hub),
        SwipeRight("Glisser vers la droite", Action.Terminal),
        DoubleTap("Double-tap sur l'horloge", Action.Ghost),
        LongPress("Appui long sur l'horloge", Action.Settings),
    }

    enum class Action(val label: String) {
        Drawer("Réseau (applis)"),
        Hub("Applis du deck"),
        Notifications("Notifications système"),
        Quick("Contrôle du deck"),
        Terminal("Terminal"),
        Claude("Appli Claude"),
        Aska("As//ka (IA locale)"),
        Messages("SMS"),
        Feed("Flux entrant"),
        Dialer("Appels"),
        Wifi("Scanner Wi-Fi"),
        Kit("Kit de terrain"),
        Stats("Statistiques"),
        Ghost("Mode furtif"),
        Torch("Lampe"),
        Settings("Config"),
        None("Rien"),
    }

    private val bindings = mutableStateMapOf<Gesture, Action>()
    private var loaded = false

    fun load(context: Context, force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        val p = prefs(context)
        // 1.3.1: swipe left moved from the Claude app to the deck hub (Claude is a shortcut there).
        if (!p.getBoolean("gestures_hub", false)) {
            p.edit().putBoolean("gestures_hub", true).apply()
            if (p.getString("gesture_${Gesture.SwipeLeft.name}", null) == Action.Claude.name) {
                p.edit().remove("gesture_${Gesture.SwipeLeft.name}").apply()
            }
        }
        Gesture.entries.forEach { g ->
            bindings[g] = Action.entries.firstOrNull { it.name == p.getString("gesture_${g.name}", null) } ?: g.default
        }
    }

    fun actionFor(gesture: Gesture): Action = bindings[gesture] ?: gesture.default

    fun bind(context: Context, gesture: Gesture, action: Action) {
        bindings[gesture] = action
        prefs(context).edit().putString("gesture_${gesture.name}", action.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
