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
import kotlin.math.sqrt

/**
 * Records one voice clip from the microphone, for a model that reads audio.
 *
 * Press to start, press again to stop, or stop by itself at [MAX_SECONDS]. The
 * clip is 16 kHz mono 16-bit PCM, which is what speech models are trained on, so
 * nothing is resampled. [onFinished] receives the finished WAV on the capture
 * thread; the caller decides what to do with it.
 *
 * [startUntilPause] is the hands-free variant: the clip ends by itself at the first
 * pause after speech, and a clip with no speech at all is reported as null rather
 * than sent. Pauses are found by the RMS level of each 100 ms chunk, which is a
 * fixed threshold and not calibrated per phone.
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

    /** Starts a clip that the user ends with [stop]. Its WAV goes to [onFinished]. */
    fun start(): Boolean = begin(pause = null)

    /**
     * Starts a clip that ends after [silenceSeconds] of quiet following speech.
     * [onEnd] receives the WAV, or null when no speech was heard before the clip gave up.
     * [stop] still ends it early, and then the clip is delivered as usual.
     */
    fun startUntilPause(silenceSeconds: Float, onEnd: (wav: ByteArray?, seconds: Int) -> Unit): Boolean =
        begin(PauseRule((silenceSeconds * BYTES_PER_SECOND).toInt(), onEnd))

    @SuppressLint("MissingPermission")
    private fun begin(pause: PauseRule?): Boolean {
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
        job = scope.launch(Dispatchers.IO) { capture(record, pause) }
        return true
    }

    /** Ends the clip now. The WAV is delivered to [onFinished] once the last chunk is read. */
    fun stop() {
        stopRequested = true
    }

    private fun capture(record: AudioRecord, pause: PauseRule?) {
        val pcm = ByteArrayOutputStream(MAX_BYTES)
        val chunk = ByteArray(CHUNK_BYTES)
        var heardSpeech = false
        var quietBytes = 0
        try {
            record.startRecording()
            while (!stopRequested && pcm.size() < MAX_BYTES) {
                val n = record.read(chunk, 0, minOf(chunk.size, MAX_BYTES - pcm.size()))
                if (n <= 0) break
                pcm.write(chunk, 0, n)
                _elapsedSeconds.value = pcm.size() / BYTES_PER_SECOND
                if (pause != null) {
                    if (rms(chunk, n) >= SPEECH_RMS) {
                        heardSpeech = true
                        quietBytes = 0
                    } else {
                        quietBytes += n
                    }
                    // Done at the first pause after speech, or when nothing was said for NO_SPEECH_BYTES.
                    if (heardSpeech && quietBytes >= pause.silenceBytes) break
                    if (!heardSpeech && pcm.size() >= NO_SPEECH_BYTES) break
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "microphone stopped unexpectedly", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            _elapsedSeconds.value = null
        }
        val bytes = pcm.toByteArray()
        if (pause != null) {
            val seconds = bytes.size / BYTES_PER_SECOND
            pause.onEnd(if (heardSpeech) wavOf(bytes, SAMPLE_RATE, 1) else null, seconds)
            return
        }
        if (bytes.isNotEmpty()) {
            onFinished(wavOf(bytes, SAMPLE_RATE, 1), bytes.size / BYTES_PER_SECOND)
        }
    }

    /** How a hands-free clip ends: after this many quiet bytes once speech has started. */
    private class PauseRule(val silenceBytes: Int, val onEnd: (wav: ByteArray?, seconds: Int) -> Unit)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 30
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val MAX_BYTES = BYTES_PER_SECOND * MAX_SECONDS
        /** 100 ms per read keeps the elapsed counter current without busy-waiting. */
        private const val CHUNK_BYTES = BYTES_PER_SECOND / 10
        /** A hands-free listen that hears nothing for this long gives up as an empty listen. */
        private const val NO_SPEECH_BYTES = BYTES_PER_SECOND * 8
        /**
         * RMS of 16-bit samples, on a 0..32767 scale, above which a chunk counts as speech.
         * Quiet room noise on a phone measures in the low hundreds. Not calibrated on a device yet.
         */
        private const val SPEECH_RMS = 400.0
        private const val TAG = "VoiceClipRecorder"

        /** Root-mean-square level of the first [n] bytes of little-endian 16-bit PCM in [buf]. */
        internal fun rms(buf: ByteArray, n: Int): Double {
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < n) {
                val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
                count++
                i += 2
            }
            return if (count == 0) 0.0 else sqrt(sum / count)
        }
    }
}
