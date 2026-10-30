package dev.localintelligence.core.documents

/**
 * Splits a document into passages a small model can read in one go.
 *
 * ## WHY ~800 CHARACTERS WITH 100 OVERLAP
 *
 * A passage is what the search tool returns, and the model reads at most a few
 * of them per step, so the unit has to be small enough to be cheap and large
 * enough to hold a complete answer ("the wifi password is ..." is usually one
 * sentence, but the sentence before it usually says which network). The overlap
 * keeps an answer that straddles a boundary whole in at least one chunk.
 *
 * ## WHY BOUNDARIES FALL ON PARAGRAPHS AND SENTENCES
 *
 * A hard cut every 800 characters splits words and sentences, and a BM25 hit on
 * half a sentence is the wrong evidence. The splitter prefers the largest natural
 * boundary that fits: paragraph, then sentence, then word. Only a single word
 * longer than the budget is cut mid-word.
 */
object DocumentChunker {

    /** Target size of one passage, in characters. */
    const val CHUNK_CHARS: Int = 800

    /** How much of the previous passage the next one repeats, in characters. */
    const val OVERLAP_CHARS: Int = 100

    /**
     * The longest piece packed into a passage. A passage is `piece + " " + overlap`
     * at most, so this leaves room for the overlap and the joining space and keeps
     * every passage at or under [CHUNK_CHARS].
     */
    private const val PIECE_MAX: Int = CHUNK_CHARS - OVERLAP_CHARS - 1

    private val PARAGRAPH = Regex("\\n\\s*\\n")
    private val SENTENCE = Regex("(?<=[.!?])\\s+")
    private val WHITESPACE = Regex("\\s+")

    /** The passages of [text], in document order. Empty for blank text. */
    fun chunk(text: String): List<String> {
        val pieces = ArrayList<String>()
        for (paragraph in text.split(PARAGRAPH)) {
            val p = paragraph.replace(WHITESPACE, " ").trim()
            if (p.isEmpty()) continue
            if (p.length <= PIECE_MAX) pieces += p else pieces += splitLong(p)
        }

        val out = ArrayList<String>()
        var current = StringBuilder()
        for (piece in pieces) {
            if (current.isEmpty()) {
                current.append(piece)
                continue
            }
            if (current.length + 1 + piece.length <= CHUNK_CHARS) {
                current.append(' ').append(piece)
                continue
            }
            out += current.toString()
            // The next passage opens with the tail of this one, so an answer that
            // straddles the boundary is whole in at least one passage. The overlap
            // is dropped when it would push the piece past the budget.
            val tail = overlapTail(current.toString())
            current = StringBuilder()
            if (tail.isNotEmpty() && tail.length + 1 + piece.length <= CHUNK_CHARS) {
                current.append(tail).append(' ')
            }
            current.append(piece)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** The last [OVERLAP_CHARS] characters of [passage], starting at a word boundary. */
    private fun overlapTail(passage: String): String {
        if (passage.length <= OVERLAP_CHARS) return passage
        val raw = passage.substring(passage.length - OVERLAP_CHARS)
        val firstSpace = raw.indexOf(' ')
        return if (firstSpace in 0 until raw.length - 1) raw.substring(firstSpace + 1) else raw
    }

    /** One over-long paragraph: break at sentences, then at words, then hard-cut a word that is still too long. */
    private fun splitLong(paragraph: String): List<String> {
        val out = ArrayList<String>()
        for (sentence in paragraph.split(SENTENCE)) {
            if (sentence.length <= PIECE_MAX) {
                out += sentence
                continue
            }
            var line = StringBuilder()
            for (word in sentence.split(' ')) {
                var w = word
                while (w.length > PIECE_MAX) {
                    if (line.isNotEmpty()) {
                        out += line.toString()
                        line = StringBuilder()
                    }
                    out += w.substring(0, PIECE_MAX)
                    w = w.substring(PIECE_MAX)
                }
                if (line.isNotEmpty() && line.length + 1 + w.length > PIECE_MAX) {
                    out += line.toString()
                    line = StringBuilder()
                }
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
            if (line.isNotEmpty()) out += line.toString()
        }
        return out
    }
}
