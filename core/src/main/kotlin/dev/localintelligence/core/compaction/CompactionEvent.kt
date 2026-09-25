package dev.localintelligence.core.compaction

import dev.localintelligence.core.model.ChatMessage

/**
 * One thing that happened, in the shape the compactor needs it.
 *
 * WHY this exists instead of compacting [ChatMessage] directly: a
 * `ChatMessage.ToolObservation` carries a tool name and an observation, but NOT
 * the arguments the call was made with. `docs/architecture.md` §12 requires tool
 * names and arguments to survive compaction, and the arguments only exist on
 * `AgentAction.CallTool`. If the compactor read `ChatMessage` it would have to
 * invent them, and an invented argument is worse than a missing one — the model
 * would be told it had already called a tool with arguments it never used.
 *
 * So the loop hands over [ToolCall] events with the arguments it already parsed,
 * and [fromHistory] is the degraded adapter for callers that do not have them.
 *
 * Ordering is the caller's contract: [CompactionState.events] is read in order,
 * and "recent wins" means later in that list.
 */
sealed interface CompactionEvent {

    /**
     * Something the user said. The FIRST non-blank one becomes the task and is
     * never dropped, because losing the literal request is the worst bug this
     * component can have (`docs/architecture.md` §12).
     */
    data class UserRequest(val text: String) : CompactionEvent

    /**
     * The model narrating. The lowest-value content in the summary: it is
     * self-reported, frequently redundant with the actions it precedes, and the
     * first thing evicted when the budget binds.
     */
    data class ModelSaid(val text: String) : CompactionEvent

    /**
     * A tool call and what came back.
     *
     * @param name the exact tool name as the model wrote it. Never truncated,
     *   never re-cased, never normalised — a renamed tool in the summary is a
     *   tool the model will not call again.
     * @param arguments the exact argument text the model produced. Kept
     *   verbatim for the same reason. Blank means "the caller did not record
     *   them", which is honest; a guess would not be.
     * @param success false puts the entry in the failures slot instead of the
     *   actions slot, which is the entire reason the two are separate sections
     *   (`docs/architecture.md` §12: prose summaries lose the failure list).
     * @param observation the tool's model-facing observation, including the full
     *   error text on failure. Truncation of this is permitted (and audited) at
     *   [CompactionConfig.maxEntryChars]; truncation of [name] or [arguments] is
     *   not, anywhere.
     */
    data class ToolCall(
        val name: String,
        val arguments: String,
        val success: Boolean,
        val observation: String,
    ) : CompactionEvent

    /** Char cost of this event as it would appear in the summary, for budgeting. */
    fun estimatedChars(): Int = when (this) {
        is UserRequest -> text.length
        is ModelSaid -> text.length
        is ToolCall -> name.length + arguments.length + observation.length + 8
    }
}

/**
 * Degraded adapter: rebuild events from a raw message window.
 *
 * Use it only when the arguments are genuinely unavailable. It records them as
 * blank rather than guessing, and [CompactionConfig] then renders the call
 * without an argument clause. The cost of this path is real and worth stating:
 * a summary built from [ChatMessage] alone cannot tell
 * `files.search {"q":"pdf"}` from `files.search {"q":"png"}`, so it can dedupe
 * two DIFFERENT calls into one line. Callers holding a parsed
 * `AgentAction.CallTool` should pass [CompactionEvent.ToolCall] directly.
 *
 * Deterministic and allocation-light: one pass, no sorting, no clock.
 */
fun fromHistory(history: List<ChatMessage>, task: String? = null): List<CompactionEvent> {
    val out = ArrayList<CompactionEvent>(history.size + 1)
    if (task != null && task.isNotBlank()) out.add(CompactionEvent.UserRequest(task))
    for (message in history) {
        when (message) {
            is ChatMessage.User -> out.add(CompactionEvent.UserRequest(message.text))
            is ChatMessage.Assistant -> out.add(CompactionEvent.ModelSaid(message.text))
            is ChatMessage.ToolObservation -> out.add(
                CompactionEvent.ToolCall(
                    name = message.toolName,
                    // Not recoverable from a ToolObservation. Blank, not invented.
                    arguments = "",
                    success = message.success,
                    observation = message.observation,
                ),
            )
            // The system prompt is not history. It is re-sent every turn by the
            // context builder, so folding it into the summary would only spend
            // tokens restating what the model already has.
            is ChatMessage.System -> Unit
        }
    }
    return out
}
