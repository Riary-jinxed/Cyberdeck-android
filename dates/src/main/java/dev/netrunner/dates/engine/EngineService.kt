package dev.netrunner.dates.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message as OsMessage
import android.os.Messenger
import android.os.Process
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Her model, in its own process (":engine"). A model takes 1 to 3 GB: if the phone runs out
 * of memory, or a GPU driver crashes, only this process dies, never the app. It lives
 * while the app is bound to it, and the process exits as soon as it is unbound, which
 * hands every byte back to the system.
 *
 * Talks through a [Messenger]; see [Engine] for the app's side.
 */
class EngineService : Service() {

    companion object {
        const val LOAD = 1
        const val CONVERSE = 2
        const val SEND = 3
        const val CANCEL = 4

        const val LOADED = 10
        const val CHUNK = 11
        const val DONE = 12
        const val ERROR = 13

        const val KEY_PATH = "path"
        const val KEY_GPU = "gpu"
        const val KEY_BACKEND = "backend"
        const val KEY_GPU_FAILED = "gpu_failed"
        const val KEY_SYSTEM = "system"
        const val KEY_ROLES = "roles"
        const val KEY_TEXTS = "texts"
        const val KEY_THINKS = "thinks"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TEXT = "text"
    }

    // Every native call goes through this one thread: LiteRT-LM objects are not thread safe.
    private val engineThread = Executors.newSingleThreadExecutor { Thread(it, "aska-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineThread)
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var generating: Job? = null

    // A GGUF model goes to llama.cpp instead: its handle, and the conversation it answers.
    private var llama = 0L
    private var llamaSystem = ""
    private val llamaHistory = mutableListOf<Pair<Boolean, String>>()
    private var llamaTemperature = 0.8f
    /** Bumped by each new answer and each cancel: an older answer sees it changed and stops. */
    @Volatile private var llamaTurn = 0

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: OsMessage) {
            val client = msg.replyTo
            val data = msg.data
            when (msg.what) {
                LOAD -> scope.launch { load(client, data) }
                CONVERSE -> scope.launch { converse(client, data) }
                SEND -> send(client, data.getString(KEY_TEXT).orEmpty())
                CANCEL -> cancel()
            }
        }
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        stopSelf()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        if (llama != 0L) LlamaNative.free(llama)
        super.onDestroy()
        // Native memory is only truly given back when the process goes away.
        Process.killProcess(Process.myPid())
    }

    private fun load(client: Messenger?, data: Bundle) {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        if (llama != 0L) LlamaNative.free(llama)
        llama = 0L
        val path = data.getString(KEY_PATH) ?: return reply(client, ERROR, KEY_TEXT, "modèle introuvable")
        if (path.endsWith(".gguf")) return loadGguf(client, path)
        var gpuFailed = false
        try {
            // GPU first: faster, and lighter on the battery. CPU if the driver refuses.
            if (data.getBoolean(KEY_GPU)) {
                try {
                    engine = Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cacheDir.path))
                        .also { it.initialize() }
                } catch (_: Throwable) {
                    gpuFailed = true
                }
            }
            val backend = if (engine != null) "GPU" else {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                engine = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(threadCount = threads), cacheDir = cacheDir.path))
                    .also { it.initialize() }
                "CPU"
            }
            send(client, LOADED, Bundle().apply {
                putString(KEY_BACKEND, backend)
                putBoolean(KEY_GPU_FAILED, gpuFailed)
            })
        } catch (e: Throwable) {
            engine = null
            reply(client, ERROR, KEY_TEXT, e.message ?: e.javaClass.simpleName)
        }
    }

    /** llama.cpp runs on the CPU, with all big cores and a 4096-token context. */
    private fun loadGguf(client: Messenger?, path: String) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        llama = try {
            LlamaNative.load(path, threads, 4096)
        } catch (e: Throwable) {
            return reply(client, ERROR, KEY_TEXT, e.message ?: e.javaClass.simpleName)
        }
        if (llama == 0L) return reply(client, ERROR, KEY_TEXT, "fichier GGUF illisible ou trop gros pour la mémoire")
        send(client, LOADED, Bundle().apply {
            putString(KEY_BACKEND, "CPU · GGUF")
            putBoolean(KEY_GPU_FAILED, false)
        })
    }

    private fun converse(client: Messenger?, data: Bundle) {
        runCatching { conversation?.close() }
        conversation = null
        if (llama != 0L) {
            val roles = data.getBooleanArray(KEY_ROLES) ?: BooleanArray(0)
            llamaSystem = data.getString(KEY_SYSTEM).orEmpty()
            llamaTemperature = data.getFloat(KEY_TEMPERATURE, 0.8f)
            llamaHistory.clear()
            data.getStringArrayList(KEY_TEXTS).orEmpty().forEachIndexed { i, t -> llamaHistory += roles.getOrElse(i) { true } to t }
            // Her instructions are most of the prompt: read them now, while the runner types.
            runCatching {
                LlamaNative.prefill(llama, llamaSystem, llamaHistory.map { it.first }.toBooleanArray(), llamaHistory.map { it.second }.toTypedArray())
            }
            return
        }
        val engine = engine ?: return
        val roles = data.getBooleanArray(KEY_ROLES) ?: BooleanArray(0)
        val texts = data.getStringArrayList(KEY_TEXTS).orEmpty()
        try {
            conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(data.getString(KEY_SYSTEM).orEmpty()),
                    initialMessages = texts.mapIndexed { i, t -> if (roles.getOrElse(i) { true }) Message.user(t) else Message.model(t) },
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = data.getFloat(KEY_TEMPERATURE, 0.7f).toDouble()),
                    maxOutputToken = 768,
                    thinkingConfig = if (data.getBoolean(KEY_THINKS)) ThinkingConfig(enableThinking = false) else null,
                )
            )
        } catch (e: Throwable) {
            reply(client, ERROR, KEY_TEXT, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun send(client: Messenger?, text: String) {
        generating?.cancel()
        if (llama != 0L) return sendGguf(client, text)
        generating = scope.launch {
            try {
                val conv = conversation ?: error("conversation fermée")
                conv.sendMessageAsync(text).collect { chunk -> reply(client, CHUNK, KEY_TEXT, chunk.toString()) }
                send(client, DONE, Bundle())
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { conversation?.cancelProcess() } }
                send(client, DONE, Bundle())
            } catch (e: Throwable) {
                reply(client, ERROR, KEY_TEXT, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The answer from llama.cpp, piece by piece; the exchange then joins its conversation. */
    private fun sendGguf(client: Messenger?, text: String) {
        val turnId = ++llamaTurn
        generating = scope.launch {
            val answer = StringBuilder()
            try {
                val turn = llamaHistory + (true to text)
                LlamaNative.generate(
                    llama, llamaSystem,
                    turn.map { it.first }.toBooleanArray(), turn.map { it.second }.toTypedArray(),
                    llamaTemperature, 768,
                ) { bytes ->
                    val piece = String(bytes, Charsets.UTF_8)
                    answer.append(piece)
                    reply(client, CHUNK, KEY_TEXT, piece)
                    turnId == llamaTurn
                }
                if (answer.isNotBlank()) {
                    llamaHistory += true to text
                    llamaHistory += false to answer.toString()
                }
                send(client, DONE, Bundle())
            } catch (e: Throwable) {
                reply(client, ERROR, KEY_TEXT, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun cancel() {
        // llama.cpp checks this between two tokens; LiteRT-LM is cancelled with the job.
        llamaTurn++
        generating?.cancel()
    }

    private fun reply(client: Messenger?, what: Int, key: String, value: String) =
        send(client, what, Bundle().apply { putString(key, value) })

    private fun send(client: Messenger?, what: Int, data: Bundle) {
        val msg = OsMessage.obtain(null, what).apply { this.data = data }
        runCatching { client?.send(msg) }
    }
}
