package dev.localintelligence.inference.litertlm

/**
 * Accumulates the text of a streaming reply, appending every chunk exactly as
 * received.
 *
 * ## The problem
 *
 * `LiteRtLmListener.onDelta` is documented as a delta, and LiteRT-LM's
 * `MessageCallback.onMessage` has been read as carrying the **whole reply so far**.
 * Those are incompatible, and only one of them can be true on a given build. A
 * backend that guesses wrong is not slightly wrong: under cumulative semantics,
 * appending every callback re-emits the entire answer once per token, so a
 * 40-token reply renders as 820 tokens of garbage. Under delta semantics, a
 * backend that assumes cumulative silently drops every character after the first
 * callback.
 *
 * Guessing from the text is no better. A delta that happens to begin with the
 * reply so far looks exactly like a cumulative re-send, so a content-based guess
 * silently drops characters from a correct stream.
 *
 * ## The decision
 *
 * The mode is never inferred from chunk content. The pinned runtime,
 * `litertlm-android:0.17.1`, streams deltas. In its
 * `runtime/conversation/internal_callback_util.cc`, each decode event sends only
 * `accumulated_response_text.substr(cursor)` to `user_callback` and then advances
 * `cursor`. In `runtime/conversation/conversation.cc`, `SendMessageAsync` wires
 * that producer as `user_callback`, and the JNI forwards it to
 * `MessageCallback.onMessage`. No cumulative snapshot reaches the callback.
 *
 * So the tracker appends every non-empty chunk verbatim, and [text] is the join
 * of all chunks. If a runtime upgrade changes this, the change is adapted in
 * `NativeLiteRtLmEngine.kt`, the single native-adapter file. It is never inferred
 * from text again.
 */
internal class LiteRtLmDeltaTracker {

    private val emitted = StringBuilder()

    /**
     * Records one callback and returns the chunk unchanged, or an empty string if
     * the callback carried nothing.
     */
    fun accept(chunk: String): String {
        if (chunk.isEmpty()) return ""
        emitted.append(chunk)
        return chunk
    }

    /** The full text emitted so far, independent of how the runtime chunked it. */
    fun text(): String = emitted.toString()

    /** Character count of everything emitted, for the output budget. */
    fun length(): Int = emitted.length
}
