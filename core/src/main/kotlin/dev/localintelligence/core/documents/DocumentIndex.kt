package dev.localintelligence.core.documents

import dev.localintelligence.core.tool.lexicalTokens
import kotlin.math.ln

/** One document to index: its id, the name the user sees, and its extracted text. */
data class IndexedDocument(val id: String, val title: String, val text: String)

/**
 * One passage a search found.
 *
 * [number] is the 1-based position of the passage inside its document, which is
 * what the model cites as `title §n`, so a reply can point at a place the user can
 * find again.
 */
data class Passage(
    val docId: String,
    val title: String,
    val number: Int,
    val text: String,
    val score: Double,
)

/**
 * A BM25 index over the passages of a set of documents. Pure, deterministic,
 * no I/O. Built from scratch whenever a document is added or removed: a phone
 * holds a handful of documents, so rebuilding is milliseconds and an incremental
 * index would be code to get wrong.
 *
 * ## WHY BM25 AND NOT THE MEMORY RANKER
 *
 * [dev.localintelligence.core.memory.MemoryIndex] scores by term coverage, which
 * is right for short memories and wrong for passages: every passage of a long
 * document would share the common words, and a rare word such as a product code
 * should count for far more than "the". BM25 weights terms by how rare they are
 * across the passages (inverse document frequency) and damps the effect of
 * repetition, which is the standard behaviour for passage retrieval. Tokens come
 * from [lexicalTokens], the same split the tool and skill selectors use, so the
 * whole app agrees on what a word is.
 *
 * Like the memory index, it cannot match a synonym: a question worded
 * differently from the document finds nothing. That is stated to the model in
 * the tool's observation, which tells it not to guess.
 */
class DocumentIndex private constructor(
    private val passages: List<PassageEntry>,
    private val postings: Map<String, List<Pair<Int, Int>>>,
    private val avgLength: Double,
) {
    private class PassageEntry(val docId: String, val title: String, val number: Int, val text: String, val length: Int)

    /** Number of passages indexed. */
    val size: Int get() = passages.size

    /**
     * The best [limit] passages for [query], highest score first. Empty when the
     * query has no usable word or nothing matches.
     */
    fun search(query: String, limit: Int): List<Passage> {
        if (limit <= 0 || passages.isEmpty()) return emptyList()
        val scores = bm25Scores(query)
        return (scores.indices)
            .filter { scores[it] > 0.0 }
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .take(limit)
            .map { passage(it, scores[it]) }
    }

    /**
     * The best [limit] passages for [query] by hybrid score: half the BM25 score,
     * scaled so the best keyword match is 1.0, plus half the cosine similarity
     * between [queryVector] and the passage's vector.
     *
     * [vectors] holds one row of `queryVector.size` floats per passage, in index
     * order, each unit length, so the dot product is the cosine. A passage with
     * no keyword hit can still rank: that is what the vector half is for.
     * Falls back to [search] when the vectors do not line up with the passages.
     */
    fun searchHybrid(query: String, queryVector: FloatArray, vectors: FloatArray, limit: Int): List<Passage> {
        if (limit <= 0 || passages.isEmpty()) return emptyList()
        val dim = queryVector.size
        if (dim == 0 || vectors.size != passages.size * dim) return search(query, limit)

        val keyword = bm25Scores(query)
        val best = keyword.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val fused = DoubleArray(passages.size)
        val cosine = DoubleArray(passages.size)
        for (i in passages.indices) {
            var dot = 0.0
            val base = i * dim
            for (d in 0 until dim) dot += queryVector[d].toDouble() * vectors[base + d]
            cosine[i] = dot
            fused[i] = KEYWORD_WEIGHT * (keyword[i] / best) + (1.0 - KEYWORD_WEIGHT) * dot
        }
        // Without this floor every passage has a positive vector score, so a question the
        // documents do not answer still returned the top passages as "best match". A passage
        // is kept only with a keyword hit or a meaningful similarity, so "no match" can be said.
        return fused.indices
            .filter { keyword[it] > 0.0 || cosine[it] >= MIN_SEMANTIC_COSINE }
            .sortedWith(compareByDescending<Int> { fused[it] }.thenBy { it })
            .take(limit)
            .map { passage(it, fused[it]) }
    }

    private fun passage(index: Int, score: Double): Passage {
        val p = passages[index]
        return Passage(p.docId, p.title, p.number, p.text, score)
    }

    /** BM25 score of every passage for [query], in index order. Zero where no term matches. */
    private fun bm25Scores(query: String): DoubleArray {
        val scores = DoubleArray(passages.size)
        val terms = lexicalTokens(query).distinct()
        if (terms.isEmpty()) return scores

        val n = passages.size.toDouble()
        for (term in terms) {
            val list = postings[term] ?: continue
            val df = list.size.toDouble()
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            for ((doc, tf) in list) {
                val len = passages[doc].length.toDouble()
                val norm = K1 * (1.0 - B + B * len / avgLength)
                val w = tf * (K1 + 1.0) / (tf + norm)
                scores[doc] += idf * w
            }
        }
        return scores
    }

    companion object {
        const val K1: Double = 1.2
        const val B: Double = 0.75

        /** Weight of the keyword half of a hybrid score. The vector half gets the rest. */
        const val KEYWORD_WEIGHT: Double = 0.5

        /**
         * Lowest cosine that counts as a match when no keyword matches. Chosen by reasoning, not
         * measured on a phone: re-check it against real questions and documents before relying on it.
         */
        const val MIN_SEMANTIC_COSINE: Double = 0.5

        /** Builds the index over every passage of [documents], in the order given. */
        fun build(documents: List<IndexedDocument>): DocumentIndex {
            val passages = ArrayList<PassageEntry>()
            for (doc in documents) {
                DocumentChunker.chunk(doc.text).forEachIndexed { i, text ->
                    val length = lexicalTokens(text).size.coerceAtLeast(1)
                    passages += PassageEntry(doc.id, doc.title, i + 1, text, length)
                }
            }
            val postings = HashMap<String, MutableList<Pair<Int, Int>>>()
            passages.forEachIndexed { index, p ->
                val counts = HashMap<String, Int>()
                for (token in lexicalTokens(p.text)) counts[token] = (counts[token] ?: 0) + 1
                for ((token, tf) in counts) postings.getOrPut(token) { ArrayList() } += index to tf
            }
            val avg = if (passages.isEmpty()) 1.0 else passages.sumOf { it.length }.toDouble() / passages.size
            return DocumentIndex(passages, postings, avg)
        }
    }
}

/**
 * How a passage is shown to the model: one line, an excerpt of at most
 * [MAX_EXCERPT_CHARS] characters, centred on the first query word it contains.
 */
object PassageExcerpt {

    /** The model-visible budget for one excerpt. A passage is 800 characters; the model needs the part that answers. */
    const val MAX_EXCERPT_CHARS: Int = 300

    /** Characters kept before the first matching word, so the match is not the first word cut off. */
    private const val LEAD_CHARS: Int = 80

    private val WHITESPACE = Regex("\\s+")

    /**
     * The excerpt of [text] around the first word of [query] it contains, collapsed to one
     * line. The words are split the same way the index splits them, so the excerpt centres
     * on a word the search actually matched.
     */
    fun of(text: String, query: String): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.length <= MAX_EXCERPT_CHARS) return flat
        val hit = lexicalTokens(query)
            .mapNotNull { term -> flat.indexOf(term, ignoreCase = true).takeIf { it >= 0 } }
            .minOrNull() ?: 0
        var start = (hit - LEAD_CHARS).coerceAtLeast(0)
        if (start > 0) {
            val space = flat.indexOf(' ', start)
            if (space in 0 until hit) start = space + 1
        }
        val lead = if (start > 0) "…" else ""
        val room = MAX_EXCERPT_CHARS - lead.length - 1
        val body = flat.substring(start)
        return if (body.length <= room + 1) lead + body
        else lead + body.take(room).trimEnd() + "…"
    }
}
