package dev.netrunner.launcher.aska

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.netrunner.launcher.system.Ghost
import java.text.Normalizer
import java.util.Locale

/**
 * The few things As//ka may do on the deck besides the clock: open an app, the torch, ghost
 * mode. Understood without the model, and always confirmed by the runner first.
 */
object AskaActions {

    private val OPEN = Regex("^(?:as//ka|ashka|aska)?[ ,]*(?:peux-tu |tu peux |stp |s'il te plait )?(?:ouvre|ouvrir|lance|lancer|demarre|demarrer|affiche)(?:-moi| moi)?\\s+(?:l'appli |l'application |l'app |l')?(?:le |la |les |mon |ma |mes )?(.+?)(?: stp| s'il te plait| please)?[ .!?]*$")
    private val TORCH_ON = Regex("\\b(allume|active|mets)\\b.*\\b(lampe|torche|flash)\\b")
    private val TORCH_OFF = Regex("\\b(eteins|eteint|coupe|desactive)\\b.*\\b(lampe|torche|flash)\\b")
    private val GHOST = Regex("\\bmode furtif\\b|\\bfurtif\\b")

    fun parse(context: Context, input: String): AlarmAction? {
        val t = normalize(input)
        if (TORCH_OFF.containsMatchIn(t)) return AlarmAction.Torch(false)
        if (TORCH_ON.containsMatchIn(t) || Regex("^(lampe|torche)( stp)?$").matches(t)) return AlarmAction.Torch(true)
        if (GHOST.containsMatchIn(t) && Regex("\\b(active|lance|passe|mets|desactive|coupe|quitte|sors|mode furtif)\\b").containsMatchIn(t)) {
            return AlarmAction.GhostMode
        }
        val wanted = OPEN.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 } ?: return null
        return findApp(context, wanted)?.let { (label, pkg) -> AlarmAction.OpenApp(label, pkg) }
    }

    /** The launcher app whose name best matches what was said. */
    private fun findApp(context: Context, wanted: String): Pair<String, String>? {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
        val w = wanted.replace(" ", "")
        return apps.firstOrNull { normalize(it.first).replace(" ", "") == w }
            ?: apps.filter { normalize(it.first).replace(" ", "").startsWith(w) }.minByOrNull { it.first.length }
            ?: apps.filter { w.length >= 4 && normalize(it.first).replace(" ", "").contains(w) }.minByOrNull { it.first.length }
    }

    fun run(context: Context, action: AlarmAction): String = when (action) {
        is AlarmAction.OpenApp -> {
            val intent = context.packageManager.getLaunchIntentForPackage(action.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent == null) error("absente")
                context.startActivity(intent)
                "J'ouvre ${action.label}."
            } catch (_: Exception) {
                "${action.label} ne répond pas à mes signaux."
            }
        }
        is AlarmAction.Torch -> {
            val camera = context.getSystemService(CameraManager::class.java)
            val id = runCatching {
                camera?.cameraIdList?.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            }.getOrNull()
            if (camera == null || id == null) "Pas de lampe sur ce deck…"
            else if (runCatching { camera.setTorchMode(id, action.on) }.isSuccess) {
                if (action.on) "Lumière ! Héhé, je vois tout maintenant." else "Lampe éteinte."
            } else "La lampe est occupée (l'appareil photo, peut-être ?)."
        }
        AlarmAction.GhostMode -> {
            val wasOn = Ghost.isOn(context)
            try {
                Ghost.toggle(context)
                if (wasOn) "Mode furtif coupé. Te revoilà sur le réseau." else "Mode furtif activé. On disparaît… chut."
            } catch (_: Exception) {
                "Le mode furtif n'a pas pu s'enclencher."
            }
        }
        else -> ""
    }

    private fun normalize(s: String) =
        Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'').trim()
}
