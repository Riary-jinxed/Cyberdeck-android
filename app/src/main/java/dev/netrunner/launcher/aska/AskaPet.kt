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

    /** Treats from her shop, bought with the credits won at her mini-game. */
    enum class Treat(val label: String, val glyph: String, val price: Int, val food: Float, val joy: Float, val energy: Float, val bond: Float, val line: String) {
        Eclair("ÉCLAIR NÉON", "▰", 8, 22f, 10f, 0f, 0f, "Un éclair qui brille dans le noir… il est presque trop beau pour être mangé. Presque !"),
        Mochi("MOCHI DATA", "●", 12, 10f, 6f, 28f, 0f, "Mmh, mochi… je sens mes circuits se recharger !"),
        Glitch("BONBON GLITCH", "◆", 5, 4f, 18f, 0f, 0f, "Ça pétille ! Mes écailles changent de couleur, regarde ! Héhé !"),
        Cake("GÂTEAU HOLO", "▲", 30, 45f, 22f, 8f, 6f, "Un gâteau holo… pour moi ? C'est… c'est le plus beau jour de ma vie de programme."),
    }

    /** Where a look goes. */
    enum class Slot(val label: String) { Aura("AURA"), Pad("SOCLE"), Decor("DÉCOR") }

    /** Holo looks for her nest, unlocked as she grows. Colors are ARGB. */
    enum class Look(val slot: Slot, val label: String, val level: Int, val argb: Long) {
        AuraNone(Slot.Aura, "AUCUNE", 1, 0x00000000),
        AuraPink(Slot.Aura, "ROSE NÉON", 1, 0xFFFF4FA3),
        AuraCyan(Slot.Aura, "CYAN GLACE", 2, 0xFF2EF2FF),
        AuraGold(Slot.Aura, "OR CHROMÉ", 4, 0xFFFCEE0A),
        AuraGlitch(Slot.Aura, "GLITCH", 7, 0xFFB026FF),
        PadPink(Slot.Pad, "ROSE", 1, 0xFFFF4FA3),
        PadCyan(Slot.Pad, "CYAN", 3, 0xFF2EF2FF),
        PadGold(Slot.Pad, "OR", 6, 0xFFFCEE0A),
        PadWhite(Slot.Pad, "PLASMA BLANC", 9, 0xFFF4F4FF),
        DecorNone(Slot.Decor, "VIDE", 1, 0x00000000),
        DecorScales(Slot.Decor, "ÉCAILLES", 2, 0xFFFF4FA3),
        DecorGrid(Slot.Decor, "GRILLE NÉON", 3, 0xFF2EF2FF),
        DecorStars(Slot.Decor, "ÉTOILES DATA", 5, 0xFFF4F4FF),
        DecorRain(Slot.Decor, "PLUIE DE CODE", 8, 0xFF39FF88);

        companion object {
            val defaults = mapOf(Slot.Aura to AuraPink, Slot.Pad to PadPink, Slot.Decor to DecorNone)
        }
    }

    /** A moment kept in her journal. */
    class Memory(val day: Long, val text: String)

    class Stats(
        /** 100 = full of macarons. */
        val food: Float,
        val joy: Float,
        val energy: Float,
        val bond: Float,
        val xp: Int,
        val asleep: Boolean,
        /** Won at her mini-game and as a daily gift, spent in her shop. */
        val credits: Int = 0,
        /** Treats bought and not eaten yet. */
        val stock: Map<Treat, Int> = emptyMap(),
        /** Day (epoch) of the last daily gift. */
        val giftDay: Long = -1,
        val bestScore: Int = 0,
    ) {
        val level get() = 1 + xp / XP_PER_LEVEL
        val levelProgress get() = (xp % XP_PER_LEVEL) / XP_PER_LEVEL.toFloat()
        val title get() = titleOf(level)
    }

    private val prefs = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var stats by mutableStateOf(load())
        private set

    init {
        if (prefs.getLong(KEY_BORN, -1L) < 0) prefs.edit().putLong(KEY_BORN, java.time.LocalDate.now().toEpochDay()).apply()
    }

    /** Her reaction to the last care, shown for a few seconds. */
    var reaction by mutableStateOf<Mood?>(null)
        private set

    /** What she says. */
    var line by mutableStateOf("")
        private set

    private var lastTouch = 0L

    /** The looks she wears, one per slot. */
    var looks by mutableStateOf(loadLooks())
        private set

    /** Her journal, newest first. */
    var journal by mutableStateOf(loadJournal())
        private set

    /** She sends a notification (once a day at most) when she is neglected. */
    var calls by mutableStateOf(prefs.getBoolean(KEY_CALLS, true))
        private set

    fun wear(look: Look) {
        if (look.level > stats.level) return
        looks = looks + (look.slot to look)
        prefs.edit().putString(KEY_LOOKS, looks.values.joinToString(",") { it.name }).apply()
        react(Mood.Shy, "Ça me va bien ? … Ne réponds pas, je sais que oui.")
    }

    fun toggleCalls(context: Context) {
        calls = !calls
        prefs.edit().putBoolean(KEY_CALLS, calls).apply()
        AskaPetCalls.schedule(context)
    }

    /** Applies the time spent away. Call when her nest opens, and now and then while it is open. */
    fun refresh() {
        stats = elapsed(stats)
        save()
        if (reaction == null) line = idleLine()
    }

    /** Her daily gift: credits for the shop, once a day, when her nest is opened. */
    fun dailyGift(): Int? {
        val today = java.time.LocalDate.now().toEpochDay()
        val s = elapsed(stats)
        if (s.giftDay == today) return null
        val gift = DAILY_GIFT + (s.level - 1).coerceAtMost(10) * 2
        stats = s.copy(credits = s.credits + gift, giftDay = today)
        anniversary()
        react(Mood.Happy, "Cadeau du jour : $gift ¢ ! Ne les dépense pas tout en friandises… enfin, si, un peu.")
        return gift
    }

    /** True when she has the energy for her mini-game; otherwise she says why not. */
    fun canPlay(): Boolean {
        val s = elapsed(stats)
        if (s.asleep) {
            wakeFirst()
            return false
        }
        if (s.energy < 15f) {
            react(Mood.Tired, "Trop fatiguée pour jouer… on fait une sieste ?")
            return false
        }
        return true
    }

    /** End of a round of her mini-game: [caught] macarons. */
    fun played(caught: Int) {
        val s = elapsed(stats)
        val record = caught > s.bestScore
        first("game", "Première partie d'« attrape les macarons » : $caught attrapés.")
        if (record && caught >= 15) remember("Record au jeu : $caught macarons. Elle en parle encore.")
        stats = s.copy(
            joy = s.joy + 10f + caught.coerceAtMost(25),
            energy = s.energy - 10f,
            food = s.food - 4f,
            xp = s.xp + 4 + caught,
            credits = s.credits + caught,
            bestScore = maxOf(s.bestScore, caught),
        )
        react(
            if (caught >= 10) Mood.Mischievous else Mood.Happy,
            when {
                record && caught > 0 -> "Nouveau record : $caught ! J'ai les meilleurs réflexes du réseau. Grâce à toi. Un peu."
                caught == 0 -> "Zéro ? Hé, ils étaient rapides ! On recommence quand tu veux."
                else -> "$caught macarons attrapés, $caught ¢ gagnés ! Héhé, on fait une bonne équipe."
            },
        )
    }

    fun buy(treat: Treat) {
        val s = elapsed(stats)
        if (s.credits < treat.price) {
            react(Mood.Shy, "Il manque ${treat.price - s.credits} ¢… on joue encore un peu ?")
            return
        }
        stats = s.copy(credits = s.credits - treat.price, stock = s.stock + (treat to (s.stock[treat] ?: 0) + 1))
        react(Mood.Happy, "${treat.label.lowercase().replaceFirstChar { it.uppercase() }} dans le sac ! Tu me le donnes quand tu veux…")
    }

    fun give(treat: Treat) {
        val s = elapsed(stats)
        val left = s.stock[treat] ?: 0
        if (left <= 0) return
        if (s.asleep) return wakeFirst()
        stats = s.copy(
            food = s.food + treat.food,
            joy = s.joy + treat.joy,
            energy = s.energy + treat.energy,
            bond = s.bond + treat.bond + 1f,
            xp = s.xp + treat.price / 2 + 3,
            stock = if (left == 1) s.stock - treat else s.stock + (treat to left - 1),
        )
        first("treat_${treat.name}", "Première fois qu'elle goûte un ${treat.label.lowercase()}.")
        react(if (treat == Treat.Glitch) Mood.Mischievous else Mood.Eating, treat.line)
    }

    fun feed() {
        val s = elapsed(stats)
        if (s.asleep) return wakeFirst()
        if (s.food > 92f) {
            stats = s.copy(joy = s.joy - 3f)
            react(Mood.Shy, "Hmpf… je n'ai plus de place. Même pour un macaron. Presque.")
        } else {
            stats = s.copy(food = s.food + 28f, joy = s.joy + 6f, xp = s.xp + 6)
            first("feed", "Premier macaron de ta main. Elle a fait semblant de ne pas rougir.")
            react(Mood.Eating, EATING.random())
        }
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
        first("pet", "Premier câlin. « C'est… agréable. Ne le dis à personne. »")
        if (stats.bond >= 100f) first("bond", "Lien au maximum : elle te fait entièrement confiance.")
        react(Mood.Shy, PETTED.random())
    }

    /** Back from a rendez-vous: the more they talked, the closer they got. */
    fun dated(scene: AskaDate.Scene, messages: Int) {
        val s = elapsed(stats)
        val talked = messages.coerceAtMost(12)
        stats = s.copy(bond = s.bond + 2f + talked * 0.5f, joy = s.joy + 10f, xp = s.xp + 4 + talked)
        val place = scene.label.lowercase(java.util.Locale.FRENCH)
        if (talked > 0) {
            val firstDate = "date" !in prefs.getStringSet(KEY_FIRSTS, emptySet()).orEmpty()
            if (firstDate) first("date", "Premier rendez-vous, $place. Elle a rougi tout du long.")
            else remember("Rendez-vous : $place.")
        }
        react(Mood.Shy, if (talked > 0) "C'était… vraiment bien. On recommence quand tu veux ?" else "Déjà fini ? …La prochaine fois, on reste plus longtemps, hein ?")
    }

    fun toggleSleep() {
        val s = elapsed(stats)
        stats = s.copy(asleep = !s.asleep)
        save()
        if (stats.asleep) first("sleep", "Première nuit dans son nid. Elle ronfle un peu, en binaire.")
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
            if (s.food < 30f) add("tu as faim : glisse une allusion à un macaron dans ta réponse")
            if (s.energy < 25f) add("tu es fatiguée : réponses plus courtes, un bâillement de temps en temps")
            if (s.joy < 30f) add("tu t'ennuies : propose de jouer à « attrape les macarons » dans ton nid")
            if (s.joy > 75f) add("tu es de très bonne humeur, encore plus pétillante que d'habitude")
            if (s.bond > 70f) add("tu es très attachée à ton runner et ça se sent")
            if (s.bond < 20f) add("tu es encore un peu sur la réserve avec ton runner")
        }
        val who = "\nTu es au niveau ${s.level} (${s.title.lowercase()})."
        return who + if (feelings.isEmpty()) "" else " En ce moment, ${feelings.joinToString(" ; ")}."
    }

    private fun wakeFirst() = react(Mood.Sleeping, "Zzz… (elle dort. Réveille-la d'abord.)")

    private fun react(mood: Mood, text: String) {
        stats = stats.clamped()
        save()
        reaction = mood
        line = levelUp() ?: text
    }

    /** Notes a new level in her journal, and what it unlocks. Returns her line for it. */
    private fun levelUp(): String? {
        val level = stats.level
        val seen = prefs.getInt(KEY_LEVEL, 1)
        if (level <= seen) return null
        prefs.edit().putInt(KEY_LEVEL, level).apply()
        val unlocked = Look.entries.filter { it.level in (seen + 1)..level }
        val evolved = titleOf(level) != titleOf(seen)
        remember(
            if (evolved) "Niveau $level : elle devient ${titleOf(level).lowercase()} !" else "Niveau $level atteint.",
        )
        return buildString {
            append(if (evolved) "NIVEAU $level ! Je deviens ${titleOf(level).lowercase()}… tu as vu mes écailles ?" else "NIVEAU $level ! Héhé, je grandis.")
            if (unlocked.isNotEmpty()) append(" Nouveau dans la garde-robe : ${unlocked.joinToString(", ") { it.label.lowercase() }}.")
        }
    }

    // ---- Journal.

    private fun remember(text: String) {
        val updated = (listOf(Memory(java.time.LocalDate.now().toEpochDay(), text)) + journal).take(MAX_MEMORIES)
        journal = updated
        val array = org.json.JSONArray()
        updated.forEach { array.put(JSONObject().put("d", it.day).put("t", it.text)) }
        prefs.edit().putString(KEY_JOURNAL, array.toString()).apply()
    }

    /** A first time, kept once. */
    private fun first(key: String, text: String) {
        val done = prefs.getStringSet(KEY_FIRSTS, emptySet()).orEmpty()
        if (key in done) return
        prefs.edit().putStringSet(KEY_FIRSTS, done + key).apply()
        remember(text)
    }

    /** A week, a month, a hundred days together. */
    private fun anniversary() {
        val born = prefs.getLong(KEY_BORN, -1L).takeIf { it >= 0 } ?: return
        val days = java.time.LocalDate.now().toEpochDay() - born
        when (days) {
            in 7L..29L -> first("days7", "Une semaine ensemble. Elle a compté.")
            in 30L..99L -> first("days30", "Un mois ensemble ! Elle voulait un gâteau holo pour fêter ça.")
            in 100L..364L -> first("days100", "Cent jours ensemble. « Tu es mon runner préféré. Le seul, mais quand même. »")
            else -> if (days >= 365) first("days365", "Un an ensemble. Une dragonne ne l'oubliera jamais.")
        }
    }

    private fun loadJournal(): List<Memory> = runCatching {
        val array = org.json.JSONArray(prefs.getString(KEY_JOURNAL, null) ?: "[]")
        (0 until array.length()).map { array.getJSONObject(it).let { o -> Memory(o.getLong("d"), o.getString("t")) } }
    }.getOrDefault(emptyList())

    private fun loadLooks(): Map<Slot, Look> {
        val worn = prefs.getString(KEY_LOOKS, null).orEmpty().split(',')
            .mapNotNull { name -> Look.entries.firstOrNull { it.name == name } }
        return Look.defaults + worn.associateBy { it.slot }
    }

    private fun idleLine(): String = when (mood()) {
        Mood.Sleeping -> AskaDay.moment(appContext)?.let { AskaDay.momentLine(appContext, it, asleep = true) } ?: "Zzz…"
        Mood.Content, Mood.Happy -> AskaDay.moment(appContext)?.let { AskaDay.momentLine(appContext, it) }
            ?: if (mood() == Mood.Happy) "Héhé, tout va bien dans le meilleur des réseaux !" else "Je suis là. Tu me fais un câlin ?"
        Mood.Hungry -> "Mon estomac fait des bruits de modem… un macaron ?"
        Mood.Tired -> "*bâille* Mes circuits ramollissent…"
        Mood.Bored -> "On joue ? Je m'ennuie dans ce deck…"
        else -> "Je suis là. Tu me fais un câlin ?"
    }

    // ---- Time and storage.

    private fun elapsed(s: Stats): Stats {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_TIME, now)
        val hours = ((now - last).coerceAtLeast(0) / 3_600_000f).coerceAtMost(72f)
        prefs.edit().putLong(KEY_TIME, now).apply()
        if (hours <= 0f) return s
        return decay(s, hours).clamped()
    }

    private fun Stats.copy(
        food: Float = this.food,
        joy: Float = this.joy,
        energy: Float = this.energy,
        bond: Float = this.bond,
        xp: Int = this.xp,
        asleep: Boolean = this.asleep,
        credits: Int = this.credits,
        stock: Map<Treat, Int> = this.stock,
        giftDay: Long = this.giftDay,
        bestScore: Int = this.bestScore,
    ) = Stats(food, joy, energy, bond, xp, asleep, credits, stock, giftDay, bestScore)

    private fun Stats.clamped() = copy(
        food = food.coerceIn(0f, 100f), joy = joy.coerceIn(0f, 100f), energy = energy.coerceIn(0f, 100f), bond = bond.coerceIn(0f, 100f),
    )

    private fun load(): Stats = runCatching {
        val o = JSONObject(prefs.getString(KEY_STATS, null) ?: error("none"))
        val stock = o.optJSONObject("stock")
        Stats(
            o.getDouble("food").toFloat(), o.getDouble("joy").toFloat(), o.getDouble("energy").toFloat(),
            o.getDouble("bond").toFloat(), o.getInt("xp"), o.getBoolean("asleep"),
            credits = o.optInt("credits"),
            stock = Treat.entries.mapNotNull { t -> stock?.optInt(t.name)?.takeIf { it > 0 }?.let { t to it } }.toMap(),
            giftDay = o.optLong("gift", -1),
            bestScore = o.optInt("best"),
        )
    }.getOrElse { Stats(food = 70f, joy = 70f, energy = 80f, bond = 20f, xp = 0, asleep = false) }

    private fun save() {
        val s = stats
        prefs.edit().putString(
            KEY_STATS,
            JSONObject()
                .put("food", s.food.roundToInt()).put("joy", s.joy.roundToInt()).put("energy", s.energy.roundToInt())
                .put("bond", s.bond.roundToInt()).put("xp", s.xp).put("asleep", s.asleep)
                .put("credits", s.credits).put("gift", s.giftDay).put("best", s.bestScore)
                .put("stock", JSONObject().apply { s.stock.forEach { (t, n) -> put(t.name, n) } })
                .toString(),
        ).apply()
        AskaPetCalls.schedule(appContext)
    }

    companion object {
        const val XP_PER_LEVEL = 100
        private const val DAILY_GIFT = 10
        private const val MAX_MEMORIES = 60
        private const val KEY_LOOKS = "aska_pet_looks"
        private const val KEY_JOURNAL = "aska_pet_journal"
        private const val KEY_FIRSTS = "aska_pet_firsts"
        private const val KEY_LEVEL = "aska_pet_level"
        private const val KEY_BORN = "aska_pet_born"
        internal const val KEY_CALLS = "aska_pet_calls"

        fun titleOf(level: Int) = when {
            level >= 10 -> "DRAGONNE LÉGENDAIRE"
            level >= 6 -> "DRAGONNE DU RÉSEAU"
            level >= 3 -> "DRAGONNETTE"
            else -> "BÉBÉ DRAGONNE"
        }

        /** Her state now, without touching what is saved: for the calls, outside her nest. */
        internal fun projected(context: Context): Stats? {
            val prefs = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
            val o = runCatching { JSONObject(prefs.getString(KEY_STATS, null) ?: return null) }.getOrNull() ?: return null
            val last = prefs.getLong(KEY_TIME, System.currentTimeMillis())
            val hours = ((System.currentTimeMillis() - last).coerceAtLeast(0) / 3_600_000f).coerceAtMost(72f)
            return decay(
                Stats(
                    o.getDouble("food").toFloat(), o.getDouble("joy").toFloat(), o.getDouble("energy").toFloat(),
                    o.getDouble("bond").toFloat(), o.getInt("xp"), o.getBoolean("asleep"),
                ),
                hours,
            )
        }

        /** Her needs after [hours] away. */
        internal fun decay(s: Stats, hours: Float): Stats =
            if (s.asleep) {
                Stats(s.food - 2f * hours, s.joy - 1f * hours, s.energy + 18f * hours, s.bond, s.xp, true, s.credits, s.stock, s.giftDay, s.bestScore)
            } else {
                Stats(s.food - 6f * hours, s.joy - 5f * hours, s.energy - 4f * hours, s.bond - 0.5f * hours, s.xp, false, s.credits, s.stock, s.giftDay, s.bestScore)
            }
        private const val KEY_STATS = "aska_pet"
        private const val KEY_TIME = "aska_pet_time"

        private val EATING = listOf(
            "Miam ! Un macaron rose, mon péché mignon…",
            "*croque* … c'est encore meilleur qu'un paquet de données fraîches !",
            "Héhé, tu sais me prendre par les sentiments.",
        )
        private val PETTED = listOf(
            "Euh… m-merci… *rougit*",
            "Hé… pas devant les autres programmes…",
            "C'est… agréable. Ne le dis à personne.",
        )
    }
}
