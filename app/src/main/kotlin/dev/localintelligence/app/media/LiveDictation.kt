package dev.localintelligence.app.media

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speech-to-text into the composer, for a model that cannot read audio.
 *
 * WHY THIS AND NOT A RECORDED CLIP: a text-only model given a WAV does nothing
 * with it, so the clip would be sent and silently ignored. Turning the speech into
 * words here means the model gets what was said, and the user sees the words
 * land in the field as they speak, where they can be corrected before sending.
 *
 * Uses the on-device recognizer where the platform has one (API 31+), and
 * otherwise the default recognizer asked to work offline. Main thread only, like
 * every SpeechRecognizer call.
 */
class LiveDictation(private val context: Context) {

    private val _listening = MutableStateFlow(false)

    /** True from [start] until the recognizer returns its result or an error. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onText: (String) -> Unit = {}

    /** What the field held when this dictation began, before any spoken words. */
    private var base = ""

    /** False when the device has no recognition service at all. */
    fun start(current: String, onText: (String) -> Unit): Boolean {
        if (_listening.value) return true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        val rec = recognizer ?: createRecognizer().also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        base = current
        this.onText = onText
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
        _listening.value = true
        return true
    }

    /** Ends listening. The last words still arrive through the callback. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Frees the recognizer. Call when the screen that owns it leaves. */
    fun release() {
        recognizer?.destroy()
        recognizer = null
        _listening.value = false
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    /** The field text with the spoken words after what was already typed. */
    private fun joined(spoken: String): String =
        listOf(base.trimEnd(), spoken).filter { it.isNotEmpty() }.joinToString(" ")

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { onText(joined(it)) }
        }

        override fun onResults(results: Bundle?) {
            firstResult(results)?.let {
                base = joined(it)
                onText(base)
            }
            _listening.value = false
        }

        override fun onError(error: Int) {
            _listening.value = false
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
