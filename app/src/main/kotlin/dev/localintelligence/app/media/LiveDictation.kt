package dev.localintelligence.app.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import dev.localintelligence.android.cloud.CredentialSource
import dev.localintelligence.android.cloud.ExtrasClient
import dev.localintelligence.android.cloud.ExtrasSettings
import dev.localintelligence.android.cloud.ManagedServices
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.core.settings.SettingsReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Speech-to-text into the composer, for a model that cannot read audio.
 *
 * WHY THIS AND NOT A RECORDED CLIP: a text-only model given a WAV does nothing
 * with it, so the clip would be sent and silently ignored. Turning the speech into
 * words here means the model gets what was said, and the user sees the words
 * land in the field as they speak, where they can be corrected before sending.
 *
 * Uses Deepgram streaming STT via Extras when managed voice is active and the "Better
 * voice (Extras)" toggle is on. Falls back to the on-device recognizer where the
 * platform has one (API 31+), and otherwise the default recognizer asked to work offline.
 * Main thread only for recognizer starts, like every SpeechRecognizer call.
 */
class LiveDictation(
    private val context: Context,
    private val settingsReader: SettingsReader? = null,
) {
    private val _listening = MutableStateFlow(false)

    /** True from [start] until the recognizer returns its result or an error. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var deepgramSession: DeepgramSttSession? = null
    private var onText: (String) -> Unit = {}
    private var onEnd: (spoken: String?, error: Int?) -> Unit = { _, _ -> }

    /** What the field held when this dictation began, before any spoken words. */
    private var base = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val settings: SettingsReader?
        get() = settingsReader ?: (context.applicationContext as? LocalIntelligenceApp)?.container?.settings

    /** False when the device has no recognition service at all and cloud voice is off. */
    fun start(current: String, onText: (String) -> Unit): Boolean =
        begin(current, onText, silenceMillis = null, onEnd = { _, _ -> })

    /**
     * One hands-free listen. The recognizer decides when the speech is over, and
     * [onEnd] runs once with what it heard: the spoken words, or null with no
     * words, and the error code when it failed. [silenceMillis] asks the recognizer
     * for the same pause length the user set; recognizers may ignore it.
     */
    fun startHandsFree(
        current: String,
        silenceMillis: Long,
        onText: (String) -> Unit,
        onEnd: (spoken: String?, error: Int?) -> Unit,
    ): Boolean = begin(current, onText, silenceMillis, onEnd)

    private fun begin(
        current: String,
        onText: (String) -> Unit,
        silenceMillis: Long?,
        onEnd: (spoken: String?, error: Int?) -> Unit,
    ): Boolean {
        if (_listening.value) return true

        val s = settings
        if (s != null && CredentialSource.managed(s, ManagedServices.VOICE) && s[ExtrasSettings.BETTER_VOICE]) {
            val started = startDeepgram(current, onText, silenceMillis, onEnd, s)
            if (started) return true
        }

        return startLocal(current, onText, silenceMillis, onEnd)
    }

    private fun startDeepgram(
        current: String,
        onText: (String) -> Unit,
        silenceMillis: Long?,
        onEnd: (spoken: String?, error: Int?) -> Unit,
        s: SettingsReader,
    ): Boolean {
        base = current
        this.onText = onText
        this.onEnd = onEnd
        _listening.value = true

        val session = DeepgramSttSession(
            context = context,
            settings = s,
            silenceMillis = silenceMillis,
            onTranscript = { transcript, isFinal ->
                val full = joined(transcript)
                if (isFinal) {
                    base = full
                }
                onText(full)
            },
            onFinished = { spoken, error ->
                _listening.value = false
                deepgramSession = null
                onEnd(spoken, error)
            },
            onFallback = {
                // Deepgram setup or network failed: fall back immediately to local recognizer in the same call
                scope.launch(Dispatchers.Main) {
                    deepgramSession?.release()
                    deepgramSession = null
                    _listening.value = false
                    startLocal(base, onText, silenceMillis, onEnd)
                }
            },
        )
        deepgramSession = session
        val ok = session.start()
        if (!ok) {
            deepgramSession = null
            _listening.value = false
            return false
        }
        return true
    }

    private fun startLocal(
        current: String,
        onText: (String) -> Unit,
        silenceMillis: Long?,
        onEnd: (spoken: String?, error: Int?) -> Unit,
    ): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        val rec = recognizer ?: createRecognizer().also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        base = current
        this.onText = onText
        this.onEnd = onEnd
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .apply {
                    if (silenceMillis != null) {
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMillis)
                    }
                },
        )
        _listening.value = true
        return true
    }

    /** Ends listening. The last words still arrive through the callback. */
    fun stop() {
        deepgramSession?.stop()
        recognizer?.stopListening()
    }

    /** Frees the recognizer. Call when the screen that owns it leaves. */
    fun release() {
        deepgramSession?.release()
        deepgramSession = null
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
            val spoken = firstResult(results)
            if (!spoken.isNullOrEmpty()) {
                base = joined(spoken)
                onText(base)
            }
            _listening.value = false
            onEnd(spoken?.takeIf { it.isNotEmpty() }, null)
        }

        override fun onError(error: Int) {
            _listening.value = false
            onEnd(null, error)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/**
 * Manages one Deepgram streaming STT WebSocket connection and microphone capture.
 *
 * External API:
 *   - STT Token Grant: POST <worker>/v1/voice/stt-token
 *     Docs: https://developers.deepgram.com/reference/auth/tokens/grant
 *   - Live STT WebSocket: wss://api.deepgram.com/v1/listen
 *     Auth: Authorization: Bearer <grantToken>
 *     Docs: https://developers.deepgram.com/reference/listen/listen-streaming
 *
 * Audio: 16 kHz mono 16-bit PCM (linear16).
 */
internal class DeepgramSttSession(
    private val context: Context,
    private val settings: SettingsReader,
    private val silenceMillis: Long?,
    private val onTranscript: (text: String, isFinal: Boolean) -> Unit,
    private val onFinished: (spoken: String?, error: Int?) -> Unit,
    private val onFallback: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var socket: SSLSocket? = null
    private var audioRecord: AudioRecord? = null
    private val writeLock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var completed = false

    private var cumulativeText = ""
    private var interimText = ""

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBuf, CHUNK_BYTES * 2),
            )
        } catch (e: SecurityException) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        audioRecord = record
        running = true

        job = scope.launch {
            try {
                runSession(record)
            } catch (e: Exception) {
                if (running && !completed && cumulativeText.isEmpty()) {
                    Log.w(TAG, "Deepgram STT failed, falling back to local recognizer", e)
                    onFallback()
                } else if (!completed) {
                    finish(cumulativeText.takeIf { it.isNotEmpty() }, null)
                }
            } finally {
                cleanup()
            }
        }
        return true
    }

    private suspend fun runSession(record: AudioRecord) {
        // Step 1: obtain grant token from Extras backend
        // External call: POST /v1/voice/stt-token
        val tokenReply = ExtrasClient.call(settings, "/v1/voice/stt-token")
        val token = tokenReply["token"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("No grant token returned")

        // Step 2: connect WebSocket to Deepgram
        // External API: wss://api.deepgram.com/v1/listen
        // Docs: https://developers.deepgram.com/reference/listen/listen-streaming
        val sslSocket = withContext(Dispatchers.IO) {
            val sock = SSLSocketFactory.getDefault().createSocket(DEEPGRAM_HOST, DEEPGRAM_PORT) as SSLSocket
            sock.soTimeout = 15_000
            sock.startHandshake()

            val randomBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val wsKey = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
            val path = buildString {
                append("/v1/listen?encoding=linear16&sample_rate=16000&channels=1&model=nova-3&interim_results=true&smart_format=true")
                if (silenceMillis != null) {
                    append("&endpointing=").append(silenceMillis)
                }
            }
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $DEEPGRAM_HOST\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $wsKey\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Authorization: Bearer $token\r\n\r\n"

            sock.outputStream.write(request.toByteArray(Charsets.UTF_8))
            sock.outputStream.flush()

            val statusLine = readLine(sock.inputStream)
            if (!statusLine.contains("101")) {
                throw IOException("WebSocket handshake failed: $statusLine")
            }
            while (true) {
                val header = readLine(sock.inputStream)
                if (header.isEmpty()) break
            }
            sock.soTimeout = 0 // unbounded read timeout during streaming
            sock
        }
        socket = sslSocket

        // Step 3: launch background reader for WebSocket transcripts
        val readerJob = scope.launch(Dispatchers.IO) {
            readLoop(sslSocket.inputStream)
        }

        // Step 4: stream microphone audio
        try {
            record.startRecording()
            val chunk = ByteArray(CHUNK_BYTES)
            while (running && !completed) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    sendBinary(chunk, read)
                } else if (read < 0) {
                    break
                }
            }
        } finally {
            runCatching { record.stop() }
            sendClose()
            readerJob.join()
        }
    }

    private fun readLoop(input: InputStream) {
        val messageBuffer = ByteArrayOutputStream()
        while (running && !completed) {
            val b0 = input.read()
            if (b0 == -1) break
            val b1 = input.read()
            if (b1 == -1) break

            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0f
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7f).toLong()

            if (payloadLen == 126L) {
                val h = input.read()
                val l = input.read()
                if (h == -1 || l == -1) break
                payloadLen = (((h and 0xff) shl 8) or (l and 0xff)).toLong()
            } else if (payloadLen == 127L) {
                var len = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b == -1) return
                    len = (len shl 8) or (b.toLong() and 0xff)
                }
                payloadLen = len
            }

            val mask = if (masked) {
                val m = ByteArray(4)
                readFully(input, m)
                m
            } else null

            val payload = ByteArray(payloadLen.toInt())
            readFully(input, payload)
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            when (opcode) {
                0x8 -> { // Close frame
                    break
                }
                0x9 -> { // Ping frame -> reply Pong
                    sendPong(payload)
                }
                0x1 -> { // Text frame (start or complete)
                    messageBuffer.reset()
                    messageBuffer.write(payload)
                    if (fin) {
                        handleJson(messageBuffer.toString("UTF-8"))
                        messageBuffer.reset()
                    }
                }
                0x0 -> { // Continuation frame
                    messageBuffer.write(payload)
                    if (fin) {
                        handleJson(messageBuffer.toString("UTF-8"))
                        messageBuffer.reset()
                    }
                }
            }
        }
    }

    private fun handleJson(text: String) {
        val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val channel = parsed["channel"]?.jsonObject
        val alt = channel?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
        val transcript = alt?.get("transcript")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val isFinal = parsed["is_final"]?.jsonPrimitive?.booleanOrNull == true
        val speechFinal = parsed["speech_final"]?.jsonPrimitive?.booleanOrNull == true

        if (transcript.isNotEmpty()) {
            if (isFinal) {
                cumulativeText = if (cumulativeText.isEmpty()) transcript else "$cumulativeText $transcript"
                interimText = ""
                scope.launch(Dispatchers.Main) { onTranscript(cumulativeText, true) }
            } else {
                interimText = transcript
                val current = if (cumulativeText.isEmpty()) interimText else "$cumulativeText $interimText"
                scope.launch(Dispatchers.Main) { onTranscript(current, false) }
            }
        }

        if (speechFinal && silenceMillis != null && !completed) {
            finish(cumulativeText.takeIf { it.isNotEmpty() }, null)
        }
    }

    private fun sendBinary(data: ByteArray, length: Int) {
        val sock = socket ?: return
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val header = ByteArrayOutputStream()
        header.write(0x82) // FIN + binary opcode
        if (length < 126) {
            header.write(0x80 or length)
        } else {
            header.write(0x80 or 126)
            header.write((length shr 8) and 0xff)
            header.write(length and 0xff)
        }
        header.write(mask)

        val masked = ByteArray(length)
        for (i in 0 until length) {
            masked[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        synchronized(writeLock) {
            runCatching {
                sock.outputStream.write(header.toByteArray())
                sock.outputStream.write(masked)
                sock.outputStream.flush()
            }
        }
    }

    private fun sendPong(payload: ByteArray) {
        val sock = socket ?: return
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val header = ByteArrayOutputStream()
        header.write(0x8A) // FIN + Pong opcode
        header.write(0x80 or (payload.size and 0x7f))
        header.write(mask)

        val masked = ByteArray(payload.size)
        for (i in payload.indices) {
            masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        synchronized(writeLock) {
            runCatching {
                sock.outputStream.write(header.toByteArray())
                sock.outputStream.write(masked)
                sock.outputStream.flush()
            }
        }
    }

    private fun sendClose() {
        val sock = socket ?: return
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val frame = byteArrayOf(0x88.toByte(), 0x80.toByte(), mask[0], mask[1], mask[2], mask[3])
        synchronized(writeLock) {
            runCatching {
                sock.outputStream.write(frame)
                sock.outputStream.flush()
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        finish(cumulativeText.takeIf { it.isNotEmpty() }, null)
    }

    fun release() {
        running = false
        completed = true
        cleanup()
        scope.cancel()
    }

    private fun finish(spoken: String?, error: Int?) {
        if (completed) return
        completed = true
        running = false
        scope.launch(Dispatchers.Main) { onFinished(spoken, error) }
    }

    private fun cleanup() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException("Premature EOF during WebSocket handshake")
            if (prev == '\r'.code && b == '\n'.code) {
                val raw = bytes.toByteArray()
                return String(raw, 0, raw.size - 1, Charsets.ISO_8859_1)
            }
            bytes.write(b)
            prev = b
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n == -1) throw EOFException("Premature EOF reading WebSocket frame")
            total += n
        }
    }

    companion object {
        private const val DEEPGRAM_HOST = "api.deepgram.com"
        private const val DEEPGRAM_PORT = 443
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES = 3200 // 100 ms at 16 kHz 16-bit mono
        private const val TAG = "DeepgramSttSession"
    }
}
