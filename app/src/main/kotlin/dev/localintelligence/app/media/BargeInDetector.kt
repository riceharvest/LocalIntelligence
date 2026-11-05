package dev.localintelligence.app.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Hears the person start to talk while the assistant is reading a reply aloud (hands-free "barge-in").
 *
 * THE HARD PART IS THE ASSISTANT'S OWN VOICE. The microphone hears the speaker, so a plain level check
 * would cut the assistant off with its own words. Three guards, none of which needs a new dependency:
 *
 *  1. The microphone is opened as VOICE_COMMUNICATION, the source Android runs its echo canceller and
 *     noise suppressor on, and the platform [AcousticEchoCanceler] and [NoiseSuppressor] are switched on
 *     where the phone has them.
 *  2. The trigger level follows the room: it is [LEVEL_OVER_BASELINE] times the median level of the last
 *     few seconds (which is the speaker's own leak while the person is quiet), and never below
 *     [MIN_TRIGGER_RMS]. A voice close to the phone is louder than the speaker's echo of itself.
 *  3. The level must hold for [SUSTAINED_CHUNKS] chunks of 100 ms in a row, so a click, a cough or a
 *     door does not interrupt.
 *
 * LIMIT, stated plainly: on a phone whose echo canceller does nothing for text-to-speech, a loud reply
 * at full volume can still leak above the guard. The tap-anywhere and volume-key stops cover that case,
 * and the mode can be switched off in settings. Not measured on a device.
 *
 * When it triggers, the microphone is already released and [onBargeIn] receives the speech heard so far,
 * 16 kHz mono 16-bit PCM, so an audio model's clip can start with the person's first words.
 */
class BargeInDetector(private val scope: CoroutineScope) {

    @Volatile
    private var stopRequested = false
    private var job: Job? = null

    /** True from [start] until the microphone is released. */
    val active: Boolean get() = job?.isActive == true

    /**
     * Starts listening. [onBargeIn] runs once, on the capture thread, after sustained speech and after
     * the microphone is released. Returns false when the microphone cannot be opened (no grant, or in use).
     */
    @SuppressLint("MissingPermission")
    fun start(onBargeIn: (preRoll: ByteArray) -> Unit): Boolean {
        if (active) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                max(minBuffer, CHUNK_BYTES * 2),
            )
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        stopRequested = false
        job = scope.launch(Dispatchers.IO) { watch(record, onBargeIn) }
        return true
    }

    /** Stops watching. The microphone is released a moment later; use [stopAndWait] to wait for it. */
    fun stop() {
        stopRequested = true
    }

    /** Stops and waits until the microphone is free, so the next listener can open it. */
    suspend fun stopAndWait() {
        stopRequested = true
        job?.join()
    }

    private fun watch(record: AudioRecord, onBargeIn: (ByteArray) -> Unit) {
        // Effects hang on the record's audio session; both are optional and either may be missing.
        val echo = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true } }.getOrNull()
        } else {
            null
        }
        val noise = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true } }.getOrNull()
        } else {
            null
        }
        val chunk = ByteArray(CHUNK_BYTES)
        val recent = ArrayDeque<Double>()
        // The loud chunks since the run started, kept so the first words are not lost.
        val loud = ByteArrayOutputStream()
        var run = 0
        var triggered = false
        try {
            record.startRecording()
            while (!stopRequested) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) break
                val level = VoiceClipRecorder.rms(chunk, n)
                // The first chunks only measure the room and the speaker's own leak.
                val trigger = max(MIN_TRIGGER_RMS, median(recent) * LEVEL_OVER_BASELINE)
                val armed = recent.size >= WARM_UP_CHUNKS
                // Every chunk feeds the baseline. A run is at most 0.6 s of a 3 s window, so the median holds.
                recent.addLast(level)
                if (recent.size > BASELINE_CHUNKS) recent.removeFirst()
                if (armed && level >= trigger) {
                    run++
                    loud.write(chunk, 0, n)
                    if (run >= SUSTAINED_CHUNKS) {
                        triggered = true
                        break
                    }
                } else {
                    run = 0
                    loud.reset()
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "microphone stopped unexpectedly", e)
        } finally {
            runCatching { record.stop() }
            echo?.release()
            noise?.release()
            record.release()
        }
        if (triggered && !stopRequested) onBargeIn(loud.toByteArray())
    }

    private fun median(values: Collection<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        private const val SAMPLE_RATE = VoiceClipRecorder.SAMPLE_RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10

        /** Never trigger below this level: well above the listen threshold, so a quiet room cannot trip it. */
        private const val MIN_TRIGGER_RMS = 1500.0

        /** The trigger is this many times the median level of the last few seconds. */
        private const val LEVEL_OVER_BASELINE = 2.5

        /** 30 chunks of 100 ms: the last three seconds. */
        private const val BASELINE_CHUNKS = 30

        /** Chunks heard before the detector can trigger: half a second of measuring. */
        private const val WARM_UP_CHUNKS = 5

        /** 6 chunks of 100 ms in a row: 0.6 s of sustained speech. */
        private const val SUSTAINED_CHUNKS = 6

        private const val TAG = "BargeInDetector"
    }
}
