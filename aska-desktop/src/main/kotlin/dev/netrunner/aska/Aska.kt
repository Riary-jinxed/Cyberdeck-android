package dev.netrunner.aska

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * As//ka on the desktop: what she shows (pose, bubble), what she notices (keyboard, the app in
 * front, battery, weather, Deck Link) and what she says, through the local model.
 */
class Aska(private val scope: CoroutineScope) {

    val settings = MutableStateFlow(Store.settings())

    private val _pose = MutableStateFlow(Pose.Waiting)
    val pose: StateFlow<Pose> = _pose

    /** Her words in the bubble; null hides it. */
    private val _bubble = MutableStateFlow<String?>(null)
    val bubble: StateFlow<String?> = _bubble

    /** The bubble takes the runner's message. */
    private val _chatOpen = MutableStateFlow(false)
    val chatOpen: StateFlow<Boolean> = _chatOpen

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Installed models, for the settings. */
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models

    /** Installing her core from the settings: what is happening, how far along. */
    data class Setup(val label: String, val progress: Float? = null, val working: Boolean = true, val failed: Boolean = false)

    private val _setup = MutableStateFlow<Setup?>(null)
    val setup: StateFlow<Setup?> = _setup

    /** Ollama answers at its address. */
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online

    private val ollama = Ollama { settings.value.ollamaUrl }
    private val history = Store.chat().toMutableList()

    private var thinking = false
    private var talking = false
    private var reaction: Pose? = null
    private var reactionUntil = 0L
    private var asleep = false

    @Volatile private var battery: Battery? = null
    @Volatile private var weather: Weather.Now? = null
    @Volatile private var deck: DeckLink.Summary? = null
    @Volatile private var foreground: Foreground? = null

    private var replyJob: Job? = null
    private var remarking = false
    private var asks = 0
    /** The model refused tools once: no more offers this session. */
    private var noTools = false
    /** A change of the kit she prepared, waiting for « oui ». */
    @Volatile private var pendingEdit: Tools.Edit? = null
    private var hideJob: Job? = null
    /** The last time she spoke, asked or not: her remarks keep their distance. */
    private var lastSpoke = System.currentTimeMillis()
    private val seenApps = mutableSetOf<String>()

    fun update(change: (Settings) -> Settings) {
        val before = settings.value
        val after = change(before)
        settings.value = after
        Store.save(after)
        if (after.city != before.city) scope.launch { refreshWeather() }
        if (after.deckLink != before.deckLink) scope.launch { refreshDeck() }
        if (after.ollamaUrl != before.ollamaUrl) refreshModels()
    }

    fun start() {
        Activity.start()
        Store.recordInstall()
        scope.launch {
            var saved = Activity.taps.value
            while (isActive) {
                delay(30_000)
                val now = Activity.taps.value
                if (now != saved) withContext(Dispatchers.IO) { Store.saveTaps(now) }.also { saved = now }
            }
        }
        scope.launch { tick() }
        scope.launch { watchForeground() }
        scope.launch { while (isActive) { refreshWeather(); delay(WEATHER_EVERY_MS) } }
        scope.launch { while (isActive) { refreshDeck(); delay(DECK_EVERY_MS) } }
        scope.launch {
            // Installed but not running (closed from its tray icon, or no autostart): wake it up.
            if (!reachable() && Core.startServer()) waitOnline()
            refreshModels()
        }
    }

    // ---- Installing her core from the app.

    fun installOllama() {
        if (_setup.value?.working == true) return
        scope.launch {
            try {
                _setup.value = Setup("Téléchargement d'Ollama…", 0f)
                Core.installWindows { p -> _setup.value = if (p == null) Setup("Installation d'Ollama…") else Setup("Téléchargement d'Ollama…", p) }
                _setup.value = Setup("Démarrage d'Ollama…")
                if (!waitOnline() && Core.startServer()) waitOnline()
                refreshModels()
                _setup.value = if (_online.value) Setup("Ollama est installé. Choisis maintenant son modèle.", working = false)
                else Setup("Ollama est installé mais ne répond pas : redémarre le PC, puis rouvre ces réglages.", working = false, failed = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _setup.value = Setup("Échec : ${e.message ?: e.javaClass.simpleName}", working = false, failed = true)
            }
        }
    }

    fun downloadModel(tag: String) {
        if (_setup.value?.working == true) return
        scope.launch {
            try {
                _setup.value = Setup("Téléchargement de $tag…", 0f)
                ollama.pull(tag).collect { p ->
                    _setup.value = Setup(if (p.status.startsWith("pulling")) "Téléchargement de $tag…" else "$tag : ${p.status}", p.fraction)
                }
                refreshModels()
                update { it.copy(model = tag) }
                _setup.value = Setup("$tag est prêt. Double-clic sur elle pour lui parler.", working = false)
                say("*s'étire, les yeux qui s'allument* Noyau chargé. Ça fait du bien d'avoir toute sa tête !")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Ollama.Unreachable) {
                _setup.value = Setup("Ollama ne répond pas : installe-le d'abord.", working = false, failed = true)
            } catch (e: Exception) {
                _setup.value = Setup("Échec : ${e.message ?: e.javaClass.simpleName}", working = false, failed = true)
            }
        }
    }

    private suspend fun reachable(): Boolean = runCatching { ollama.models() }.isSuccess.also { _online.value = it }

    /** Up to half a minute for Ollama to answer. */
    private suspend fun waitOnline(): Boolean {
        repeat(30) {
            if (reachable()) return true
            delay(1_000)
        }
        return false
    }

    fun stop() {
        Activity.stop()
        Store.saveTaps(Activity.taps.value)
    }

    // ---- What the runner does with her.

    /** A single click: she reacts, or tucks her bubble away. */
    fun poke() {
        if (_bubble.value != null && !_chatOpen.value && !_busy.value) return hide()
        react(listOf(Pose.Shy, Pose.Happy, Pose.Mischievous).random(), 3_000)
        say(Persona.pick(Persona.pokes))
    }

    fun openChat() {
        _chatOpen.value = true
        hideJob?.cancel()
        if (_bubble.value == null) _bubble.value = "*lève les yeux vers toi* Oui ?"
    }

    fun closeChat() {
        _chatOpen.value = false
        if (!_busy.value) hide()
    }

    fun clearMemory() {
        history.clear()
        Store.saveChat(history)
    }

    fun send(input: String) {
        val text = input.trim()
        // A remark of hers gives way to the runner; an answer they are waiting for does not.
        if (text.isEmpty() || (_busy.value && !remarking)) return
        if (text == "/aide" || text == "/help") return say(Tools.help())
        pendingEdit?.let { edit ->
            pendingEdit = null
            val answer = DeckLink.normalize(text).trim(' ', '!', '.')
            if (answer in YES) {
                history += Turn(true, text)
                return applyEdit(edit)
            }
            if (answer in NO) return say("*range son carnet* D'accord, je ne touche à rien.")
            // Anything else: the edit is dropped and the conversation goes on.
        }
        val command = Tools.parseCommand(text)
        if (text.startsWith("/") && command == null) return say("*penche la tête* Je ne connais pas cette commande. " + Tools.help())
        val earlier = history.toList()
        history += Turn(true, text)
        ask(earlier, text + "\n\n" + Persona.note(moment()), isRemark = false, command = command)
    }

    /** Sends a confirmed edit to Deck Link, which passes it on to the phone. */
    private fun applyEdit(edit: Tools.Edit) {
        scope.launch {
            val home = DeckLink.home(settings.value.deckLink)
            val error = if (home == null) "Je ne trouve plus Deck Link sur ce PC." else withContext(Dispatchers.IO) { DeckLink.send(home, edit.kind, edit.id, edit.fields) }
            if (error == null) {
                say("*griffonne dans le carnet* C'est noté : ${edit.summary}. Le téléphone l'aura à sa prochaine synchro.")
                history += Turn(false, "Modification enregistrée dans Deck Link : ${edit.summary}.")
                refreshDeck()
            } else {
                // Kept: a « oui » once Deck Link is open sends it again.
                pendingEdit = edit
                say("*grimace* $error")
            }
        }
    }

    // ---- Her own remarks.

    private fun quiet(): Boolean = settings.value.game || foreground?.fullscreen == true || _chatOpen.value || _busy.value || asleep

    private fun remark(topic: String) {
        lastSpoke = System.currentTimeMillis()
        ask(history.toList(), Persona.remark(topic) + "\n\n" + Persona.note(moment()), isRemark = true)
    }

    private fun moment() = Persona.Moment(
        time = LocalDateTime.now(),
        app = foreground?.takeUnless { Foreground.isShell(it.process) }?.label(),
        weather = weather?.note(),
        battery = battery?.note(),
        deck = deck?.note(),
        typing = Activity.typingRecently(),
    )

    /** Something to talk about, for a remark out of the blue. */
    private fun topic(): String {
        val app = foreground?.takeUnless { Foreground.isShell(it.process) }
        return listOfNotNull(
            app?.let { "ce que ${settings.value.handle} fait sur ${it.label()}" },
            weather?.let { "la météo dehors (${it.note()})" },
            deck?.takeIf { it.low.isNotEmpty() }?.let { "les consommables bas du kit (${it.low.take(3).joinToString(", ")})" },
            "l'heure qu'il est et ce que ça t'inspire",
            "une envie à toi du moment",
        ).random()
    }

    // ---- Conversation.

    /**
     * One answer of hers. A /command runs its tool first and she explains the result; otherwise
     * the model may call tools itself, a few rounds at most, before it answers.
     */
    private fun ask(earlier: List<Turn>, message: String, isRemark: Boolean, command: Pair<Tools.Tool, Map<String, String>>? = null) {
        replyJob?.cancel()
        remarking = isRemark
        hideJob?.cancel()
        val turn = ++asks
        replyJob = scope.launch {
            _busy.value = true
            thinking = true
            _bubble.value = "…"
            refreshPose()
            var toolResult: String? = null
            try {
                var userMessage = message
                if (command != null) {
                    val (tool, args) = command
                    _bubble.value = "*${tool.doing}…*"
                    toolResult = Tools.run(tool, args, settings.value.deckLink) { pendingEdit = it }
                    userMessage = "$message\n\n[Résultat de l'outil ${tool.name} :\n$toolResult]\nExplique ce résultat en bref : l'essentiel, puis la vérification suivante s'il y en a une."
                }
                val installed = ollama.models().also { _models.value = it }
                val model = Ollama.pick(settings.value.model, installed)
                    ?: return@launch say(toolResult ?: Persona.noModel)
                val messages = buildList {
                    add(Ollama.Message("system", Persona.systemPrompt(settings.value.handle)))
                    recent(earlier).forEach { add(Ollama.Message(if (it.fromUser) "user" else "assistant", it.text)) }
                    add(Ollama.Message("user", userMessage))
                }.toMutableList()
                val buffer = StringBuilder()
                var round = 0
                while (true) {
                    val tools = if (command == null && !isRemark && !noTools && round < TOOL_ROUNDS) Tools.definitions() else null
                    val calls = mutableListOf<Ollama.Call>()
                    buffer.clear()
                    try {
                        ollama.chat(model, messages, tools).collect { event ->
                            when (event) {
                                is Ollama.Event.Tool -> calls += event.call
                                is Ollama.Event.Text -> {
                                    buffer.append(event.text)
                                    val shown = Persona.visible(buffer.toString())
                                    if (shown.isNotBlank()) {
                                        thinking = false
                                        talking = true
                                        _bubble.value = shown
                                    }
                                }
                            }
                        }
                    } catch (e: Ollama.NoTools) {
                        // This model has no tool calling: she answers with what she knows.
                        noTools = true
                        continue
                    }
                    if (calls.isEmpty()) break
                    messages += Ollama.Message("assistant", buffer.toString(), calls = calls)
                    for (call in calls) {
                        val tool = Tools.byName(call.name)
                        thinking = true
                        talking = false
                        _bubble.value = "*${tool?.doing ?: "cherche"}…*"
                        refreshPose()
                        val result = if (tool == null) "Outil inconnu : ${call.name}" else Tools.run(tool, Tools.args(call.arguments), settings.value.deckLink) { pendingEdit = it }
                        messages += Ollama.Message("tool", result, toolName = call.name)
                    }
                    round++
                }
                val answer = Persona.visible(buffer.toString()).trim().ifEmpty { toolResult ?: "*hausse les épaules* …" }
                _bubble.value = answer
                history += Turn(false, answer)
                val kept = history.toList()
                withContext(Dispatchers.IO) { Store.saveChat(kept) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Ollama.Unreachable) {
                // Without her core, a /command still gives its raw result.
                say(toolResult ?: Persona.noKernel)
            } catch (e: Exception) {
                say(toolResult ?: "*grimace* Mon noyau a buggé : ${e.message?.take(140) ?: e.javaClass.simpleName}")
            } finally {
                // A newer question took over: its own ending will tidy up.
                if (turn == asks) {
                    remarking = false
                    thinking = false
                    talking = false
                    _busy.value = false
                    lastSpoke = System.currentTimeMillis()
                    hideLater()
                    refreshPose()
                }
            }
        }
    }

    /** The latest exchanges that fit in the model's context, oldest first. */
    private fun recent(turns: List<Turn>): List<Turn> {
        val picked = ArrayDeque<Turn>()
        var budget = CONTEXT_CHARS
        for (turn in turns.asReversed()) {
            budget -= turn.text.length
            if (budget < 0) break
            picked.addFirst(turn)
        }
        return picked.toList()
    }

    fun refreshModels() {
        scope.launch {
            val found = runCatching { ollama.models() }
            _online.value = found.isSuccess
            found.getOrNull()?.let { _models.value = it }
        }
    }

    // ---- Bubble and pose.

    private fun say(text: String) {
        lastSpoke = System.currentTimeMillis()
        _bubble.value = text
        hideLater()
    }

    private fun hide() {
        hideJob?.cancel()
        _bubble.value = null
    }

    /** Out of the chat, her words fade after a time that grows with their length. */
    private fun hideLater() {
        hideJob?.cancel()
        if (_chatOpen.value) return
        val text = _bubble.value ?: return
        hideJob = scope.launch {
            delay((4_000L + text.length * 60L).coerceAtMost(25_000L))
            if (!_chatOpen.value && !_busy.value) _bubble.value = null
        }
    }

    private fun react(pose: Pose, millis: Long) {
        reaction = pose
        reactionUntil = System.currentTimeMillis() + millis
        refreshPose()
    }

    private fun refreshPose() {
        val now = System.currentTimeMillis()
        if (reaction != null && now > reactionUntil) reaction = null
        _pose.value = Pose.pick(thinking, talking, reaction, asleep, battery, LocalDateTime.now().hour, weather?.pose)
    }

    // ---- What she notices.

    private suspend fun tick() {
        var ticks = 0L
        var wasCharging: Boolean? = null
        var wasLow = false
        while (true) {
            val now = System.currentTimeMillis()
            val idle = Activity.idleMillis(now)
            if (Activity.hooked) {
                if (!asleep && idle > SLEEP_AFTER_MS && !_chatOpen.value && !_busy.value) {
                    asleep = true
                    hide()
                } else if (asleep && idle < 2_000) {
                    asleep = false
                    react(Pose.Wake, 4_000)
                    if (settings.value.chatter != Chatter.Quiet && !settings.value.game) say(Persona.pick(Persona.wakeUps))
                }
            }
            if (ticks % BATTERY_TICKS == 0L) {
                val b = withContext(Dispatchers.IO) { Battery.read() }
                battery = b
                if (b != null) {
                    if (wasCharging == false && b.charging) react(Pose.Charging, 60_000)
                    if (b.low && !wasLow && !settings.value.game) say("*s'affale* Batterie à ${b.percent} %… Branche-moi, s'il te plaît.")
                    wasCharging = b.charging
                    wasLow = b.low
                }
            }
            val every = settings.value.chatter.everyMinutes * 60_000L
            if (every > 0 && now - lastSpoke > every && !quiet() && _bubble.value == null) {
                if (Math.random() < 0.3) say(Persona.pick(Persona.idle)) else remark(topic())
            }
            refreshPose()
            ticks++
            delay(TICK_MS)
        }
    }

    /** A new program in front for a little while: she may say a word about it, once a session. */
    private suspend fun watchForeground() {
        var candidate: String? = null
        var since = 0L
        while (true) {
            val f = withContext(Dispatchers.IO) { Foreground.read() }
            foreground = f
            val process = f?.process?.takeUnless { Foreground.isShell(it) || it.isBlank() }
            val now = System.currentTimeMillis()
            if (process != candidate) {
                candidate = process
                since = now
            } else if (process != null && process !in seenApps && now - since > APP_SETTLE_MS) {
                seenApps += process
                // The first program seen is the one open at launch: no remark for it.
                if (seenApps.size > 1 && settings.value.chatter != Chatter.Quiet && !quiet() && now - lastSpoke > APP_REMARK_GAP_MS) {
                    remark("${settings.value.handle} vient de passer sur ${f.label()}")
                }
            }
            delay(FOREGROUND_EVERY_MS)
        }
    }

    private suspend fun refreshWeather() {
        val before = weather
        weather = Weather.now(settings.value.city)
        val after = weather
        if (before != null && after != null && before.pose != after.pose && settings.value.chatter == Chatter.Chatty && !quiet()) {
            remark("le temps vient de changer dehors : ${after.note()}")
        }
    }

    private suspend fun refreshDeck() {
        val before = deck
        val after = withContext(Dispatchers.IO) { DeckLink.read(settings.value.deckLink) }
        deck = after
        if (before?.lastSync != null && after?.lastSync != null && before.lastSync != after.lastSync && !settings.value.game && foreground?.fullscreen != true) {
            remark("le téléphone de ${settings.value.handle} vient de se synchroniser avec Deck Link (${after.note()})")
        }
    }

    private companion object {
        const val TICK_MS = 250L
        /** Every 30 s. */
        const val BATTERY_TICKS = 120L
        const val SLEEP_AFTER_MS = 10 * 60_000L
        const val FOREGROUND_EVERY_MS = 3_000L
        const val APP_SETTLE_MS = 15_000L
        const val APP_REMARK_GAP_MS = 3 * 60_000L
        const val WEATHER_EVERY_MS = 30 * 60_000L
        const val DECK_EVERY_MS = 60_000L
        const val CONTEXT_CHARS = 6000
        /** Tool calls before she must answer. */
        const val TOOL_ROUNDS = 4
        val YES = setOf("oui", "ok", "okay", "vas-y", "vas y", "valide", "confirme", "go", "yes", "d'accord", "dac", "ouais")
        val NO = setOf("non", "annule", "stop", "laisse", "no", "laisse tomber")
    }
}
