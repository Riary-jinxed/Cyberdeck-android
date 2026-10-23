package dev.netrunner.dates.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import dev.netrunner.dates.engine.EngineService as S

/**
 * The app's side of [EngineService]: binds to the model's process, loads a model,
 * streams replies. If that process dies (out of memory, GPU driver crash), calls fail with
 * [Crashed] and the app carries on.
 */
class Engine(private val context: Context) {

    class Crashed : Exception("le noyau s'est arrêté, sans doute faute de mémoire")

    class Loaded(val backend: String, val gpuFailed: Boolean)

    private class Event(val what: Int, val data: Bundle)

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { msg ->
        events.trySend(Event(msg.what, Bundle(msg.data)))
        true
    })

    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private var connecting: CompletableDeferred<Messenger>? = null

    val connected get() = service != null

    /** Called when the model's process dies on its own. */
    var onDied: (() -> Unit)? = null

    private suspend fun connect(): Messenger {
        service?.let { return it }
        connecting?.let { return it.await() }
        val waiting = CompletableDeferred<Messenger>()
        connecting = waiting
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val messenger = Messenger(binder)
                service = messenger
                waiting.complete(messenger)
            }

            override fun onServiceDisconnected(name: ComponentName) = died()
            override fun onBindingDied(name: ComponentName) = died()
            override fun onNullBinding(name: ComponentName) = died()

            private fun died() {
                service = null
                waiting.completeExceptionally(Crashed())
                events.trySend(Event(DIED, Bundle()))
                onDied?.invoke()
                // Stop the system from restarting a process nobody uses now.
                close()
            }
        }
        connection = conn
        if (!context.bindService(Intent(context, S::class.java), conn, Context.BIND_AUTO_CREATE)) {
            connection = null
            connecting = null
            throw Crashed()
        }
        return try {
            withTimeout(15_000) { waiting.await() }
        } finally {
            connecting = null
        }
    }

    /** Loads the model at [path], on the GPU if [gpu] and the driver agrees. */
    suspend fun load(path: String, gpu: Boolean): Loaded {
        val target = connect()
        drain()
        post(target, S.LOAD, Bundle().apply {
            putString(S.KEY_PATH, path)
            putBoolean(S.KEY_GPU, gpu)
        })
        while (true) {
            val e = events.receive()
            when (e.what) {
                S.LOADED -> return Loaded(e.data.getString(S.KEY_BACKEND) ?: "CPU", e.data.getBoolean(S.KEY_GPU_FAILED))
                S.ERROR -> error(e.data.getString(S.KEY_TEXT) ?: "chargement impossible")
                DIED -> throw Crashed()
            }
        }
    }

    /** Starts a fresh conversation: her instructions and the recent exchanges. */
    fun converse(system: String, history: List<Pair<Boolean, String>>, thinks: Boolean, temperature: Float = 0.7f) {
        val target = service ?: return
        post(target, S.CONVERSE, Bundle().apply {
            putString(S.KEY_SYSTEM, system)
            putBooleanArray(S.KEY_ROLES, history.map { it.first }.toBooleanArray())
            putStringArrayList(S.KEY_TEXTS, ArrayList(history.map { it.second }))
            putBoolean(S.KEY_THINKS, thinks)
            putFloat(S.KEY_TEMPERATURE, temperature)
        })
    }

    /** Her reply to [text], piece by piece. Cancelling the collection stops the model. */
    fun send(text: String): Flow<String> = flow {
        val target = service ?: throw Crashed()
        drain()
        post(target, S.SEND, Bundle().apply { putString(S.KEY_TEXT, text) })
        var finished = false
        try {
            while (!finished) {
                val e = events.receive()
                when (e.what) {
                    S.CHUNK -> emit(e.data.getString(S.KEY_TEXT).orEmpty())
                    S.DONE -> finished = true
                    S.ERROR -> {
                        finished = true
                        error(e.data.getString(S.KEY_TEXT) ?: "erreur du noyau")
                    }
                    DIED -> {
                        finished = true
                        throw Crashed()
                    }
                }
            }
        } finally {
            if (!finished) service?.let { post(it, S.CANCEL, Bundle()) }
        }
    }

    /** Unbinds: the model's process frees its memory and exits. */
    fun close() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }

    private fun drain() {
        while (events.tryReceive().isSuccess) Unit
    }

    private fun post(target: Messenger, what: Int, data: Bundle) {
        val msg = Message.obtain(null, what).apply {
            this.data = data
            replyTo = inbox
        }
        try {
            target.send(msg)
        } catch (_: Exception) {
            throw Crashed()
        }
    }

    private companion object {
        const val DIED = -1
    }
}
