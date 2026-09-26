package dev.localintelligence.core.agent

import dev.localintelligence.core.context.CompactedState
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator

/**
 * Mutable state for one in-flight run. Not a persistence type — the durable
 * history lives in a `SessionStore` and the model never sees more than the
 * bounded window kept here.
 *
 * Everything in this class is bounded on purpose (a phone, not a server):
 *  - `messages` is trimmed by `RetainedHistory.bound` to at most 32 entries
 *    after a run ends, keeping the opening task, the newest turns, and every
 *    user turn that fits
 *  - `currentKeywords` is capped at [KEYWORD_LIMIT] terms
 *  - every folded summary line is capped at [LINE_CHARS], and the rendered
 *    summary at [SUMMARY_BUDGET_CHARS]
 */
class Session(
    val id: Long = 0L,
    /** Messages kept verbatim after a compaction; older ones are folded into [workingSummary]. */
    val keepRecent: Int = 4,
) {
    val messages: MutableList<ChatMessage> = mutableListOf()

    var workingSummary: CompactedState? = null

    /**
     * Records the task as a new opening user turn.
     *
     * WHY NOT IDEMPOTENT ANY MORE: this used to add the turn only if the
     * session held no [ChatMessage.User] at all, which is correct for a
     * single-run session and catastrophic for a shared one. With one
     * long-lived session per app, the second question the user ever asked was
     * silently dropped - the model would answer the first question again and
     * the app would look broken in a way nothing could explain. Every turn is
     * now appended.
     *
     * The list is bounded after each run by `RetainedHistory.bound`, which caps
     * it at MAX_RETAINED_MESSAGES and preserves the opening task, the newest
     * turns, and user turns. Note that [compact] does NOT run per run - the
     * loop calls it mid-run when the token budget is exceeded - so it is not
     * what stops the list growing across a day of scheduled runs.
     */
    fun start(task: String) {
        messages += ChatMessage.User(task)
    }

    /**
     * Runtime-to-model message: a malformed-output correction, a loop warning, or
     * a declined confirmation.
     *
     * Injected as an assistant turn so the retry reads as a continuation of the
     * model's own last turn. It is the runtime speaking, never the user — the
     * user only ever produces the one [ChatMessage.User] turn.
     */
    fun observe(text: String) = appendAssistant(text)

    fun appendAssistant(text: String) {
        messages += ChatMessage.Assistant(text)
    }

    /** [observation] must already be truncated to the model-visible budget. */
    fun appendToolObservation(tool: AgentTool, observation: String, success: Boolean) {
        messages += ChatMessage.ToolObservation(tool.definition.name, observation, success)
    }

    /**
     * Retrieval keywords for tool selection: the task plus the two most recent
     * observations. `docs/architecture.md` §11 — lexical only, no embeddings.
     */
    fun currentKeywords(): List<String> {
        val source = buildString {
            messages.filterIsInstance<ChatMessage.User>().firstOrNull()?.let { append(it.text) }
            messages.filterIsInstance<ChatMessage.ToolObservation>()
                .takeLast(2)
                .forEach { append(' ').append(it.observation) }
        }
        return source.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .distinct()
            .take(KEYWORD_LIMIT)
    }

    /**
     * Active token estimate for the compaction trigger. Best effort: one
     * allocation per call, no cache (a cache would be unbounded state on a phone).
     */
    fun tokens(model: ModelBackend): Int {
        val buffer = StringBuilder()
        messages.forEach { buffer.append(render(it)).append('\n') }
        workingSummary?.let { buffer.append(it.render()) }
        return model.countTokens(buffer.toString())
    }

    /**
     * Folds everything older than the last [keepRecent] messages into
     * [workingSummary] and re-inserts a rendered summary, so the model keeps the
     * task and the outcome of what was dropped.
     *
     * Deterministic and model-free: compaction spends no inference
     * (`docs/architecture.md` §12). Returns true when the window actually shrank.
     */
    fun compact(): Boolean {
        val keep = keepRecent.coerceAtLeast(1)
        if (messages.size <= keep + 1) return false

        val head = messages.first()
        val folded = messages.subList(1, messages.size - keep).toList()
        val tail = messages.subList(messages.size - keep, messages.size).toList()
        val previous = workingSummary

        val state = CompactedState(
            task = (head as? ChatMessage.User)?.text ?: previous?.task.orEmpty(),
            progress = (previous?.progress.orEmpty() +
                folded.filterIsInstance<ChatMessage.Assistant>().map { it.text.take(LINE_CHARS) })
                .takeLast(6),
            actionsTaken = (previous?.actionsTaken.orEmpty() +
                folded.filterIsInstance<ChatMessage.ToolObservation>().map { it.toolName })
                .distinct().takeLast(8),
            failures = (previous?.failures.orEmpty() +
                folded.filterIsInstance<ChatMessage.ToolObservation>().filterNot { it.success }
                    .map { it.observation.take(LINE_CHARS) })
                .takeLast(8),
        )
        workingSummary = state

        messages.clear()
        messages += head
        messages += ChatMessage.Assistant(
            ObservationTruncator.truncate(state.render(), SUMMARY_BUDGET_CHARS),
        )
        messages += tail
        return true
    }

    fun clear() {
        messages.clear()
        workingSummary = null
    }

    private fun render(message: ChatMessage): String = when (message) {
        is ChatMessage.System -> message.text
        is ChatMessage.User -> message.text
        is ChatMessage.Assistant -> message.text
        is ChatMessage.ToolObservation -> "${message.toolName}: ${message.observation}"
    }

    private companion object {
        const val SUMMARY_BUDGET_CHARS = 1200
        const val LINE_CHARS = 120
        const val KEYWORD_LIMIT = 12
    }
}
