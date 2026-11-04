package dev.localintelligence.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.localintelligence.core.media.AudioChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the first audio track of a picked file into the PCM the model reads: 16 kHz,
 * mono, 16-bit little endian, cut into [AudioChunks.CHUNK_SECONDS] pieces as it goes.
 *
 * WHY STREAMING: an hour of speech is about 115 MB of 16-bit PCM at 16 kHz. Holding the
 * whole decode would double that in memory. Each chunk is handed over as soon as it is
 * full, so transcription of the first chunk starts while the rest is still decoding.
 *
 * WHY THE FORMAT COMES FROM THE CODEC: a container can say one rate and the decoder
 * output another, and a float-PCM decoder is common. The output format change is the
 * only place the real rate, channel count and sample encoding are known.
 */
object AudioFileDecoder {

    /**
     * Decodes [uri] and calls [onChunk] with each finished chunk: raw PCM bytes, 16 kHz mono
     * 16-bit little endian. The caller wraps them with [wavOf] before the model reads them.
     * [onChunk] returns false to stop the decode early (the user pressed Stop).
     *
     * Throws [IllegalArgumentException] with a sentence for the user when the file has no
     * audio track or the phone cannot decode its audio. Other failures are IO errors.
     */
    suspend fun decode(context: Context, uri: Uri, onChunk: suspend (ByteArray) -> Boolean) =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val track = audioTrack(extractor)
                    ?: throw IllegalArgumentException("This file has no audio track.")
                extractor.selectTrack(track.index)
                decodeTrack(extractor, track.format, onChunk)
            } finally {
                extractor.release()
            }
        }

    private class Track(val index: Int, val format: MediaFormat)

    private fun audioTrack(extractor: MediaExtractor): Track? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return Track(i, format)
        }
        return null
    }

    private suspend fun decodeTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        onChunk: suspend (ByteArray) -> Boolean,
    ) {
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("This file's audio has no format the phone can read.")
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw IllegalArgumentException("This phone cannot decode $mime audio.", e)
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val pump = Pump(extractor, codec, onChunk)
            pump.run()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** One decode loop: feeds compressed samples in, takes PCM out, and writes chunks. */
    private class Pump(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        private val onChunk: suspend (ByteArray) -> Boolean,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var rate = 0
        private var channels = 0
        private var floatPcm = false
        private var resampler: MonoResampler? = null
        private val chunk = ShortArray(AudioChunks.CHUNK_SAMPLES)
        private var filled = 0

        suspend fun run() {
            while (true) {
                if (!inputDone) feedInput()
                when (val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readOutputFormat()
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (out >= 0) {
                            val finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (!consume(out)) return
                            if (finished) break
                        }
                    }
                }
            }
            // The last chunk is the tail of the file, shorter than the others.
            if (filled > 0) onChunk(pcmBytes(filled))
        }

        private fun feedInput() {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index) ?: return
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        private fun readOutputFormat() {
            val out = codec.outputFormat
            rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                out.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            floatPcm = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> false
                AudioFormat.ENCODING_PCM_FLOAT -> true
                else -> throw IllegalArgumentException("This phone decodes this audio to an unsupported sample format.")
            }
            resampler = MonoResampler(rate, AudioChunks.SAMPLE_RATE)
        }

        /** Returns false when the caller stopped the decode. */
        private suspend fun consume(index: Int): Boolean {
            val bytes = ByteArray(info.size)
            codec.getOutputBuffer(index)?.let { buffer ->
                buffer.position(info.offset)
                buffer.get(bytes, 0, info.size)
            }
            codec.releaseOutputBuffer(index, false)
            val frames = info.size / (if (floatPcm) 4 else 2) / channels.coerceAtLeast(1)
            val source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val res = resampler ?: return true
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    sum += if (floatPcm) source.float else source.short / 32768f
                }
                res.push(sum / channels) { sample ->
                    chunk[filled++] = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                if (filled == chunk.size) {
                    // The chunk is handed off whole and the buffer reused, so a stop returns here cleanly.
                    if (!onChunk(pcmBytes(filled))) return false
                    filled = 0
                }
            }
            return true
        }

        private fun pcmBytes(count: Int): ByteArray {
            val out = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) out.putShort(chunk[i])
            return out.array()
        }
    }

    private const val TIMEOUT_US = 10_000L
}

/**
 * Linear resampling of mono samples from [inRate] to [outRate], one push at a time.
 *
 * Linear interpolation is enough for speech going to a speech model: the model was
 * trained on 16 kHz recordings, and a sharper filter would cost time on a phone for no
 * audible gain. Each output sample uses the input sample before and the one at its
 * position. The last fraction of a sample at the very end is not emitted, which is
 * inaudible at the file's end.
 */
private class MonoResampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var nextOut = 0.0
    private var index = 0L
    private var prev = 0f

    fun push(sample: Float, emit: (Float) -> Unit) {
        // Output positions in [index - 1, index) lie between the previous sample and this one.
        while (nextOut < index) {
            val frac = (nextOut - (index - 1)).toFloat()
            emit(prev + (sample - prev) * frac)
            nextOut += step
        }
        prev = sample
        index++
    }
}
