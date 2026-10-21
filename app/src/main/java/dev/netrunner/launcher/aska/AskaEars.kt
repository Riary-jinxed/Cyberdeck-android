package dev.netrunner.launcher.aska

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Her ears: the phone's speech recognition, on the device when it can (Android 12+ with the
 * French pack), otherwise the phone's usual recognition service. Only listens while the
 * microphone button is held on, and lets go of the recognizer right after.
 */
class AskaEars(private val context: Context) {

    var listening by mutableStateOf(false)
        private set

    /** What she has heard so far, shown in the input while you speak. */
    var heard by mutableStateOf("")
        private set

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Listens for one sentence. Main thread only. */
    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        heard = ""
        listening = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let { heard = it }
            }

            override fun onResults(results: Bundle?) {
                val text = first(results) ?: heard
                done()
                if (text.isNotBlank()) onResult(text) else onError("Je n'ai rien entendu…")
            }

            override fun onError(error: Int) {
                val partial = heard
                done()
                when {
                    partial.isNotBlank() -> onResult(partial)
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onError("Je n'ai rien entendu… réessaie ?")
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Il me faut l'accès au micro.")
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                        onError("Le français n'est pas installé pour la reconnaissance vocale (Paramètres › Saisie vocale).")
                    else -> onError("Mes oreilles grésillent (erreur $error). Réessaie ?")
                }
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        )
    }

    /** Stops listening; what was heard is still delivered. */
    fun finish() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        listening = false
    }

    private fun done() {
        val r = recognizer
        recognizer = null
        listening = false
        r?.let { runCatching { it.destroy() } }
    }

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
