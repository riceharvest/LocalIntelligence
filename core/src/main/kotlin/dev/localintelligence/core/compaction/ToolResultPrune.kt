package dev.localintelligence.core.compaction

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.tool.ObservationStore

/**
 * The deterministic prune that runs before a compaction folds the window: it
 * shrinks tool results and old tool calls in place, with no model call.
 *
 * ## WHY BEFORE A FOLD, AND WHY IT IS NOT A SUMMARY
 *
 * A fold rewrites the start of the prompt, and the next prefill pays for all of
 * it. Most of what makes a window large is the body of old tool results that
 * the model has already read, and the same page or list is often returned twice.
 * Shrinking those bodies changes the prompt once, to a fixed string, and leaves
 * every later message in place. Nothing here asks the model anything, so the
 * result is the same for the same window, and the prompt prefix stays reusable.
 *
 * ## WHAT IS LOST, AND WHAT IS NOT
 *
 * Nothing that cannot be read back. A stub keeps the observation handle
 * (`obs#41`), and the full text stays in the observation store and on disk, so
 * `observations.read` returns it. A result with no handle is not stubbed, because
 * a stub without one would be a loss. A duplicate points at the first copy, which
 * is either full or a stub with its own handle.
 *
 * ## THE THREE PASSES
 *
 *  1. Duplicates: a tool result byte-identical (same tool, same text) to an
 *     earlier one becomes one line that names the first. This applies anywhere
 *     in the window, tail included, because it loses nothing.
 *  2. Old results: a result before the protected tail, at least [MIN_RESULT_CHARS]
 *     long, with a handle, becomes a one-line stub with that handle.
 *  3. Old calls: a model's tool call before the protected tail, at least
 *     [MIN_CALL_CHARS] long, is cut to its first [KEEP_CALL_CHARS] characters.
 *
 * Only the model-facing copy changes. The durable row keeps the full text (see
 * `Session.replaceKeepingIdentity`).
 */
object ToolResultPrune {

    /** Messages at the end of the window that are never pruned. */
    const val PROTECTED_TAIL: Int = 6

    /** Shorter results are cheaper to keep than the stub is to read. */
    const val MIN_RESULT_CHARS: Int = 400

    /** Shorter calls are kept as they are. */
    const val MIN_CALL_CHARS: Int = 200

    /** How much of a cut call survives. Enough for the tool name and the first argument. */
    const val KEEP_CALL_CHARS: Int = 120

    /** Opens every stub this pass writes, so a later pass does not stub a stub again. */
    const val STUB_PREFIX: String = "[pruned: "

    private val HANDLE_RE = Regex("obs#(\\d+)")

    /**
     * The replacements for [messages], keyed by index. Empty when nothing can be
     * shrunk. Pure: the caller decides whether to apply them.
     */
    fun plan(messages: List<ChatMessage>): Map<Int, ChatMessage> {
        val protectedFrom = (messages.size - PROTECTED_TAIL).coerceAtLeast(0)
        val out = LinkedHashMap<Int, ChatMessage>()

        // Pass 1: the first identical result keeps its full text, every later copy names it.
        val firstByKey = HashMap<String, Int>()
        for ((index, message) in messages.withIndex()) {
            if (message !is ChatMessage.ToolObservation || isStub(message.observation)) continue
            val key = message.toolName + '\u0000' + message.observation
            val first = firstByKey[key]
            if (first == null) {
                firstByKey[key] = index
                continue
            }
            val keeper = messages[first] as ChatMessage.ToolObservation
            val handle = handleOf(keeper.observation)
            val reference = if (handle != null) {
                "${message.toolName} gave the same output as an earlier call (obs#$handle). Not repeated here."
            } else {
                "${message.toolName} gave the same output as an earlier call. Not repeated here."
            }
            out[index] = message.copy(observation = STUB_PREFIX + reference + "]")
        }

        // Passes 2 and 3 only touch what is before the protected tail.
        for ((index, message) in messages.withIndex()) {
            if (index >= protectedFrom || index in out) continue
            when (message) {
                is ChatMessage.ToolObservation -> {
                    if (message.observation.length < MIN_RESULT_CHARS || isStub(message.observation)) continue
                    val handle = handleOf(message.observation) ?: continue
                    val stub = "${STUB_PREFIX}${message.toolName} result, ${message.observation.length} chars, " +
                        "obs#$handle. Read it with ${ObservationStore.READ_TOOL} and id $handle if needed.]"
                    out[index] = message.copy(observation = stub)
                }

                is ChatMessage.Assistant -> {
                    val raw = message.rendered ?: continue
                    if (message.text.isNotBlank() || raw.length < MIN_CALL_CHARS || raw.contains(STUB_PREFIX)) continue
                    val closing = if (raw.contains("</tool>")) "</tool>" else ""
                    out[index] = message.copy(rendered = raw.take(KEEP_CALL_CHARS) + " $STUB_PREFIX arguments cut]" + closing)
                }

                else -> Unit
            }
        }
        return out
    }

    /** The handle a stored result carries, or null. */
    fun handleOf(observation: String): Long? =
        HANDLE_RE.find(observation)?.groupValues?.get(1)?.toLongOrNull()

    /** True for a result this pass already shrank. */
    fun isStub(observation: String): Boolean = observation.startsWith(STUB_PREFIX)
}
