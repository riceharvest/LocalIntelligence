package dev.localintelligence.android.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.localintelligence.core.voice.WakePhraseTokens
import java.io.Closeable

/**
 * Streaming open-vocabulary wake word spotter backed by the bundled
 * `sherpa-onnx` Zipformer GigaSpeech 3.3M int8 transducer model in `assets/kws/`.
 *
 * Audio stays entirely in-memory: 16 kHz mono PCM frames are converted to
 * normalized floats, fed into the transducer stream, and discarded immediately.
 */
class WakeWordSpotter(
    context: Context,
    initialPhrase: String,
) : Closeable {

    private val tokenizer: WakePhraseTokens = context.assets
        .open(ASSET_PIECES)
        .bufferedReader()
        .use { WakePhraseTokens.fromTsv(it.readText()) }

    private val spotter: KeywordSpotter = KeywordSpotter(
        assetManager = context.assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80,
                dither = 0.0f,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = ASSET_ENCODER,
                    decoder = ASSET_DECODER,
                    joiner = ASSET_JOINER,
                ),
                tokens = ASSET_TOKENS,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            maxActivePaths = 4,
            numTrailingBlanks = 1,
            keywordsScore = WakePhraseTokens.DEFAULT_BOOST,
            keywordsThreshold = WakePhraseTokens.DEFAULT_THRESHOLD,
            keywordsFile = ASSET_KEYWORDS_EMPTY,
        ),
    )

    private var stream: OnlineStream? = null
    private var activeKeywordLine: String? = null
    private var floatBuffer = FloatArray(1600)

    init {
        updatePhrase(initialPhrase)
    }

    /**
     * Rebuilds the spotting stream when [phrase] changes.
     */
    @Synchronized
    fun updatePhrase(phrase: String) {
        val keywordLine = tokenizer.keywordLine(phrase)
            ?: tokenizer.keywordLine(WakePhraseTokens.DEFAULT_WAKE_PHRASE)
            ?: return
        if (keywordLine == activeKeywordLine && stream != null) return
        stream?.release()
        stream = spotter.createStream(keywordLine)
        activeKeywordLine = keywordLine
    }

    /**
     * Feeds [count] 16-bit mono PCM samples at 16 kHz into the spotter and
     * returns `true` when the active wake phrase is detected.
     */
    @Synchronized
    fun acceptPcm16(samples: ShortArray, count: Int): Boolean {
        if (count <= 0) return false
        val s = stream ?: return false
        if (floatBuffer.size < count) {
            floatBuffer = FloatArray(count)
        }
        val floats = floatBuffer
        for (i in 0 until count) {
            floats[i] = samples[i] / 32768.0f
        }
        val slice = if (floats.size == count) floats else floats.copyOf(count)
        s.acceptWaveform(slice, SAMPLE_RATE)
        while (spotter.isReady(s)) {
            spotter.decode(s)
        }
        val result = spotter.getResult(s)
        if (result.keyword.isNotBlank()) {
            spotter.reset(s)
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        val s = stream ?: return
        spotter.reset(s)
    }

    @Synchronized
    override fun close() {
        stream?.release()
        stream = null
        activeKeywordLine = null
        spotter.release()
    }

    companion object {
        const val SAMPLE_RATE: Int = 16_000

        private const val ASSET_DIR = "kws"
        private const val ASSET_ENCODER = "$ASSET_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val ASSET_DECODER = "$ASSET_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val ASSET_JOINER = "$ASSET_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val ASSET_TOKENS = "$ASSET_DIR/tokens.txt"
        private const val ASSET_PIECES = "$ASSET_DIR/kws-pieces.tsv"
        private const val ASSET_KEYWORDS_EMPTY = "$ASSET_DIR/keywords.txt"
    }
}
