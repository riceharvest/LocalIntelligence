package dev.localintelligence.app.speech

import android.media.MediaPlayer
import dev.localintelligence.android.cloud.CredentialSource
import dev.localintelligence.android.cloud.ExtrasClient
import dev.localintelligence.android.cloud.ExtrasSettings
import dev.localintelligence.android.cloud.ManagedServices
import dev.localintelligence.core.model.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingsReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads replies aloud with the phone's own text-to-speech engine.
 *
 * WHY THE PLATFORM ENGINE: it ships with Android, so nothing is downloaded and
 * no audio model is loaded next to the chat model. Model-generated speech will
 * come later and reuse this class's rate and pitch settings.
 *
 * The engine is created on the first [speak], not at app start, because most
 * users never turn the feature on. [TextToSpeech] initialises asynchronously and
 * refuses speech until it is ready, so a request that arrives first is held and
 * spoken from the init callback.
 *
 * Only one utterance plays at a time: every [speak] flushes the one before it.
 */
class Speaker(
    context: Context,
    private val settings: SettingsReader,
    /** The loaded model, when it can speak in its own voice. Null otherwise. */
    private val modelVoice: () -> SpeechOutput? = { null },
) {

    private val appContext = context.applicationContext

    private val _speakingText = MutableStateFlow<String?>(null)

    /** The text of the reply being read, or null. Chat rows compare against it to show Stop. */
    val speakingText: StateFlow<String?> = _speakingText.asStateFlow()

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null
    private var currentId: String? = null
    private var nextId = 0

    /** Synthesis in the model's voice runs here; it can take seconds on a phone. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: MediaPlayer? = null

    /** Reads [text] aloud, replacing whatever is playing. Markdown and code are stripped first. */
    @Synchronized
    fun speak(text: String) {
        val spoken = SpeechText.clean(text)
        if (spoken.isBlank()) return
        val id = "reply-${nextId++}"
        currentId = id
        _speakingText.value = text

        if (CredentialSource.managed(settings, ManagedServices.VOICE) && settings[ExtrasSettings.BETTER_VOICE]) {
            scope.launch { speakWithWorker(spoken, id) }
            return
        }

        val voice = modelVoice().takeIf { settings[CoreSettings.VOICE_SOURCE] == "model" }
        if (voice != null) {
            scope.launch { speakWithModel(voice, spoken, id) }
            return
        }
        sayWithSystem(spoken, id)
    }

    @Synchronized
    private fun sayWithSystem(spoken: String, id: String) {
        if (id != currentId) return
        val tts = engine ?: createEngine()
        if (ready) say(tts, spoken, id) else pending = spoken to id
    }

    /**
     * Managed voice TTS via Extras Worker proxy (`POST /v1/voice/speak`) using Deepgram Aura-2.
     * Any failure falls back to the local path in the same call.
     */
    private suspend fun speakWithWorker(spoken: String, id: String) {
        val audioBytes = runCatching { fetchWorkerTts(spoken) }.getOrNull()
        if (audioBytes == null || audioBytes.isEmpty()) {
            fallbackLocal(spoken, id)
            return
        }

        val file = File(appContext.cacheDir, "speech_extras.mp3")
        val written = runCatching { file.writeBytes(audioBytes) }.isSuccess
        if (!written) {
            fallbackLocal(spoken, id)
            return
        }

        synchronized(this) {
            if (id != currentId) return
            player?.release()
            player = MediaPlayer().apply {
                runCatching {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { finished(id) }
                    prepare()
                    start()
                }.onFailure {
                    finished(id)
                    fallbackLocal(spoken, id)
                }
            }
        }
    }

    /**
     * External API: POST <baseUrl>/v1/voice/speak
     * Deepgram Aura-2 TTS docs: https://developers.deepgram.com/reference/text-to-speech/speak
     */
    private fun fetchWorkerTts(spoken: String): ByteArray {
        val root = ExtrasClient.baseUrl(settings)
        if (!root.startsWith("https://")) throw IOException("Insecure URL")
        val token = CredentialSource.extrasAccessToken(settings)
        if (token.isBlank()) throw IOException("Missing access token")

        val connection = (URL("$root/v1/voice/speak").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/*")
            setRequestProperty("Authorization", "Bearer $token")
        }

        val body = buildJsonObject {
            put("text", spoken.take(2000))
        }.toString().toByteArray(Charsets.UTF_8)

        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        if (status !in 200..299) {
            throw IOException("Extras TTS failed: HTTP $status")
        }
        return connection.inputStream.use { it.readBytes() }
    }

    private fun fallbackLocal(spoken: String, id: String) {
        synchronized(this) {
            if (id != currentId) return
        }
        val voice = modelVoice().takeIf { settings[CoreSettings.VOICE_SOURCE] == "model" }
        if (voice != null) {
            scope.launch { speakWithModel(voice, spoken, id) }
        } else {
            sayWithSystem(spoken, id)
        }
    }

    /**
     * The model's own voice: synthesise to a WAV, then play it. Falls back to the
     * phone's voice when the model returns nothing, so a reply is never silent.
     */
    private suspend fun speakWithModel(voice: SpeechOutput, spoken: String, id: String) {
        val wav = runCatching {
            voice.speak(spoken, Locale.getDefault().language, settings[CoreSettings.MODEL_VOICE])
        }.getOrNull()
        if (wav == null || wav.isEmpty()) {
            sayWithSystem(spoken, id)
            return
        }
        val file = File(appContext.cacheDir, "speech.wav")
        runCatching { file.writeBytes(wav) }.onFailure { sayWithSystem(spoken, id); return }
        synchronized(this) {
            if (id != currentId) return
            player?.release()
            player = MediaPlayer().apply {
                runCatching {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { finished(id) }
                    prepare()
                    start()
                }.onFailure { finished(id) }
            }
        }
    }

    /** Stops playback and clears the held request. */
    @Synchronized
    fun stop() {
        pending = null
        currentId = null
        _speakingText.value = null
        engine?.stop()
        player?.release()
        player = null
    }

    /** Stops [text] if it is the reply being read, otherwise reads it. */
    @Synchronized
    fun toggle(text: String) {
        if (_speakingText.value == text) stop() else speak(text)
    }

    /**
     * Releases the engine. Called when the chat activity finishes, the closest
     * thing Android offers to app exit. A later [speak] creates a new engine, so
     * the process can keep using the speaker after the activity is gone.
     */
    @Synchronized
    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
        pending = null
        currentId = null
        _speakingText.value = null
    }

    private fun createEngine(): TextToSpeech {
        val tts = TextToSpeech(appContext) { status -> onInit(status) }
        engine = tts
        return tts
    }

    @Synchronized
    private fun onInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            // No usable engine on this phone. Drop the request rather than hold it forever.
            engine = null
            pending = null
            currentId = null
            _speakingText.value = null
            return
        }
        tts.language = Locale.getDefault()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            // Both overloads are overridden: the one without an error code is
            // abstract on older API levels, and the engine may call either.
            @Deprecated("Replaced by onError(String, Int)")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
        })
        ready = true
        pending?.let { (text, id) ->
            pending = null
            say(tts, text, id)
        }
    }

    private fun say(tts: TextToSpeech, text: String, id: String) {
        tts.setSpeechRate(settings[CoreSettings.SPEECH_RATE])
        tts.setPitch(settings[CoreSettings.SPEECH_PITCH])
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Clears the speaking state only for the utterance that ended, not a newer one. */
    @Synchronized
    private fun finished(utteranceId: String?) {
        if (utteranceId == null || utteranceId != currentId) return
        currentId = null
        _speakingText.value = null
    }
}

/**
 * Turns a markdown reply into the words to say.
 *
 * Code is dropped rather than read: a spoken `{`, `->` or a file path is noise
 * to a listener. Tool markup is dropped too, in case a reply still carries it.
 */
internal object SpeechText {
    private val fencedCode = Regex("```.*?(```|$)", RegexOption.DOT_MATCHES_ALL)
    private val toolMarkup = Regex("<tool\\b.*?</tool>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val protocolTags = Regex("</?(respond|tool)[^>]*>", RegexOption.IGNORE_CASE)
    private val link = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val linePrefix = Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+[.)]\\s+)")
    private val inlineCode = Regex("`([^`]*)`")
    private val symbols = Regex("[*~#|`>]")
    private val underscores = Regex("_+")
    private val whitespace = Regex("\\s+")

    fun clean(text: String): String {
        var s = fencedCode.replace(text, " ")
        s = toolMarkup.replace(s, " ")
        s = protocolTags.replace(s, " ")
        s = link.replace(s) { it.groupValues[1] }
        s = linePrefix.replace(s, "")
        s = inlineCode.replace(s) { it.groupValues[1] }
        s = symbols.replace(s, " ")
        s = underscores.replace(s, " ")
        return whitespace.replace(s, " ").trim()
    }
}
