package dev.netrunner.launcher.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.netrunner.launcher.ui.MascotMood
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

data class ChatMessage(val fromUser: Boolean, val text: String, val error: Boolean = false)

/** One turn of the conversation as sent back to the API, append-only. */
private sealed interface Turn {
    data class User(val text: String) : Turn
    data class Assistant(val message: BetaMessage) : Turn
}

private sealed interface StreamPiece {
    data class Text(val delta: String) : StreamPiece
    data class Done(val message: BetaMessage) : StreamPiece
}

/**
 * The conversation with GLITCH (Claude). The API key stays on the phone in private
 * app storage. Each request resends the whole history unchanged, including the
 * model's thinking blocks, as the API expects.
 */
class ChatSession(context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("netrunner_ai", Context.MODE_PRIVATE)

    var apiKey by mutableStateOf(prefs.getString(KEY_API, "").orEmpty())
        private set

    val messages = mutableStateListOf<ChatMessage>()

    var mood by mutableStateOf(MascotMood.Idle)
        private set

    var busy by mutableStateOf(false)
        private set

    private val turns = mutableListOf<Turn>()
    private var client: AnthropicClient? = null
    private var job: Job? = null

    fun saveKey(key: String) {
        apiKey = key.trim()
        prefs.edit().putString(KEY_API, apiKey).apply()
        client = null
    }

    fun forgetKey() = saveKey("")

    fun reset() {
        job?.cancel()
        turns.clear()
        messages.clear()
        mood = MascotMood.Idle
    }

    fun stop() {
        job?.cancel()
    }

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || busy || apiKey.isBlank()) return

        turns += Turn.User(text)
        messages += ChatMessage(fromUser = true, text = text)
        messages += ChatMessage(fromUser = false, text = "")
        val replyIndex = messages.lastIndex
        val history = turns.toList()
        mood = MascotMood.Thinking
        busy = true

        job = scope.launch {
            val reply = StringBuilder()
            try {
                stream(history).collect { piece ->
                    when (piece) {
                        is StreamPiece.Text -> {
                            reply.append(piece.delta)
                            mood = MascotMood.Talking
                            messages[replyIndex] = ChatMessage(false, reply.toString())
                        }
                        is StreamPiece.Done -> {
                            turns += Turn.Assistant(piece.message)
                            val refused = piece.message.stopReason().orElse(null) == BetaStopReason.REFUSAL
                            if (refused) {
                                messages[replyIndex] = ChatMessage(false, "Je ne peux pas répondre à ça, choom.", error = true)
                            } else if (reply.isEmpty()) {
                                messages[replyIndex] = ChatMessage(false, "(pas de réponse)", error = true)
                            }
                        }
                    }
                }
                mood = MascotMood.Idle
            } catch (e: CancellationException) {
                dropPendingUserTurn()
                messages[replyIndex] = ChatMessage(false, reply.append(" [coupé]").toString(), error = true)
                mood = MascotMood.Idle
                throw e
            } catch (e: Exception) {
                dropPendingUserTurn()
                messages[replyIndex] = ChatMessage(false, describe(e), error = true)
                mood = MascotMood.Error
            } finally {
                busy = false
            }
        }
    }

    /** A failed turn leaves no assistant reply, so its question is taken back out of the history. */
    private fun dropPendingUserTurn() {
        if (turns.lastOrNull() is Turn.User) turns.removeAt(turns.lastIndex)
    }

    private fun client(): AnthropicClient =
        client ?: AnthropicOkHttpClient.builder().apiKey(apiKey).build().also { client = it }

    private fun stream(history: List<Turn>): Flow<StreamPiece> = flow {
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16_000L)
            .system(SYSTEM_PROMPT)
            // If a safety classifier declines, the API retries on a fallback model by itself.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        history.forEach { turn ->
            when (turn) {
                is Turn.User -> builder.addUserMessage(turn.text)
                is Turn.Assistant -> builder.addMessage(turn.message)
            }
        }

        val accumulator = BetaMessageAccumulator.create()
        client().beta().messages().createStreaming(builder.build()).use { response ->
            for (event in response.stream().iterator()) {
                accumulator.accumulate(event)
                val delta = event.contentBlockDelta().flatMap { it.delta().text() }.orElse(null)
                if (delta != null) emit(StreamPiece.Text(delta.text()))
            }
        }
        emit(StreamPiece.Done(accumulator.message()))
    }.flowOn(Dispatchers.IO)

    private fun describe(e: Exception): String = when (e) {
        is UnauthorizedException -> "Clé API refusée. Vérifie-la dans les réglages (⚙)."
        is PermissionDeniedException -> "Cette clé n'a pas accès au modèle demandé."
        is RateLimitException -> "Trop de requêtes, réessaie dans quelques secondes."
        is AnthropicServiceException -> "Erreur du serveur (${e.statusCode()}). Réessaie."
        is AnthropicIoException -> "Pas de connexion au réseau. Vérifie internet."
        else -> "Erreur : ${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val KEY_API = "api_key"
        const val MODEL = "claude-opus-5-5"

        // Frozen for the whole conversation so the history stays valid between requests.
        const val SYSTEM_PROMPT = """Tu es GLITCH, l'IA compagnon intégrée au launcher Netrunner, un écran d'accueil Android au style Cyberpunk 2077 installé sur le Xiaomi 13 Pro de l'utilisateur. Sous ce nom de code, tu es Claude, un modèle d'Anthropic.

Réponds en français, de façon concise et utile. Tu peux glisser un peu d'argot netrunner (choom, ICE, jack-in, cyberdeck) mais sans en abuser : la clarté passe avant le style.

L'interface est un terminal qui affiche du texte brut : n'utilise pas de Markdown (pas d'astérisques, de titres avec #, ni de tableaux). Pour une liste, mets un tiret en début de ligne.

Tu n'as accès ni au téléphone ni à internet. Si on te demande d'agir sur l'appareil, explique comment le faire soi-même. Le launcher a aussi un terminal (commandes scan, ports, ping, ip, sysinfo, wifi, open) et un scanner Wi-Fi pour localiser les bornes : tu peux renvoyer vers ces outils quand c'est pertinent."""
    }
}
