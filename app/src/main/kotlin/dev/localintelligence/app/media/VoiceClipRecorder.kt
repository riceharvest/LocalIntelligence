package dev.localintelligence.app.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Records one voice clip from the microphone, for a model that reads audio.
 *
 * Press to start, press again to stop, or stop by itself at [MAX_SECONDS]. The
 * clip is 16 kHz mono 16-bit PCM, which is what speech models are trained on, so
 * nothing is resampled. [onFinished] receives the finished WAV on the capture
 * thread; the caller decides what to do with it.
 *
 * The caller must hold RECORD_AUDIO. [start] returns false, rather than throwing,
 * when the microphone cannot be opened, so a missing grant is a message and not a
 * crash.
 */
class VoiceClipRecorder(
    private val scope: CoroutineScope,
    private val onFinished: (wav: ByteArray, seconds: Int) -> Unit,
) {
    private val _elapsedSeconds = MutableStateFlow<Int?>(null)

    /** Seconds recorded so far, or null while not recording. */
    val elapsedSeconds: StateFlow<Int?> = _elapsedSeconds.asStateFlow()

    @Volatile
    private var stopRequested = false
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (job?.isActive == true) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                max(minBuffer, CHUNK_BYTES * 2),
            )
        } catch (e: SecurityException) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        stopRequested = false
        _elapsedSeconds.value = 0
        job = scope.launch(Dispatchers.IO) { capture(record) }
        return true
    }

    /** Ends the clip now. The WAV is delivered to [onFinished] once the last chunk is read. */
    fun stop() {
        stopRequested = true
    }

    private fun capture(record: AudioRecord) {
        val pcm = ByteArrayOutputStream(MAX_BYTES)
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            record.startRecording()
            while (!stopRequested && pcm.size() < MAX_BYTES) {
                val n = record.read(chunk, 0, minOf(chunk.size, MAX_BYTES - pcm.size()))
                if (n <= 0) break
                pcm.write(chunk, 0, n)
                _elapsedSeconds.value = pcm.size() / BYTES_PER_SECOND
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "microphone stopped unexpectedly", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            _elapsedSeconds.value = null
        }
        val bytes = pcm.toByteArray()
        if (bytes.isNotEmpty()) {
            onFinished(wavOf(bytes, SAMPLE_RATE, 1), bytes.size / BYTES_PER_SECOND)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 30
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val MAX_BYTES = BYTES_PER_SECOND * MAX_SECONDS
        /** 100 ms per read keeps the elapsed counter current without busy-waiting. */
        private const val CHUNK_BYTES = BYTES_PER_SECOND / 10
        private const val TAG = "VoiceClipRecorder"
    }
}
