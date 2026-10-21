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

    /** "ouvre", "lance", "tu peux ouvrir", "démarre-moi"… anywhere in the sentence. */
    private val OPEN = Regex("\\b(ouvr(e|es|ir|ez)|lance(r|z)?|demarre(r|z)?)\\b")
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
        val verb = OPEN.find(t) ?: return null
        return findApp(context, t, t.substring(verb.range.last + 1))?.let { (label, pkg) -> AlarmAction.OpenApp(label, pkg) }
    }

    /**
     * The launcher app named in the sentence: an app whose whole name appears in it (the
     * longest wins), otherwise one whose name starts with the words after the verb.
     */
    private fun findApp(context: Context, sentence: String, afterVerb: String): Pair<String, String>? {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
        val words = " " + words(sentence) + " "
        apps.filter { words(normalize(it.first)).length >= 3 && words.contains(" " + words(normalize(it.first)) + " ") }
            .maxByOrNull { it.first.length }
            ?.let { return it }
        val wanted = words(afterVerb)
            .removePrefix("moi ").split(' ')
            .dropWhile { it in FILLERS }
            .takeWhile { it !in FILLERS }
            .joinToString("")
            .takeIf { it.length >= 3 } ?: return null
        val compact = { label: String -> normalize(label).filter(Char::isLetterOrDigit) }
        return apps.filter { compact(it.first).startsWith(wanted) }.minByOrNull { it.first.length }
            ?: apps.filter { wanted.length >= 4 && compact(it.first).contains(wanted) }.minByOrNull { it.first.length }
    }

    private val FILLERS = setOf("l", "la", "le", "les", "mon", "ma", "mes", "app", "appli", "application", "stp", "please", "pour", "s", "il", "te", "plait", "moi", "vite")

    /** Letters and digits only, single spaces. */
    private fun words(s: String) = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

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
