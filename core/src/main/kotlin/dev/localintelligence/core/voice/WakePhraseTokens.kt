package dev.localintelligence.core.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Unigram SentencePiece tokenizer for the sherpa-onnx Gigaspeech KWS model.
 *
 * The bundled model expects keywords encoded as BPE/Unigram subword pieces from
 * its 500-token table (`kws-pieces.tsv`), followed by `:boost #threshold @LABEL`.
 * Rather than pulling a native SentencePiece library for a short wake phrase, this
 * runs Viterbi over each normalized word using the piece log-probabilities dumped
 * from `bpe.model`. Every `▁A`..`▁Z` and `A`..`Z` is present in the table, so any
 * Latin word segments cleanly.
 */
class WakePhraseTokens(
    private val scores: Map<String, Double>,
) {
    /**
     * Segments [phrase] into space-separated Unigram pieces (for example,
     * `"Hey Nova"` -> `"▁HE Y ▁NO V A"`). Returns an empty string when [phrase]
     * contains no `A`..`Z` letters after stripping diacritics.
     */
    fun encode(phrase: String): String {
        val words = normalizeWords(phrase)
        if (words.isEmpty()) return ""
        return words.flatMap(::segmentWord).joinToString(" ")
    }

    /**
     * Formats [phrase] as a single sherpa-onnx keyword line:
     * `<space-joined pieces> :<boost> #<threshold> @<UPPER_WORDS>`.
     *
     * Returns `null` when [phrase] has no `A`..`Z` words.
     */
    fun keywordLine(
        phrase: String,
        boost: Float = DEFAULT_BOOST,
        threshold: Float = DEFAULT_THRESHOLD,
    ): String? {
        val words = normalizeWords(phrase)
        if (words.isEmpty()) return null
        val pieces = words.flatMap(::segmentWord).joinToString(" ")
        if (pieces.isEmpty()) return null
        val label = words.joinToString("_")
        return "$pieces :$boost #$threshold @$label"
    }

    private fun segmentWord(word: String): List<String> {
        val target = "$WORD_PREFIX$word"
        val n = target.length
        val bestScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val backPtr = IntArray(n + 1)
        bestScore[0] = 0.0

        for (i in 0 until n) {
            val prefixScore = bestScore[i]
            if (prefixScore == Double.NEGATIVE_INFINITY) continue
            for (j in i + 1..n) {
                val piece = target.substring(i, j)
                val pieceScore = scores[piece] ?: continue
                val candidate = prefixScore + pieceScore
                if (candidate > bestScore[j]) {
                    bestScore[j] = candidate
                    backPtr[j] = i
                }
            }
        }

        if (bestScore[n] == Double.NEGATIVE_INFINITY) {
            return (0 until n).map { idx ->
                if (idx == 0 && n >= 2) target.substring(0, 2) else target[idx].toString()
            }
        }

        val out = ArrayList<String>()
        var cur = n
        while (cur > 0) {
            val prev = backPtr[cur]
            out.add(target.substring(prev, cur))
            cur = prev
        }
        out.reverse()
        return out
    }

    companion object {
        const val WORD_PREFIX: Char = '\u2581'
        const val DEFAULT_BOOST: Float = 1.5f
        const val DEFAULT_THRESHOLD: Float = 0.25f
        const val DEFAULT_WAKE_PHRASE: String = "Hey assistant"

        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val NON_ALPHA = Regex("[^A-Z]+")

        /**
         * Parses `kws-pieces.tsv` (`piece\tscore\ttype` per line). Only normal
         * pieces (`type == 1`) are kept; control/unknown/byte tokens (`2..4`)
         * are skipped.
         */
        fun fromTsv(tsv: String): WakePhraseTokens {
            val map = HashMap<String, Double>(512)
            for (rawLine in tsv.lineSequence()) {
                if (rawLine.isEmpty()) continue
                val parts = rawLine.split('\t')
                if (parts.size < 3) continue
                if (parts[2].trim() != "1") continue
                val score = parts[1].trim().toDoubleOrNull() ?: continue
                map[parts[0]] = score
            }
            return WakePhraseTokens(map)
        }

        /**
         * Uppercases [phrase], strips diacritics (NFD without combining marks),
         * converts every non-`A..Z` character to a space, and returns the
         * non-empty words.
         */
        fun normalizeWords(phrase: String): List<String> {
            val deaccented = Normalizer.normalize(phrase, Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
                .uppercase(Locale.ROOT)
            return deaccented
                .replace(NON_ALPHA, " ")
                .trim()
                .split(' ')
                .filter { it.isNotEmpty() }
        }

        /**
         * Resolves the active wake phrase from user settings:
         * - [configuredPhrase] when non-blank,
         * - `"Hey <assistantName>"` when [assistantName] is non-blank,
         * - `"Hey assistant"` otherwise.
         */
        fun effectivePhrase(configuredPhrase: String, assistantName: String): String {
            val explicit = configuredPhrase.trim()
            if (explicit.isNotEmpty()) return explicit
            val name = assistantName.trim()
            return if (name.isNotEmpty()) "Hey $name" else DEFAULT_WAKE_PHRASE
        }
    }
}
