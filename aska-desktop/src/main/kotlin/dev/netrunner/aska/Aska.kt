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
import java.io.File
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

    /** The window in front plays a video or a stream: she watches with the runner. */
    private val _watching = MutableStateFlow(false)
    val watching: StateFlow<Boolean> = _watching

    /** A small move at her desk (a yawn, a sign…), or a lasting one (focused, gamepad in hand). */
    private val _gesture = MutableStateFlow<Gesture?>(null)
    val gesture: StateFlow<Gesture?> = _gesture

    /** Music plays on the PC: she sways along. */
    private val _music = MutableStateFlow<String?>(null)
    val music: StateFlow<String?> = _music

    private val ollama = Ollama { settings.value.ollamaUrl }
    private val history = Store.chat().toMutableList()

    private var thinking = false
    private var talking = false
    private var reaction: Pose? = null
    private var passing: Gesture? = null
    private var passingUntil = 0L
    /** Pomodoro under way; null without one. */
    @Volatile private var focus: Focus? = null
    /** A game is running (Warframe, a Steam or Epic game in front). */
    @Volatile private var playing = false
    @Volatile private var warframeOn = false
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

    /** What her tools need from her. */
    private val host = object : Tools.Host {
        override val deckLink get() = settings.value.deckLink
        override fun propose(edit: Tools.Edit) { pendingEdit = edit }
        override fun song() = _music.value
        override fun pomodoro(action: String, minutes: Int?): String = when (action) {
            "stop", "arrete", "arrête", "fin" -> if (focus == null) "Aucun pomodoro en cours." else {
                val done = focus!!.rounds
                focus = null
                "Pomodoro arrêté après $done session(s)."
            }
            "status", "etat", "état" -> focus?.text() ?: "Aucun pomodoro en cours."
            else -> {
                val work = (minutes ?: 25).coerceIn(5, 120)
                focus = Focus(workMinutes = work)
                "Pomodoro lancé : $work min de concentration, puis 5 min de pause. Je me tais pendant le travail."
            }
        }
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
        scope.launch { watchWarframe() }
        scope.launch { watchHealth() }
        scope.launch { watchWindows() }
        scope.launch { watchComfy() }
        scope.launch { watchFolders() }
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

    private fun quiet(): Boolean = settings.value.game || foreground?.fullscreen == true || _chatOpen.value || _busy.value || asleep || focus?.working == true

    private fun remark(topic: String) {
        lastSpoke = System.currentTimeMillis()
        ask(history.toList(), Persona.remark(topic) + "\n\n" + Persona.note(moment()), isRemark = true)
    }

    private fun moment() = Persona.Moment(
        time = LocalDateTime.now(),
        app = foreground?.takeUnless { Foreground.isShell(it.process) }?.label(),
        weather = weather?.note(),
        battery = battery?.note(),
        music = _music.value,
        focus = focus?.text(),
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
    private fun ask(earlier: List<Turn>, message: String, isRemark: Boolean, command: Tools.Command? = null) {
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
                    val tool = command.tool
                    _bubble.value = "*${tool.doing}…*"
                    toolResult = Tools.run(tool, command.args, host)
                    userMessage = "$message\n\n[Résultat de l'outil ${tool.name} :\n$toolResult]\n${command.instruction}"
                }
                val installed = ollama.models().also { _models.value = it }
                val model = Ollama.pick(settings.value.model, installed)
                    ?: return@launch say(toolResult ?: Persona.noModel)
                val messages = buildList {
                    add(Ollama.Message("system", listOfNotNull(Persona.systemPrompt(settings.value.handle), Memory.prompt(settings.value.handle)).joinToString("\n\n")))
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
                        val result = if (tool == null) "Outil inconnu : ${call.name}" else Tools.run(tool, Tools.args(call.arguments), host)
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

    /** A passing move at her desk for [millis]. */
    private fun gesture(g: Gesture, millis: Long = 4_000) {
        passing = g
        passingUntil = System.currentTimeMillis() + millis
        refreshPose()
    }

    private fun refreshPose() {
        val now = System.currentTimeMillis()
        if (reaction != null && now > reactionUntil) reaction = null
        if (passing != null && now > passingUntil) passing = null
        _gesture.value = passing ?: when {
            focus?.working == true -> Gesture.Focus
            playing -> Gesture.Gamepad
            else -> null
        }
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
                if (!asleep && idle > SLEEP_AFTER_MS && !_chatOpen.value && !_busy.value && !_watching.value) {
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
            if (ticks % 4 == 0L) {
                remind(now)
                focus?.step(now)?.let { line ->
                    gesture(if (focus?.working == true) Gesture.Focus else Gesture.Stretch, 6_000)
                    say("*fait tinter un petit minuteur* $line")
                }
            }
            if (ticks % BATTERY_TICKS == 0L) {
                if (idle > BREAK_RESET_MS) activeSince = now
                greet(now, idle)
                nudgeBreak(now, idle)
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

    // ---- Her desktop helpers.

    /** Since when the runner has been at the PC without a real pause. */
    private var activeSince = System.currentTimeMillis()
    private var lastBreak = 0L
    private var lastYawn = 0L

    /** Reminders whose time has come: always said, even in game mode, and they wake her up. */
    private fun remind(now: Long) {
        val due = Reminders.due(now)
        if (due.isEmpty()) return
        if (asleep) asleep = false
        gesture(Gesture.Sign, 8_000)
        runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
        say("*agite une petite cloche* " + due.joinToString(" ") { "Rappel : ${it.text}." })
        history += Turn(false, "Rappel donné à ${settings.value.handle} : " + due.joinToString(" ; ") { it.text })
    }

    /** Hello with the day's summary on the first activity of the morning; good night late in the evening. */
    private fun greet(now: Long, idle: Long) {
        val s = settings.value
        if (!s.greetings || s.game || idle > 60_000 || _busy.value || _chatOpen.value) return
        val time = LocalDateTime.now()
        val day = time.toLocalDate()
        val done = Store.greeted()
        val kind = when (time.hour) {
            in 5..11 -> "morning"
            in 22..23, in 0..2 -> "evening"
            else -> return
        }
        // After midnight, the evening still belongs to the day before.
        val owner = if (time.hour < 5) day.minusDays(1) else day
        if ("$owner $kind" in done) return
        Store.saveGreeted(kind, owner)
        lastSpoke = now
        if (kind == "morning") {
            val today = Reminders.all().filter { Reminders.label(it.at).startsWith("à ") }
            val facts = listOfNotNull(
                weather?.let { "météo : ${it.note()}" },
                today.takeIf { it.isNotEmpty() }?.let { r -> "rappels du jour : " + r.joinToString(" ; ") { "${Reminders.label(it.at)} ${it.text}" } },
                deck?.takeIf { it.low.isNotEmpty() }?.let { "consommables bas dans le kit : " + it.low.take(4).joinToString(", ") },
            )
            gesture(Gesture.Stretch, 5_000)
            ask(history.toList(), Persona.greeting(s.handle, morning = true, facts) + "\n\n" + Persona.note(moment()), isRemark = true)
        } else {
            gesture(Gesture.Yawn, 5_000)
            lastYawn = now
            ask(history.toList(), Persona.greeting(s.handle, morning = false, emptyList()) + "\n\n" + Persona.note(moment()), isRemark = true)
        }
    }

    /** Two hours at the PC (or in a game) without a pause: a nudge to stretch and drink, then once an hour. */
    private fun nudgeBreak(now: Long, idle: Long) {
        val s = settings.value
        val hour = LocalDateTime.now().hour
        if (idle < 60_000 && (hour >= 23 || hour < 5) && now - lastYawn > YAWN_EVERY_MS) {
            lastYawn = now
            gesture(Gesture.Yawn, 4_000)
        }
        if (!s.breaks || idle > 60_000 || now - activeSince < BREAK_AFTER_MS || now - lastBreak < BREAK_AGAIN_MS) return
        // In full screen she stays quiet: the nudge waits for the next look.
        if (_busy.value || _chatOpen.value || focus != null || foreground?.fullscreen == true) return
        lastBreak = now
        gesture(Gesture.Stretch, 6_000)
        val hours = (now - activeSince) / 3_600_000
        say(
            if (playing) "*s'étire en levant les bras* Ça fait ${hours} h que tu joues sans pause : une minute pour boire un verre d'eau et t'étirer ?"
            else "*s'étire en baillant* ${hours} h d'écran d'affilée ! Lève-toi deux minutes, bois un peu d'eau, et repose tes yeux.",
        )
    }

    /** The PC running hot, out of memory or of disk: she says it once, then again after a while if it lasts. */
    private suspend fun watchHealth() {
        val told = HashMap<String, Long>()
        while (true) {
            delay(HEALTH_EVERY_MS)
            if (!settings.value.health) continue
            val h = withContext(Dispatchers.IO) { runCatching { Health.read() }.getOrNull() } ?: continue
            val now = System.currentTimeMillis()
            val fresh = h.alerts().filter { a -> now - (told[a.substringBefore('|')] ?: 0L) > HEALTH_AGAIN_MS }
            if (fresh.isEmpty()) continue
            fresh.forEach { told[it.substringBefore('|')] = now }
            gesture(Gesture.Worried, 6_000)
            if (!settings.value.game && foreground?.fullscreen != true || fresh.any { it.startsWith("gpu") || it.startsWith("cpu") }) {
                say("*fronce les sourcils* " + fresh.joinToString(". ") { it.substringAfter('|') } + ".")
            }
        }
    }

    /** The windows' titles: the music playing, and unread messages that apps show as « (3) ». */
    private suspend fun watchWindows() {
        val unreadBefore = HashMap<String, Int>()
        var lastSongRemark = 0L
        var primed = false
        while (true) {
            val s = settings.value
            val windows = withContext(Dispatchers.IO) { OpenWindows.list() }
            val song = if (!s.music) null else withContext(Dispatchers.IO) { OpenWindows.song(windows)?.title ?: if (!isWindows) OpenWindows.linuxSong()?.title else null }
            val now = System.currentTimeMillis()
            if (song != _music.value) {
                _music.value = song
                val gap = when (s.chatter) { Chatter.Quiet -> Long.MAX_VALUE; Chatter.Normal -> 40 * 60_000L; Chatter.Chatty -> 8 * 60_000L }
                if (song != null && primed && !quiet() && now - lastSongRemark > gap && now - lastSpoke > APP_REMARK_GAP_MS && _bubble.value == null) {
                    lastSongRemark = now
                    remark("la musique qui vient de commencer sur le PC : $song")
                }
            }
            if (s.messages) {
                val unread = OpenWindows.unread(windows)
                val news = unread.filter { (app, n) -> primed && n > (unreadBefore[app] ?: 0) }
                unreadBefore.clear()
                unreadBefore.putAll(unread)
                if (news.isNotEmpty()) {
                    gesture(Gesture.Sign, 6_000)
                    if (!s.game && foreground?.fullscreen != true && !_busy.value && !_chatOpen.value && focus?.working != true) {
                        say("*brandit un petit panneau* Nouveau message sur " + news.entries.joinToString(" et ") { (app, n) -> "$app ($n non lu${if (n > 1) "s" else ""})" } + ".")
                    }
                }
            }
            primed = true
            delay(WINDOWS_EVERY_MS)
        }
    }

    /** ComfyUI's queue: when it empties, the render is done. */
    private suspend fun watchComfy() {
        var before = 0
        while (true) {
            val queue = Comfy.queue(settings.value.comfyUrl)
            if (queue != null) {
                if (before > 0 && queue == 0 && !comfyOwn) {
                    gesture(Gesture.Happy, 5_000)
                    if (!settings.value.game && foreground?.fullscreen != true && !_busy.value) say("*applaudit* Ton rendu ComfyUI est fini !")
                }
                before = queue
            }
            delay(if (queue == null) 60_000L else 5_000L)
        }
    }

    /** Set while she generates in ComfyUI herself: she shows that result rather than announcing it. */
    @Volatile var comfyOwn = false

    /** New files in Downloads and in the watched folder (a LoRA training's output, for instance). */
    private suspend fun watchFolders() {
        val watch = FolderWatch()
        while (true) {
            val s = settings.value
            val folders = listOfNotNull(FolderWatch.downloads.takeIf { s.downloads }, s.watchFolder.takeIf { it.isNotBlank() }?.let { File(it) })
            val done = withContext(Dispatchers.IO) { runCatching { watch.check(folders) }.getOrDefault(emptyList()) }
            if (done.isNotEmpty()) {
                gesture(Gesture.Happy, 4_000)
                if (!s.game && foreground?.fullscreen != true && !_busy.value && !_chatOpen.value && focus?.working != true) {
                    say("*lève le pouce* " + done.take(3).joinToString(" ") { FolderWatch.describe(it) })
                }
            }
            delay(FOLDERS_EVERY_MS)
        }
    }

    /** A new program in front for a little while: she may say a word about it, once a session. */
    private suspend fun watchForeground() {
        var candidate: String? = null
        var since = 0L
        while (true) {
            val f = withContext(Dispatchers.IO) { Foreground.read() }
            foreground = f
            _watching.value = Foreground.watching(f)
            playing = warframeOn || f?.let { Foreground.isGame(it) } == true
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

    /**
     * While Warframe runs: a word on the state of the world when the game starts, then a nudge
     * before the night falls on the Plains (Eidolons) and when Baro Ki'Teer lands.
     */
    private suspend fun watchWarframe() {
        var playing = false
        var lastFetch = 0L
        var baroWasHere: Boolean? = null
        val nightsAnnounced = mutableSetOf<java.time.Instant>()
        while (true) {
            val running = withContext(Dispatchers.IO) {
                runCatching { ProcessHandle.allProcesses().anyMatch { p -> Warframe.isRunning(p.info().command().map { File(it).name }.orElse(null)) } }.getOrDefault(false)
            }
            val now = System.currentTimeMillis()
            if (running && (!playing || now - lastFetch > WARFRAME_EVERY_MS)) {
                val world = Warframe.world()
                lastFetch = now
                if (world != null) {
                    if (!playing && settings.value.chatter != Chatter.Quiet) {
                        remark("${settings.value.handle} vient de lancer Warframe. État du monde en ce moment :\n${world.text()}\nDonne-lui l'info la plus utile maintenant (un cycle qui change bientôt, Baro, la sortie)")
                    }
                    world.cetus?.takeIf { it.state == "jour" }?.let { day ->
                        val left = java.time.Duration.between(java.time.Instant.now(), day.ends).toMinutes()
                        if (left in 0..NIGHT_WARNING_MINUTES && nightsAnnounced.add(day.ends)) {
                            say("*pointe le ciel* La nuit tombe sur les Plaines d'Eidolon dans $left min : prépare-toi pour les Eidolons !")
                        }
                    }
                    if (baroWasHere == false && world.baroActive) say("*sautille* Baro Ki'Teer vient d'arriver ! ${world.baro.orEmpty()}")
                    baroWasHere = world.baroActive
                }
            }
            playing = running
            warframeOn = running
            delay(if (running) 60_000L else 120_000L)
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
        const val BREAK_AFTER_MS = 2 * 3_600_000L
        const val BREAK_AGAIN_MS = 3_600_000L
        /** A pause of this long counts as a break. */
        const val BREAK_RESET_MS = 5 * 60_000L
        const val YAWN_EVERY_MS = 45 * 60_000L
        const val HEALTH_EVERY_MS = 60_000L
        const val HEALTH_AGAIN_MS = 30 * 60_000L
        const val WINDOWS_EVERY_MS = 5_000L
        const val FOLDERS_EVERY_MS = 15_000L
        const val WARFRAME_EVERY_MS = 3 * 60_000L
        const val NIGHT_WARNING_MINUTES = 5L
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
