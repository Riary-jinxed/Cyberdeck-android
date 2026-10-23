package dev.netrunner.dates.date

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.dates.engine.Engine
import dev.netrunner.dates.engine.Models
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A rendez-vous with As//ka: the scene, what they say, and her model. The model is loaded when the
 * date starts and let go a little after the app leaves the screen, which hands its memory back.
 * The date itself is kept on disk until she is walked home: Android may close the app meanwhile.
 */
class DateBrain(private val context: Context, private val scope: CoroutineScope) {

    enum class State { NoModel, Asleep, Waking, Ready, Thinking }

    data class Line(
        val id: Long,
        val fromUser: Boolean,
        val text: String,
        /** False while the answer is still streaming in. */
        val done: Boolean = true,
        /** A photo she sends (drawable). */
        val photo: Int? = null,
    )

    val lines = mutableStateListOf<Line>()

    var scene by mutableStateOf<AskaDate.Scene?>(null)
        private set
    var profile by mutableStateOf<Link.Profile?>(null)
        private set
    var state by mutableStateOf(State.Asleep)
        private set
    /** "GPU" or "CPU" once loaded. */
    var backend by mutableStateOf<String?>(null)
        private set

    private var messages = 0
    private var nextId = 0L
    private var replyJob: Job? = null
    private var sleepJob: Job? = null
    private var loadedModel: String? = null
    /** Roughly what the model's current conversation holds, in characters. */
    private var sentChars = 0
    private val loadLock = Mutex()

    private val engine = Engine(context).apply {
        onDied = {
            if (state != State.Thinking && state != State.Waking) {
                loadedModel = null
                backend = null
                refreshState()
            }
        }
    }

    /** Reads who the runner is from the launcher. Null profile: the launcher cannot be reached. */
    fun refreshProfile() {
        profile = Link.profile(context)
    }

    init {
        restore()
    }

    fun refreshState() {
        if (state == State.Waking || state == State.Thinking) return
        state = when {
            Models.active(context) == null -> State.NoModel
            engine.connected && loadedModel == Models.active(context) -> State.Ready
            else -> State.Asleep
        }
    }

    fun start(scene: AskaDate.Scene) {
        val p = profile ?: return
        if (!scene.unlocked(p.bond)) return
        stop()
        lines.clear()
        messages = 0
        this.scene = scene
        say(scene.opening)
        wake()
        if (engine.connected) resetConversation()
    }

    /** Walks her home: the date goes back to the launcher, for her memory and their bond. */
    fun end(): Boolean {
        val s = scene ?: return true
        stop()
        val saved = Link.dateEnded(context, s, messages, lines.filter { it.fromUser }.map { it.text }, lines.filter { !it.fromUser }.map { it.text })
        scene = null
        lines.clear()
        savedFile.delete()
        return saved
    }

    /** On screen: load her model in the background so the first answer comes quicker. */
    fun wake() {
        sleepJob?.cancel()
        Models.pollDownload(context)
        refreshState()
        if (scene != null && state == State.Asleep) scope.launch { runCatching { ensureLoaded() } }
    }

    /** Off screen: free the memory if the runner does not come back soon. */
    fun sleepSoon() {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            delay(SLEEP_DELAY_MS)
            replyJob?.join()
            unload()
        }
    }

    fun stop() {
        replyJob?.cancel()
    }

    fun modelChanged() {
        scope.launch {
            unload()
            refreshState()
            wake()
        }
    }

    fun send(input: String) {
        val text = input.trim()
        val scene = scene ?: return
        val p = profile ?: return
        if (text.isEmpty() || state == State.Thinking) return
        val asked = Line(nextId++, fromUser = true, text = text)
        add(asked)
        messages++
        if (AskaDate.asksPhoto(text)) return say(AskaDate.photoLine(scene), photo = AskaDate.photo(scene))
        if (Models.active(context) == null) return say("*fouille dans ses poches* Il me manque mon noyau… Installe-le dans l'onglet NOYAU, et je reviens.")

        replyJob = scope.launch {
            val reply = Line(nextId++, fromUser = false, text = "", done = false)
            add(reply)
            try {
                ensureLoaded()
                state = State.Thinking
                val buffer = StringBuilder()
                var shownAt = 0L
                // A small event of the scene now and then, for her only.
                val beat = AskaDate.beat(scene, messages)
                // A small model ends every answer with a question: one out of two at most.
                val pause = lines.lastOrNull { !it.fromUser && it.done && it.id < asked.id }?.text?.contains('?') == true
                val message = listOfNotNull(
                    text,
                    AskaDate.choiceNote(text),
                    AskaDate.leadNote(lines.filter { !it.fromUser && it.done && it.id < asked.id && it.text.isNotBlank() }.map { it.text }),
                    AskaDate.looksNote(scene, text),
                    AskaDate.whereNote(lines.filter { it.done && it.id < asked.id }.map { it.fromUser to it.text }, p.handle),
                    beat?.let { "[Indication de scène, à glisser seulement si ça ne casse pas le moment : $it]" },
                    if (pause) "[Cette fois, pas de question : fais toi-même le pas suivant, décris ce que tu fais et ressens.]" else null,
                ).joinToString("\n\n")
                // Before its context is full (the answer would come back empty), start over from the recent lines.
                if (sentChars + message.length > SESSION_CHARS) resetConversation()
                for (attempt in 0..1) {
                    buffer.clear()
                    engine.send(message).collect { chunk ->
                        buffer.append(chunk)
                        // The screen follows at most ten times a second: the model needs the CPU more.
                        val now = System.currentTimeMillis()
                        if (now - shownAt < UI_REFRESH_MS) return@collect
                        shownAt = now
                        replace(reply.id, Line(reply.id, false, visible(buffer.toString()), done = false))
                    }
                    if (visible(buffer.toString()).isNotBlank() || attempt == 1) break
                    // Nothing came out: the context was full after all. Once more, on a fresh one.
                    resetConversation()
                }
                sentChars += message.length + buffer.length
                replace(reply.id, Line(reply.id, false, visible(buffer.toString()).ifEmpty { "…" }))
            } catch (e: CancellationException) {
                val partial = lines.firstOrNull { it.id == reply.id }?.text.orEmpty()
                replace(reply.id, Line(reply.id, false, if (partial.isEmpty()) "[transmission coupée]" else "$partial […]"))
            } catch (e: Throwable) {
                lines.removeAll { it.id == reply.id }
                if (e is Engine.Crashed) crashed() else {
                    say("Erreur de noyau : ${e.message?.take(160) ?: e.javaClass.simpleName}")
                    resetConversation()
                }
            } finally {
                state = State.Asleep
                refreshState()
            }
        }
    }

    // ---- Engine lifecycle.

    private suspend fun ensureLoaded() = loadLock.withLock {
        val id = Models.active(context) ?: error("aucun modèle installé")
        if (engine.connected && loadedModel == id) return@withLock
        state = State.Waking
        try {
            val loaded = load(Models.file(context, id).path)
            loadedModel = id
            backend = loaded.backend
            resetConversation()
        } finally {
            state = State.Asleep
            refreshState()
        }
    }

    /** GPU first (faster and lighter on the battery), CPU if it fails or crashed before. */
    private suspend fun load(path: String): Engine.Loaded {
        val prefs = context.getSharedPreferences("dates", Context.MODE_PRIVATE)
        val gpu = !prefs.getBoolean(GPU_BROKEN, false)
        val loaded = try {
            engine.load(path, gpu)
        } catch (e: Engine.Crashed) {
            if (!gpu) throw e
            prefs.edit().putBoolean(GPU_BROKEN, true).apply()
            engine.load(path, gpu = false)
        }
        if (loaded.gpuFailed) prefs.edit().putBoolean(GPU_BROKEN, true).apply()
        return loaded
    }

    private fun resetConversation() {
        val s = scene ?: return
        val p = profile ?: return
        // Without the message waiting for its answer: it is about to be sent.
        val recent = recentForContext().dropLastWhile { it.fromUser }
        val system = AskaDate.systemPrompt(s, p.handle, p.bond, p.facts, p.adult) + p.dates
        sentChars = system.length + recent.sumOf { it.text.length }
        engine.converse(system, recent.map { it.fromUser to it.text }, Models.thinks(loadedModel.orEmpty()), 0.8f)
    }

    /** The latest exchanges that fit in the model's context, oldest first. */
    private fun recentForContext(): List<Line> {
        val picked = ArrayDeque<Line>()
        var budget = CONTEXT_CHARS
        for (line in lines.asReversed()) {
            if (!line.done || line.text.isEmpty()) continue
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

    private fun crashed() {
        val onGpu = backend == "GPU"
        loadedModel = null
        backend = null
        engine.close()
        if (onGpu) context.getSharedPreferences("dates", Context.MODE_PRIVATE).edit().putBoolean(GPU_BROKEN, true).apply()
        say(
            if (onGpu) "*cligne des yeux* Mon noyau a planté sur le GPU. Je repasse sur le CPU : redis-moi ça ?"
            else "*vacille* Mon noyau a manqué de mémoire. Ferme quelques applis, puis redis-moi ça.",
        )
    }

    // ---- Conversation on screen.

    private fun say(text: String, photo: Int? = null) {
        add(Line(nextId++, fromUser = false, text = text, photo = photo))
    }

    private fun add(line: Line) {
        lines.add(line)
        if (line.done) save()
    }

    private fun replace(id: Long, line: Line) {
        val index = lines.indexOfFirst { it.id == id }
        if (index >= 0) lines[index] = line
        if (line.done) save()
    }

    // ---- The date in progress, on disk.

    private val savedFile get() = File(context.filesDir, "date.json")

    private fun save() {
        val s = scene ?: return
        val array = JSONArray()
        lines.filter { it.done }.forEach {
            array.put(JSONObject().put("user", it.fromUser).put("text", it.text).put("photo", it.photo != null))
        }
        runCatching { savedFile.writeText(JSONObject().put("scene", s.name).put("messages", messages).put("lines", array).toString()) }
    }

    private fun restore() {
        val saved = runCatching { JSONObject(savedFile.readText()) }.getOrNull() ?: return
        val s = AskaDate.Scene.entries.firstOrNull { it.name == saved.optString("scene") } ?: return
        val array = saved.optJSONArray("lines") ?: JSONArray()
        scene = s
        messages = saved.optInt("messages")
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            lines.add(Line(nextId++, o.optBoolean("user"), o.optString("text"), photo = if (o.optBoolean("photo")) AskaDate.photo(s) else null))
        }
    }

    /** Hides reasoning some models print despite being told not to. */
    private fun visible(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?(</think>|$)"), "").trimStart()

    private companion object {
        const val GPU_BROKEN = "gpu_broken"
        /** Long enough to answer a message elsewhere and come back without reloading her. */
        const val SLEEP_DELAY_MS = 180_000L
        const val CONTEXT_CHARS = 6000
        /** About 3000 tokens of French: under the usual 4096-token context, with room for an answer. */
        const val SESSION_CHARS = 9000
        const val UI_REFRESH_MS = 100L
    }
}
