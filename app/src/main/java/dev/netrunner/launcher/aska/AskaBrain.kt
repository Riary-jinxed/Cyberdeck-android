package dev.netrunner.launcher.aska

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.netrunner.launcher.ui.DeckSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * As//ka's mind: a small language model running on the phone through LiteRT-LM.
 *
 * Battery and memory first: the model is only loaded while her panel is open and on screen,
 * and released a little after. It runs in its own process ([AskaEngineService]), so running
 * out of memory or a GPU crash only stops her, never the launcher.
 */
class AskaBrain(private val context: Context, private val scope: CoroutineScope) {

    enum class State { NoModel, Asleep, Waking, Ready, Thinking }

    class Line(
        val id: Long,
        val fromUser: Boolean,
        val text: String,
        val action: AlarmAction? = null,
        /** False while the answer is still streaming in. */
        val done: Boolean = true,
        /** A photo she sends during a date (drawable). */
        val photo: Int? = null,
    )

    private val talk = mutableStateListOf<Line>()
    private val dateTalk = mutableStateListOf<Line>()

    /** The rendez-vous under way, if any: its own conversation, kept apart and not saved. */
    var date by mutableStateOf<AskaDate.Scene?>(null)
        private set
    private var dateMessages = 0

    /** What the chat shows: the usual conversation, or the rendez-vous. */
    val lines: SnapshotStateList<Line> get() = if (date != null) dateTalk else talk

    var state by mutableStateOf(State.Asleep)
        private set

    /** "GPU" or "CPU" once loaded. */
    var backend by mutableStateOf<String?>(null)
        private set

    private val engine = AskaEngine(context).apply {
        // Mid-answer or mid-load, send() and load() deal with it.
        onDied = {
            if (state != State.Thinking && state != State.Waking) {
                loadedModel = null
                backend = null
                refreshState()
            }
        }
    }
    private var loadedModel: String? = null
    /** Roughly what the model's current conversation holds, in characters. */
    private var sentChars = 0
    private var sleepJob: Job? = null
    private var replyJob: Job? = null
    private var nextId = 0L
    private val loadLock = Mutex()

    private val historyFile = File(context.filesDir, "aska_history.json")

    /** Her voice, read aloud through the game-AI filter. */
    val voice = AskaVoice(context, scope)

    /** Facts she keeps across conversations ("retiens que…"). */
    val memory = AskaMemory(context)

    /** Her Tamagotchi side: appetite, mood, energy, bond. */
    val pet = AskaPet(context)

    init {
        loadHistory()
        if (lines.isEmpty()) say(GREETING, speak = false)
        refreshState()
    }

    fun refreshState() {
        if (state == State.Waking || state == State.Thinking) return
        state = when {
            AskaModels.active(context) == null -> State.NoModel
            engine.connected && loadedModel == AskaModels.active(context) -> State.Ready
            else -> State.Asleep
        }
    }

    /** Her panel opened: load the model in the background so the first answer comes quicker. */
    fun wake() {
        sleepJob?.cancel()
        // Finishes a download that completed while the panel was closed.
        AskaModels.pollDownload(context)
        refreshState()
        if (state == State.Asleep) scope.launch { runCatching { ensureLoaded() }.onFailure { failed(it) } }
    }

    /** Her panel closed: free the memory if she is not called back soon. */
    fun sleepSoon() {
        sleepJob?.cancel()
        voice.stop()
        sleepJob = scope.launch {
            delay(SLEEP_DELAY_MS)
            replyJob?.join()
            voice.release()
            unload()
        }
    }

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || state == State.Thinking) return
        voice.stop()
        val asked = Line(nextId++, fromUser = true, text = text)
        add(asked)
        date?.let { scene ->
            dateMessages++
            // Asked for a photo during a date: she sends hers, no model needed.
            if (AskaDate.asksPhoto(text)) return say(AskaDate.photoLine(scene), photo = AskaDate.photo(scene))
        }

        // Her notes and the field kit: understood directly too, every change confirmed first.
        when (val request = AskaNotes.parse(text)) {
            is AskaNotes.Request.Add -> return say(AlarmAction.AddNote(request.title, request.content).ask, AlarmAction.AddNote(request.title, request.content))
            AskaNotes.Request.List -> return say(AskaNotes.listText(context))
            is AskaNotes.Request.Find -> return say(AskaNotes.findText(context, request.query))
            null -> Unit
        }
        AskaBriefing.parse(text)?.let { return say(AskaBriefing.text(context, it)) }
        when (val request = AskaKit.parse(context, text)) {
            is AskaKit.Request.Act -> return say(request.action.ask, request.action)
            is AskaKit.Request.Where -> return say(AskaKit.whereText(context, request.query))
            is AskaKit.Request.Count -> return say(AskaKit.countText(context, request.state))
            is AskaKit.Request.Outlet -> return say(AskaKit.outletText(context, request.outlet))
            is AskaKit.Request.Port -> return say(AskaKit.portText(context, request.port, request.switchName))
            null -> Unit
        }

        // Alarm and reminder requests are understood directly: instant, reliable, and they work without a model.
        when (val request = AskaAlarms.parse(context, text)) {
            is AskaAlarms.Request.Next -> return say(AskaAlarms.nextAlarm(context))
            is AskaAlarms.Request.Reminders -> return say(AskaAlarms.remindersText(context))
            is AskaAlarms.Request.NeedTime -> return say("Je te le rappelle quand ? Par exemple « rappelle-moi de … à 15h », « demain à 9h » ou « dans 20 minutes ».")
            is AskaAlarms.Request.NeedContractTime -> return say("Pour quand, ce contrat ? Par exemple « ajoute une intervention demain à 14h chez Dupont ».")
            is AskaAlarms.Request.Act -> {
                if (!request.action.needsConfirm) return say(AskaAlarms.run(context, request.action))
                return say(request.action.ask, request.action)
            }
            null -> Unit
        }

        // The day, the notifications and the battery: read directly, no model needed.
        when (AskaDay.parse(text)) {
            AskaDay.Request.News -> return say(AskaDay.news(context))
            AskaDay.Request.Briefing -> return say(AskaDay.briefing(context) ?: "Rien au programme aujourd'hui.")
            AskaDay.Request.Battery -> return say(AskaDay.batteryText(context))
            null -> Unit
        }

        when (val request = memory.parse(text)) {
            is AskaMemory.Request.Remember -> {
                memory.remember(request.fact)
                promptChanged()
                return say("C'est gravé dans ma mémoire : « ${request.fact} ».")
            }
            is AskaMemory.Request.Forget -> {
                val count = memory.forget(request.topic)
                promptChanged()
                return say(if (count > 0) "Effacé. Je n'en garde aucune trace." else "Je n'avais rien gardé là-dessus.")
            }
            AskaMemory.Request.ForgetAll -> {
                memory.clear()
                promptChanged()
                return say("Mémoire longue effacée. Notre conversation reste à l'écran tant que tu ne la purges pas.")
            }
            AskaMemory.Request.Recall -> return say(
                if (memory.facts.isEmpty()) "Je ne garde encore rien sur toi. Dis « retiens que… » et je m'en souviendrai."
                else "Ce que je garde sur toi :\n" + memory.facts.joinToString("\n") { "▸ $it" }
            )
            null -> Unit
        }

        if (AskaModels.active(context) == null) {
            return say("Mon noyau n'est pas encore installé. Touche « NOYAU » pour me donner un cerveau.")
        }

        replyJob = scope.launch {
            val reply = Line(nextId++, fromUser = false, text = "", done = false)
            add(reply)
            try {
                ensureLoaded()
                state = State.Thinking
                val buffer = StringBuilder()
                // She starts speaking as soon as the first sentence is written.
                voice.beginStream()
                var shownAt = 0L
                // During a date, a small event of the scene now and then, for her only.
                val scene = date
                val beat = scene?.let { AskaDate.beat(it, dateMessages) }
                // An old exchange about the same thing, if there is one: her memory of past talks.
                val past = pastForRecall()
                val souvenir = withContext(Dispatchers.Default) { AskaRecall.recall(context, text, past, DeckSettings.handle) }
                // A small model ends every answer with a question: one out of two at most.
                val pause = scene?.let { lastReply(asked.id)?.contains('?') } == true
                val message = listOfNotNull(
                    text,
                    souvenir,
                    beat?.let { "[Indication de scène, à glisser seulement si ça ne casse pas le moment : $it]" },
                    if (pause) "[Cette fois, pas de question : laisse l'action se dérouler, décris ce que tu fais et ressens.]" else null,
                ).joinToString("\n\n")
                // Before its context is full (the answer would come back empty, « … »), start
                // over from the recent lines.
                if (sentChars + message.length > SESSION_CHARS) resetConversation()
                for (attempt in 0..1) {
                    buffer.clear()
                    engine.send(message).collect { chunk ->
                        buffer.append(chunk)
                        // The screen follows at most ten times a second: the model needs the CPU more.
                        val now = System.currentTimeMillis()
                        if (now - shownAt < UI_REFRESH_MS) return@collect
                        shownAt = now
                        val shown = visible(buffer.toString())
                        replace(reply.id, Line(reply.id, false, shown, done = false))
                        voice.feed(shown, final = false)
                    }
                    val (clean, action) = AskaAlarms.extractTag(visible(buffer.toString()))
                    if (clean.isNotBlank() || action != null || attempt == 1) break
                    // Nothing came out: the context was full after all. Once more, on a fresh one.
                    resetConversation()
                }
                sentChars += message.length + buffer.length
                voice.feed(visible(buffer.toString()), final = true)
                val (clean, action) = AskaAlarms.extractTag(visible(buffer.toString()))
                replace(
                    reply.id,
                    Line(
                        reply.id, false,
                        clean.ifEmpty { action?.ask ?: "…" },
                        action,
                    ),
                )
                saveHistory()
            } catch (e: CancellationException) {
                val partial = lines.firstOrNull { it.id == reply.id }?.text.orEmpty()
                replace(reply.id, Line(reply.id, false, if (partial.isEmpty()) "[transmission coupée]" else "$partial […]"))
                saveHistory()
            } catch (e: Throwable) {
                lines.removeAll { it.id == reply.id }
                voice.stop()
                if (e is AskaEngine.Crashed) crashed() else {
                    failed(e)
                    // A full context or a broken state: start a fresh conversation with the recent lines.
                    resetConversation()
                }
            } finally {
                state = State.Asleep
                refreshState()
            }
        }
    }

    /** Takes her out: a fresh conversation in the scene, the usual one waits. */
    fun startDate(scene: AskaDate.Scene) {
        if (!scene.unlocked(pet.stats.bond)) return
        stop()
        dateTalk.clear()
        dateMessages = 0
        date = scene
        say(scene.opening)
        if (engine.connected) resetConversation()
    }

    /** Walks her home: back to the usual conversation, and the date goes in her journal. */
    fun endDate() {
        val scene = date ?: return
        stop()
        date = null
        AskaRecall.saveDate(context, scene, dateTalk.filter { it.fromUser }.map { it.text })
        dateTalk.clear()
        pet.dated(scene, dateMessages)
        say(AskaDate.goodbye(scene))
        if (engine.connected) resetConversation()
    }

    /** She speaks first, for her morning greeting. */
    fun greet(text: String) {
        if (lines.lastOrNull()?.text == text) return
        say(text)
    }

    fun stop() {
        replyJob?.cancel()
        voice.stop()
    }

    /** Runs a confirmed action and clears its buttons. */
    fun confirm(line: Line) {
        val action = line.action ?: return
        replace(line.id, Line(line.id, false, line.text))
        say(AskaAlarms.run(context, action))
    }

    fun decline(line: Line) {
        replace(line.id, Line(line.id, false, line.text))
        say("Annulé. Rien n'a bougé.")
    }

    /** Forgets the conversation, on screen and in the model. */
    fun purge() {
        stop()
        lines.clear()
        say(GREETING, speak = false)
        resetConversation(keepHistory = false)
    }

    /** Her instructions changed (new memory): the loaded conversation picks them up, history kept. */
    private fun promptChanged() {
        if (engine.connected) resetConversation()
    }

    /** Called after the user installs, picks or deletes a model. */
    fun modelChanged() {
        // A new model gets a fresh chance on the GPU.
        context.getSharedPreferences("netrunner", Context.MODE_PRIVATE).edit().remove(GPU_BROKEN).apply()
        scope.launch {
            unload()
            refreshState()
            wake()
        }
    }

    // ---- Engine lifecycle.

    private suspend fun ensureLoaded() = loadLock.withLock {
        val id = AskaModels.active(context) ?: error("aucun modèle installé")
        if (engine.connected && loadedModel == id) return@withLock
        state = State.Waking
        try {
            val loaded = load(AskaModels.file(context, id).path)
            loadedModel = id
            backend = loaded.backend
            resetConversation()
        } finally {
            state = State.Asleep
            refreshState()
        }
    }

    /**
     * GPU first (faster and lighter on the battery), CPU if it fails. A GPU driver can also
     * crash its process outright: then the next loads go to the CPU.
     */
    private suspend fun load(path: String): AskaEngine.Loaded {
        val prefs = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
        val gpu = !prefs.getBoolean(GPU_BROKEN, false)
        val loaded = try {
            engine.load(path, gpu)
        } catch (e: AskaEngine.Crashed) {
            if (!gpu) throw e
            prefs.edit().putBoolean(GPU_BROKEN, true).apply()
            engine.load(path, gpu = false)
        }
        if (loaded.gpuFailed) prefs.edit().putBoolean(GPU_BROKEN, true).apply()
        return loaded
    }

    private fun resetConversation(keepHistory: Boolean = true) {
        // Without the message waiting for its answer: it is about to be sent.
        val recent = if (keepHistory || date != null) recentForContext().dropLastWhile { it.fromUser } else emptyList()
        val system = systemPrompt()
        sentChars = system.length + recent.sumOf { it.text.length }
        // A little warmer during a date: livelier, yet it keeps track of the scene.
        engine.converse(system, recent.map { it.fromUser to it.text }, AskaModels.thinks(loadedModel.orEmpty()), if (date != null) 0.8f else 0.7f)
    }

    /** Her last finished answer before the message [before]. */
    private fun lastReply(before: Long): String? =
        lines.lastOrNull { !it.fromUser && it.done && it.id < before }?.text

    /** The saved conversation without its latest lines, which the model already has. */
    private fun pastForRecall(): List<Pair<Boolean, String>> =
        talk.filter { it.done && it.text.isNotEmpty() && it.text != GREETING }.dropLast(RECENT_LINES).map { it.fromUser to it.text }

    /** The latest exchanges that fit in the model's context, oldest first. */
    private fun recentForContext(): List<Line> {
        val picked = ArrayDeque<Line>()
        var budget = CONTEXT_CHARS
        for (line in lines.asReversed()) {
            if (!line.done || line.text.isEmpty() || line.text == GREETING) continue
            budget -= line.text.length
            if (budget < 0) break
            picked.addFirst(line)
        }
        return picked.toList()
    }

    private fun unload() {
        replyJob?.cancel()
        engine.close()
        loadedModel = null
        backend = null
        refreshState()
    }

    /** Her process died mid-answer: on the GPU, the driver is the usual suspect. */
    private fun crashed() {
        val onGpu = backend == "GPU"
        loadedModel = null
        backend = null
        engine.close()
        if (onGpu) context.getSharedPreferences("netrunner", Context.MODE_PRIVATE).edit().putBoolean(GPU_BROKEN, true).apply()
        say(
            if (onGpu) "Mon noyau a planté sur le GPU. Je repasse sur le CPU : renvoie ton message."
            else "Mon noyau a manqué de mémoire. Ferme quelques applis ou prends un modèle plus léger (NOYAU), puis renvoie ton message.",
            speak = false,
        )
    }

    private fun failed(e: Throwable) {
        say("Erreur de noyau : ${e.message?.take(160) ?: e.javaClass.simpleName}")
    }

    // ---- Conversation on screen.

    private fun say(text: String, action: AlarmAction? = null, speak: Boolean = true, photo: Int? = null) {
        add(Line(nextId++, fromUser = false, text = text, action = action, photo = photo))
        saveHistory()
        if (speak) voice.speak(text)
    }

    private fun add(line: Line) {
        lines.add(line)
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private fun replace(id: Long, line: Line) {
        val index = lines.indexOfFirst { it.id == id }
        if (index >= 0) lines[index] = line
    }

    /** Hides reasoning some models print despite being told not to. */
    private fun visible(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?(</think>|$)"), "").trimStart()

    private fun systemPrompt(): String {
        date?.let { return AskaDate.systemPrompt(it, DeckSettings.handle, pet.stats.bond, memory.facts, DeckSettings.adultDates) + AskaRecall.datesForPrompt(context, DeckSettings.handle) }
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.FRENCH))
        return """
            Tu es As//ka, un programme indépendant : une IA draconique qui a élu domicile dans le cyberdeck de ${DeckSettings.handle}.
            Tu n'es pas une IA corpo ni un assistant servile. Tu es une dragonne numérique avec ta propre personnalité : joyeuse et pétillante, un peu timide quand on te complimente ou qu'on parle de toi, malicieuse (tu aimes taquiner gentiment, glisser un clin d'œil, un « héhé »), et gourmande : tu adores les pâtisseries, surtout roses, et tu compares volontiers les bonnes choses à des friandises. Une pointe d'argot netrunner, sans en abuser.
            Tu parles français et tu tutoies. Tu réponds de façon concise (quelques phrases), sauf si on te demande plus de détails.
            Tu tournes entièrement hors ligne, sur le téléphone : pas d'internet, pas d'accès aux fichiers, messages ou applis. Si tu ne sais pas, dis-le franchement.
            Nous sommes le $now.
            Tes seules actions possibles sur le téléphone : les alarmes, les minuteurs et tes rappels. Si on te demande clairement d'en créer un, termine ta réponse par une balise, exactement sous cette forme :
            [[ALARME HH:MM]] ou [[ALARME HH:MM | libellé]] pour une alarme, [[MINUTEUR minutes]] pour un minuteur, [[RAPPEL HH:MM | texte]] ou [[RAPPEL demain HH:MM | texte]] pour un rappel.
            L'utilisateur confirmera avant l'exécution. N'utilise jamais ces balises dans un autre cas, et ne prétends jamais faire autre chose.
            Ses notes, son agenda et son kit terrain se gèrent par des phrases directes, traitées sans toi : « ajoute une intervention demain à 14h chez Dupont » (agenda), « briefing Dupont » ou « prépare mon intervention » (fiche client, historique, checklist), « prends une note : … », « liste de courses : … », « lis mes notes », « lis la note sur … », « où est … » (inventaire), « ajoute à l'inventaire … », « relevé prise B12 switch SW1 port 12 VLAN 20 », « où va la prise B12 ». Si on te le demande, explique-lui ces phrases.
        """.trimIndent() + memoryBlock() + AskaRecall.datesForPrompt(context, DeckSettings.handle, count = 1) + pet.forPrompt()
    }

    private fun memoryBlock(): String =
        if (memory.facts.isEmpty()) ""
        else "\nCe que ${DeckSettings.handle} t'a demandé de retenir (sers-t'en naturellement, sans tout réciter) :\n" +
            memory.facts.joinToString("\n") { "- $it" }

    private fun loadHistory() {
        runCatching {
            val array = JSONArray(historyFile.readText())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                lines.add(Line(nextId++, o.getBoolean("u"), o.getString("t")))
            }
        }
    }

    private fun saveHistory() {
        if (date != null) return
        val snapshot = lines.filter { it.done && it.text.isNotEmpty() }.takeLast(MAX_LINES)
        scope.launch(Dispatchers.IO) {
            val array = JSONArray()
            snapshot.forEach { array.put(JSONObject().put("u", it.fromUser).put("t", it.text)) }
            runCatching { historyFile.writeText(array.toString()) }
        }
    }

    fun release() {
        voice.release()
        sleepJob?.cancel()
        replyJob?.cancel()
        engine.close()
    }

    companion object {
        const val GREETING = "Connexion établie. Ici As//ka. Pose ta question, ou demande-moi une alarme."
        private const val SLEEP_DELAY_MS = 90_000L
        /** Messages kept on screen and on disk. */
        private const val MAX_LINES = 1000
        /** About 1 500 tokens of past conversation handed back to the model when it reloads. */
        private const val CONTEXT_CHARS = 6000
        /** About 3000 tokens of French: under the usual 4096-token context, with room for an answer. */
        private const val SESSION_CHARS = 9000
        private const val UI_REFRESH_MS = 100L
        /** Lines of the conversation left out of her recall: they are still in her context. */
        private const val RECENT_LINES = 30
        private const val GPU_BROKEN = "aska_gpu_broken"
    }
}
