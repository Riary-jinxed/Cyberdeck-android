package dev.netrunner.launcher.aska

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
 * As//ka's model, in its own process (":aska"). A model takes 1 to 3 GB: if the phone runs out
 * of memory, or a GPU driver crashes, only this process dies, never the launcher. It lives
 * while the launcher is bound to it, and the process exits as soon as it is unbound, which
 * hands every byte back to the system.
 *
 * Talks through a [Messenger]; see [AskaEngine] for the launcher's side.
 */
class AskaEngineService : Service() {

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
        const val KEY_TEXT = "text"
    }

    // Every native call goes through this one thread: LiteRT-LM objects are not thread safe.
    private val engineThread = Executors.newSingleThreadExecutor { Thread(it, "aska-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineThread)
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var generating: Job? = null

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
        super.onDestroy()
        // Native memory is only truly given back when the process goes away.
        Process.killProcess(Process.myPid())
    }

    private fun load(client: Messenger?, data: Bundle) {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        val path = data.getString(KEY_PATH) ?: return reply(client, ERROR, KEY_TEXT, "modèle introuvable")
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

    private fun converse(client: Messenger?, data: Bundle) {
        runCatching { conversation?.close() }
        conversation = null
        val engine = engine ?: return
        val roles = data.getBooleanArray(KEY_ROLES) ?: BooleanArray(0)
        val texts = data.getStringArrayList(KEY_TEXTS).orEmpty()
        try {
            conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(data.getString(KEY_SYSTEM).orEmpty()),
                    initialMessages = texts.mapIndexed { i, t -> if (roles.getOrElse(i) { true }) Message.user(t) else Message.model(t) },
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
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

    private fun cancel() {
        generating?.cancel()
    }

    private fun reply(client: Messenger?, what: Int, key: String, value: String) =
        send(client, what, Bundle().apply { putString(key, value) })

    private fun send(client: Messenger?, what: Int, data: Bundle) {
        val msg = OsMessage.obtain(null, what).apply { this.data = data }
        runCatching { client?.send(msg) }
    }
}
