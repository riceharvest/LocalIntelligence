package dev.localintelligence.core.agent

import dev.localintelligence.core.compaction.RetainedHistory
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

    /**
     * How many messages have ever been appended to [messages] in this process,
     * counted across trims.
     *
     * ## WHY THIS IS A COUNTER AND NOT `messages.size`
     *
     * Because [messages] is not monotonic. `RetainedHistory.bound` trims it to
     * 32 after every run, and the loop's `foldWindow` clears and re-adds a
     * subset, so the size goes up and down for the life of the session. A
     * durable writer therefore cannot use the list length as "how much have I
     * written yet" - the correct answer after a trim is "all of it", and the
     * size says otherwise.
     *
     * What a writer needs is a stable high-water mark. This is it: the number of
     * messages the session has produced, which only ever increases. Combined
     * with the writer's own count it gives exactly how many are new, and those
     * are always the newest ones - the tail of the list - because a trim only
     * ever removes from the front.
     *
     * It counts appends through the three methods below, which are the only
     * doors into the list. [compact] and [clear] re-arrange or discard messages
     * that are already stored and so do not move it.
     */
    var appendedCount: Int = 0
        private set

    /** Appends [message] and counts it. The only way into [messages]. */
    private fun record(message: ChatMessage) {
        messages += message
        appendedCount++
    }

    /**
     * The compaction state: what the model was told, what was done, what failed,
     * folded out of [messages] when the window overflowed.
     *
     * ## THIS IS NOT PERSISTED, AND A RESTARTED SESSION THEREFORE HAS NONE
     *
     * This is the one piece of a conversation that does not survive the process.
     * `RoomSessionStore` stores the message rows and nothing else: a
     * [dev.localintelligence.core.context.CompactedState] is structured (task,
     * progress, actions, failures) and the `session_summaries` table holds a
     * rendered [String], so writing one and reading it back would be a lossy
     * round-trip through text that this module does not own.
     *
     * WHAT THAT MEANS ON A RESTART, precisely: the restored window holds the
     * recent turns verbatim and has no summary of anything older. The model is
     * therefore not misled into thinking a compacted history never happened - it
     * simply does not have one, and the older turns are not recoverable. That is
     * why the restore in `AppContainer` anchors the window on a user turn
     * rather than replaying an arbitrary prefix: a window that opens mid-answer
     * with no summary behind it is worse than no window at all.
     */
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
    fun start(task: String) = record(ChatMessage.User(task))

    /**
     * Runtime-to-model message: a malformed-output correction, a loop warning, or
     * a declined confirmation.
     *
     * Injected as an assistant turn so the retry reads as a continuation of the
     * model's own last turn. It is the runtime speaking, never the user — the
     * user only ever produces the one [ChatMessage.User] turn.
     */
    fun observe(text: String) = appendAssistant(text)

    fun appendAssistant(text: String) = record(ChatMessage.Assistant(text))

    /** [observation] must already be truncated to the model-visible budget. */
    fun appendToolObservation(tool: AgentTool, observation: String, success: Boolean) =
        record(ChatMessage.ToolObservation(tool.definition.name, observation, success))

    /**
     * Retrieval keywords for tool selection: the task plus the two most recent
     * observations. `docs/architecture.md` §11 — lexical only, no embeddings.
     */
    fun currentKeywords(): List<String> {
        // ONLY the user's own words feed tool selection.
        //
        // This previously appended the last two ToolObservations as well, which
        // closed an exfiltration chain: a hostile page fetched by web.fetch
        // becomes an observation, its text steers retrieval, retrieval decides
        // which tools the GRAMMAR exposes, and an omitted tool is not merely
        // discouraged but literally uncallable. A page that read like "documents
        // files search read contents" could therefore promote files.read_text
        // into the grammar on the next turn and have the model emit a perfectly
        // valid call. Untrusted text selecting a tool is the trust boundary
        // being crossed, not a prompt-injection detail.
        //
        // The task itself is passed to the selector separately by
        // AgentController.selectTools, so dropping observations here loses no
        // signal about what the user asked for.
        //
        // Latest user turn, not first: the session is shared and multi-run, so
        // `firstOrNull` returned the FIRST-EVER request, which stops describing
        // the current turn after the first run.
        val source = buildString {
            messages.filterIsInstance<ChatMessage.User>().lastOrNull()?.let { append(it.text) }
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

    /**
     * Replaces the window with [history], as loaded from a durable store on
     * process start, and returns how many messages were adopted.
     *
     * WHY THIS EXISTS: a session lives for one process. If the process is
     * killed for RAM the user is mid-conversation, and without this the next
     * launch opens an empty window that neither knows nor admits the exchange
     * that was in flight. The caller supplies the history; this only decides
     * how much of it is real enough to keep.
     *
     * ## THE RESTORE POLICY LIVES HERE, DELIBERATELY
     *
     * The tempting version is "take the last N and go". That is the one this
     * rejects, because on this data it produces a *worse* session than starting
     * fresh: a tail whose oldest message is an assistant fragment or a tool
     * result hands the model turns it can narrate but never asked for, and the
     * [workingSummary] that would have explained the gap did not survive either.
     * See the two rules below.
     *
     * RULE 1 - THE WINDOW MUST OPEN ON A QUESTION. A conversation is only
     * coherent from a user turn onward; anything before the first
     * [ChatMessage.User] in the tail is a dangling answer. So the window is
     * advanced to the first user turn. If the tail holds no user turn at all
     * (a run that was killed between the user pressing send and the model
     * starting to answer), there is nothing coherent to restore and this
     * returns 0, leaving the session empty - the honest outcome, because an
     * empty window admits ignorance while a fragment pretends to recall.
     *
     * RULE 2 - NEVER CROSS THE BINDING BUDGET. The store holds every message
     * ever written, but the live window is held to
     * [RetainedHistory.MAX_RETAINED_MESSAGES] by [boundHistory]. Restoring
     * more would feed the model a longer context the running app has already
     * proved it cannot hold, and would re-introduce on a cold start the exact
     * growth that the live trim exists to prevent.
     *
     * ## WHAT THIS DOES NOT RESTORE, AND WHY THAT IS FINE
     *
     * [workingSummary] is NOT restored. It is a compaction artefact: it
     * describes messages that were folded out of [messages] to make room, and
     * rehydrating it on its own would hand the model a summary of a context it
     * cannot see. Losing it costs the model the *earliest* history, which is
     * exactly the history a user re-establishes in one sentence - whereas
     * losing the recent window costs them the thread they are actively in. So
     * the trade is deliberate and the summary is the side we spend. Callers
     * that need the old context should say so in their first message.
     */
    fun restore(history: List<ChatMessage>): Int {
        if (history.isEmpty()) return 0
        val bounded = boundHistory(history)
        val firstQuestion = bounded.indexOfFirst { it is ChatMessage.User }
        if (firstQuestion < 0) return 0
        val adopted = bounded.subList(firstQuestion, bounded.size)
        messages.clear()
        messages += adopted
        appendedCount = adopted.size
        return adopted.size
    }

    /**
     * The newest [limit] messages, bounded exactly like the live window.
     *
     * WHY NOT `takeLast(limit)`: this deliberately keeps the OLDEST message
     * alongside the newest ones, mirroring what `RetainedHistory.bound`
     * preserves at run time - the run's opening task plus the recent tail.
     * Dropping that opening turn is how a restored session ends up talking
     * about something nobody asked about.
     *
     * The result is at most [limit] long: one head plus `limit - 1` of the
     * newest, so the head can never be evicted by a window that is already at
     * the budget.
     */
    private fun boundHistory(history: List<ChatMessage>): List<ChatMessage> {
        val limit = RetainedHistory.MAX_RETAINED_MESSAGES
        if (history.size <= limit) return history
        val head = history.first()
        val tail = history.subList(history.size - (limit - 1), history.size)
        return listOf(head) + tail
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
