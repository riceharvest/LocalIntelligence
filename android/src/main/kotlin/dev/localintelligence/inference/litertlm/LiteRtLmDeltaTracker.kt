package dev.localintelligence.inference.litertlm

/**
 * Reconciles a streaming runtime that may emit cumulative or incremental text.
 *
 * ## The problem
 *
 * `LiteRtLmListener.onDelta` is documented as a delta, and LiteRT-LM's
 * `MessageCallback.onMessage` is specified as carrying the **whole reply so far**.
 * Those are incompatible, and only one of them can be true on a given build. A
 * backend that guesses wrong is not slightly wrong: under cumulative semantics,
 * appending every callback re-emits the entire answer once per token, so a
 * 40-token reply renders as 820 tokens of garbage. Under delta semantics, a
 * backend that assumes cumulative silently drops every character after the first
 * callback.
 *
 * So the backend does not guess. It watches the first callbacks for evidence and
 * then commits. This is the only place that ambiguity is allowed to exist, and it
 * is resolved here where it can be tested exhaustively.
 *
 * ## How the decision is made
 *
 * The mode is decided once, from the first callback pair that actually carries
 * evidence, and then held for the rest of the stream:
 *
 * - A later chunk that **strictly extends** everything emitted so far is evidence
 *   of cumulative mode, and only its new tail is emitted.
 * - A later chunk that does **not** extend is evidence of delta mode, and it is
 *   appended as-is.
 * - A chunk that extends but adds **nothing** carries no evidence either way. In
 *   cumulative mode it is a re-send and emits nothing; in delta mode it is a
 *   genuine repeated token and is emitted.
 *
 * That last case is why the mode is committed rather than re-decided per chunk.
 * A delta runtime emitting the same token repeatedly — `"word "`, `"word "`,
 * `"word "` — is a real and common shape, and a tracker that re-decided each time
 * would see `"word "` extend `"word "`, wrongly conclude cumulative, and swallow
 * every chunk after the first. Committing to delta (the documented default)
 * until something proves otherwise handles it correctly.
 */
internal class LiteRtLmDeltaTracker {

    /** How the runtime has been observed to stream, once the evidence is in. */
    private enum class Mode {
        /** Nothing has proved either way yet; behave as a delta stream. */
        UNDECIDED,

        /** Every callback so far has strictly extended the text. */
        CUMULATIVE,

        /** A callback failed to extend, so this is a delta stream. */
        DELTA,
    }

    private val emitted = StringBuilder()
    private var mode = Mode.UNDECIDED

    /**
     * Records one callback and returns the text that is genuinely new, or an
     * empty string if the callback carried nothing new.
     */
    fun accept(chunk: String): String {
        if (chunk.isEmpty()) return ""

        val prior = emitted.toString()

        // The first callback proves nothing: every string extends the empty
        // string, so treating it as evidence would immediately and wrongly commit
        // to cumulative mode. It is simply the baseline.
        if (prior.isEmpty()) return append(chunk)

        // Already proven cumulative. A non-extending chunk here is an anomaly
        // (the runtime rewrote its own text), so it is appended rather than
        // dropped -- losing content is worse than showing an out-of-order one.
        if (mode == Mode.CUMULATIVE) {
            return if (chunk.startsWith(prior)) {
                append(chunk.substring(prior.length))
            } else {
                append(chunk)
            }
        }

        // A strict extension is the only positive evidence of cumulative
        // streaming. A chunk that merely EQUALS what we have is ambiguous on its
        // own, and reading that as cumulative is exactly what would eat a delta
        // stream's repeated tokens ("word ", "word ", "word " ...).
        if (chunk.length > prior.length && chunk.startsWith(prior)) {
            mode = Mode.CUMULATIVE
            return append(chunk.substring(prior.length))
        }

        // Otherwise: positive evidence of a delta stream, or nothing decided yet.
        // Delta is the documented default, so it is what an undecided stream
        // behaves as.
        if (mode == Mode.UNDECIDED) mode = Mode.DELTA
        return append(chunk)
    }

    private fun append(fresh: String): String {
        if (fresh.isEmpty()) return ""
        emitted.append(fresh)
        return fresh
    }

    /** The full text emitted so far, independent of how the runtime chunked it. */
    fun text(): String = emitted.toString()

    /** Character count of everything emitted, for the output budget. */
    fun length(): Int = emitted.length
}
