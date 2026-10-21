package dev.netrunner.launcher.aska

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * As//ka as a little Tamagotchi: appetite, mood, energy and bond, which drift as time passes.
 * Nothing runs in the background: the time elapsed since the last look is applied when her
 * nest is opened.
 */
class AskaPet(context: Context) {

    /** What she does right now, which picks her chibi and her line. */
    enum class Mood { Sleeping, Hungry, Tired, Bored, Happy, Shy, Mischievous, Eating, Content }

    class Stats(
        /** 100 = full of macarons. */
        val food: Float,
        val joy: Float,
        val energy: Float,
        val bond: Float,
        val xp: Int,
        val asleep: Boolean,
    ) {
        val level get() = 1 + xp / XP_PER_LEVEL
        val levelProgress get() = (xp % XP_PER_LEVEL) / XP_PER_LEVEL.toFloat()
    }

    private val prefs = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)

    var stats by mutableStateOf(load())
        private set

    /** Her reaction to the last care, shown for a few seconds. */
    var reaction by mutableStateOf<Mood?>(null)
        private set

    /** What she says. */
    var line by mutableStateOf("")
        private set

    private var lastTouch = 0L

    /** Applies the time spent away. Call when her nest opens, and now and then while it is open. */
    fun refresh() {
        stats = elapsed(stats)
        save()
        if (reaction == null) line = idleLine()
    }

    fun feed() {
        val s = elapsed(stats)
        if (s.asleep) return wakeFirst()
        if (s.food > 92f) {
            stats = s.copy(joy = s.joy - 3f)
            react(Mood.Shy, "Hmpf… je n'ai plus de place. Même pour un macaron. Presque.")
        } else {
            stats = s.copy(food = s.food + 28f, joy = s.joy + 6f, xp = s.xp + 6)
            react(Mood.Eating, EATING.random())
        }
    }

    fun play() {
        val s = elapsed(stats)
        if (s.asleep) return wakeFirst()
        if (s.energy < 15f) {
            react(Mood.Tired, "Trop fatiguée pour jouer… on fait une sieste ?")
            return
        }
        stats = s.copy(joy = s.joy + 22f, energy = s.energy - 12f, food = s.food - 6f, xp = s.xp + 10)
        react(Mood.Mischievous, PLAYING.random())
    }

    fun pet() {
        val now = System.currentTimeMillis()
        if (now - lastTouch < 1_500) return
        lastTouch = now
        val s = elapsed(stats)
        if (s.asleep) {
            react(Mood.Sleeping, "…mmh… encore cinq minutes…")
            return
        }
        stats = s.copy(bond = s.bond + 4f, joy = s.joy + 4f, xp = s.xp + 3)
        react(Mood.Shy, PETTED.random())
    }

    fun toggleSleep() {
        val s = elapsed(stats)
        stats = s.copy(asleep = !s.asleep)
        save()
        if (stats.asleep) react(Mood.Sleeping, "Bonne nuit, runner… veille sur le deck pour moi.")
        else react(Mood.Happy, if (s.energy > 70f) "Systèmes rechargés ! On fait quoi ?" else "Déjà ? *bâille* … bon, d'accord.")
    }

    /** Her state now, for her chibi. */
    fun mood(): Mood {
        reaction?.let { return it }
        val s = stats
        return when {
            s.asleep -> Mood.Sleeping
            s.food < 30f -> Mood.Hungry
            s.energy < 25f -> Mood.Tired
            s.joy < 30f -> Mood.Bored
            s.joy > 75f -> Mood.Happy
            else -> Mood.Content
        }
    }

    /** The reaction is over: back to her usual self. */
    fun calm() {
        reaction = null
        line = idleLine()
    }

    /** A few words about how she feels, for the model's instructions. */
    fun forPrompt(): String {
        val s = elapsed(stats)
        val feelings = buildList {
            if (s.food < 30f) add("tu as faim et tu réclames volontiers un macaron")
            if (s.energy < 25f) add("tu es fatiguée")
            if (s.joy < 30f) add("tu t'ennuies un peu")
            if (s.joy > 75f) add("tu es de très bonne humeur")
            if (s.bond > 70f) add("tu es très attachée à ton runner")
        }
        return if (feelings.isEmpty()) "" else "\nEn ce moment, ${feelings.joinToString(", ")}."
    }

    private fun wakeFirst() = react(Mood.Sleeping, "Zzz… (elle dort. Réveille-la d'abord.)")

    private fun react(mood: Mood, text: String) {
        stats = stats.clamped()
        save()
        reaction = mood
        line = text
    }

    private fun idleLine(): String = when (mood()) {
        Mood.Sleeping -> "Zzz…"
        Mood.Hungry -> "Mon estomac fait des bruits de modem… un macaron ?"
        Mood.Tired -> "*bâille* Mes circuits ramollissent…"
        Mood.Bored -> "On joue ? Je m'ennuie dans ce deck…"
        Mood.Happy -> "Héhé, tout va bien dans le meilleur des réseaux !"
        else -> "Je suis là. Tu me fais un câlin ?"
    }

    // ---- Time and storage.

    private fun elapsed(s: Stats): Stats {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_TIME, now)
        val hours = ((now - last).coerceAtLeast(0) / 3_600_000f).coerceAtMost(72f)
        prefs.edit().putLong(KEY_TIME, now).apply()
        if (hours <= 0f) return s
        return if (s.asleep) {
            s.copy(food = s.food - 2f * hours, energy = s.energy + 18f * hours, joy = s.joy - 1f * hours)
        } else {
            s.copy(food = s.food - 6f * hours, energy = s.energy - 4f * hours, joy = s.joy - 5f * hours, bond = s.bond - 0.5f * hours)
        }.clamped()
    }

    private fun Stats.copy(
        food: Float = this.food,
        joy: Float = this.joy,
        energy: Float = this.energy,
        bond: Float = this.bond,
        xp: Int = this.xp,
        asleep: Boolean = this.asleep,
    ) = Stats(food, joy, energy, bond, xp, asleep)

    private fun Stats.clamped() = Stats(
        food.coerceIn(0f, 100f), joy.coerceIn(0f, 100f), energy.coerceIn(0f, 100f), bond.coerceIn(0f, 100f), xp, asleep,
    )

    private fun load(): Stats = runCatching {
        val o = JSONObject(prefs.getString(KEY_STATS, null) ?: error("none"))
        Stats(
            o.getDouble("food").toFloat(), o.getDouble("joy").toFloat(), o.getDouble("energy").toFloat(),
            o.getDouble("bond").toFloat(), o.getInt("xp"), o.getBoolean("asleep"),
        )
    }.getOrElse { Stats(food = 70f, joy = 70f, energy = 80f, bond = 20f, xp = 0, asleep = false) }

    private fun save() {
        val s = stats
        prefs.edit().putString(
            KEY_STATS,
            JSONObject()
                .put("food", s.food.roundToInt()).put("joy", s.joy.roundToInt()).put("energy", s.energy.roundToInt())
                .put("bond", s.bond.roundToInt()).put("xp", s.xp).put("asleep", s.asleep)
                .toString(),
        ).apply()
    }

    companion object {
        const val XP_PER_LEVEL = 100
        private const val KEY_STATS = "aska_pet"
        private const val KEY_TIME = "aska_pet_time"

        private val EATING = listOf(
            "Miam ! Un macaron rose, mon péché mignon…",
            "*croque* … c'est encore meilleur qu'un paquet de données fraîches !",
            "Héhé, tu sais me prendre par les sentiments.",
        )
        private val PLAYING = listOf(
            "Attrape-moi si tu peux, runner ! Héhé !",
            "Je t'ai eu ! Encore une partie ?",
            "Niark… j'ai triché un tout petit peu. Chut.",
        )
        private val PETTED = listOf(
            "Euh… m-merci… *rougit*",
            "Hé… pas devant les autres programmes…",
            "C'est… agréable. Ne le dis à personne.",
        )
    }
}
