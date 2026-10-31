package dev.localintelligence.core.context

import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.tool.ToolDefinition

/**
 * Deterministic prompt assembly for a small local model.
 *
 * Order of the returned list is the whole contract, and it runs from what
 * never changes to what changes every turn, so a new message only adds to the
 * end of the previous prompt and llama.cpp reuses everything before it:
 *
 *  1. system message — the base prompt and the session's tools (stable)
 *  2. the compacted working summary, if the loop supplied one (changes only at
 *     a compaction; see [SummaryWriter])
 *  3. earlier conversation, oldest-first, each user turn as it was rendered
 *     when it was sent ([ChatMessage.User.rendered])
 *  4. the current task, rendered by [renderTurn]: the task first, then the
 *     context for it (clock when the task is about time, memories, skill hints)
 *     under a label that says it is not part of the request
 *  5. this run's own turns, oldest-first
 *
 * Memories used to be their own block between 2 and 3. They depend on the
 * task, so the prompt changed near its start on every message and nothing
 * after that point could be reused; they now ride in the turn they were
 * found for.
 *
 * The history is one long-lived session shared by every run, so earlier
 * conversation comes before the task and this run's own steps after it.
 *
 * The budget is the point. A routine Android action must never need a 20K prefill
 * (docs/architecture.md section 9): prefill is latency, prefill is battery.
 *
 * ## How the compacted summary gets in
 *
 * [ContextBuilder.build] is frozen and has no summary parameter, so the summary
 * arrives as the FIRST element of [history]: a `System` message beginning with
 * [SummaryWriter.SUMMARY_PREFIX] that the compactor rendered. It is lifted out of
 * [history] and re-emitted in slot 2. The agent loop owns that convention; it is
 * one line of the loop (prepend `contextCompactor.summaryMessage(state)` when
 * handing history to the builder).
 *
 * ## What is never dropped
 *
 * The system message (it carries the tool definitions) and the current task. A
 * context with no tool list is useless to the model, so tool definitions are
 * budgeted FIRST and the remainder is spent on history. When even those two do
 * not fit, [build] still returns a valid two-message list rather than throwing:
 * a too-large prompt is a quality problem, an exception is a crash.
 *
 * ## How history is trimmed
 *
 * By dropping WHOLE messages, oldest first, never by slicing. A half-truncated
 * JSON tool observation is worse than no observation at all: it teaches the
 * model that JSON is allowed to be malformed.
 *
 * ## RAM (docs/architecture.md section 16)
 *
 * Every buffer here has a stated worst case, for a 6000-token working limit:
 *
 *  - the output list: 4 fixed + at most [MAX_HISTORY_SCAN] turns = 68 refs
 *  - the history window: a [java.util.List.subList] VIEW, zero copy
 *  - the earlier conversation without repeats of the task: at most 64 refs
 *  - generated strings: system prompt (bounded by the caller's tool set, which
 *    architecture section 11 hard-caps at 8 tools), memories <= ~1 KB
 *
 * Worst case builder-owned heap: **~12 KB**. The builder holds no state between
 * calls, so it is garbage the moment `build` returns. There is no cache here and
 * there must never be one.
 */
class DefaultContextBuilder(
    private val systemPrompt: (List<ToolDefinition>, List<String>) -> String =
        { tools, lines -> SystemPrompts.forTools(tools, sessionLines = lines) },
    /**
     * A safety net, not the compaction policy: the agent loop compacts before a
     * prompt gets near this. Trimming here drops the oldest turn, which shifts
     * every later token and costs a full prefill, so it only happens when the
     * loop's own budget was wrong.
     */
    private val workingLimit: Int = 6000,
    /** What starts every new user turn: the clock line. Read once per turn. */
    private val turnPreamble: () -> String = { "" },
) : ContextBuilder {

    /**
     * The task first, then its context under a label. The model reads the
     * start of a turn as the request, so the context must never lead: on a short
     * message a clock line at the front was taken for the task itself.
     * The clock is added only for a task about time (see [TIME_TASK]).
     */
    override fun renderTurn(task: String, memories: List<Memory>, hints: List<String>): String {
        val parts = ArrayList<String>(3)
        if (TIME_TASK.containsMatchIn(task)) {
            turnPreamble().takeIf { it.isNotBlank() }?.let { parts += it }
        }
        renderMemories(memories)?.let { parts += it }
        hints.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString("\n") }
        if (parts.isEmpty()) return task
        return task + CONTEXT_SEPARATOR + parts.joinToString("\n\n")
    }

    override fun build(
        task: String,
        history: List<ChatMessage>,
        memories: List<Memory>,
        tools: List<ToolDefinition>,
        sessionLines: List<String>,
    ): List<ChatMessage> {
        val limit = workingLimit.coerceAtLeast(0)

        val summary = summaryOf(history)
        val window = turnWindow(history, summary != null)

        // The live task turn is the newest user turn equal to the task. What came
        // before it is earlier conversation; what came after it is this run's own
        // steps. Other exact copies of the task in the earlier conversation are
        // dropped, and the answers that followed them stay.
        val live = window.indexOfLast { it is ChatMessage.User && it.text == task }
        val prior: List<ChatMessage> = if (live < 0) {
            emptyList()
        } else {
            window.subList(0, live).filterNot { it is ChatMessage.User && it.text == task }
        }
        val current: List<ChatMessage> = if (live < 0) window else window.subList(live + 1, window.size)

        // Capacity: 4 fixed slots + the window. Never grows beyond the scan cap.
        val out = ArrayList<ChatMessage>(4 + ContextLimits.MAX_HISTORY_SCAN)
        var used = 0

        // 1. System prompt + tool definitions. Budgeted first, never dropped.
        val system = ChatMessage.System(systemPrompt(tools, sessionLines))
        out.add(system)
        used += TokenEstimate.tokens(system)

        // 2. Compacted working summary.
        if (summary != null) {
            out.add(summary)
            used += TokenEstimate.tokens(summary)
        }

        // 3. Memories are in the task turn now ([renderTurn]); `memories` is
        //    unused here and kept for the interface.

        // 4. The current task, as it was rendered when the turn started. A
        //    blank task is not a task; spending 4 tokens on an empty user turn
        //    helps nobody.
        val liveTurn = if (live >= 0) window[live] as ChatMessage.User else null
        val taskMessage = if (task.isBlank()) null else ChatMessage.User(liveTurn?.rendered ?: task)
        if (taskMessage != null) used += TokenEstimate.tokens(taskMessage)

        // 5. Turns. This run's own turns are budgeted first, then the earlier
        //    conversation gets what is left. Each kept set is a contiguous suffix.
        val currentFrom = newestFitFrom(current, limit - used)
        used += TokenEstimate.tokens(current.subList(currentFrom, current.size))
        val priorFrom = newestFitFrom(prior, limit - used)
        used += TokenEstimate.tokens(prior.subList(priorFrom, prior.size))

        // Emitted in conversation order: earlier conversation, the task, this run.
        // Earlier user turns go in as they were rendered, so the tokens match
        // the previous prompt's exactly.
        prior.subList(priorFrom, prior.size).mapTo(out) { asSent(it) }
        var taskIndex = -1
        if (taskMessage != null) {
            taskIndex = out.size
            out.add(taskMessage)
        }
        current.subList(currentFrom, current.size).mapTo(out) { asSent(it) }

        // 6. Still over budget even with zero turns kept. That can only mean the
        //    fixed slots alone exceed the limit, so drop the oldest remaining
        //    non-system message and re-check. Removable range is index 1 up to
        //    (not including) the task; the system message and the task survive.
        if (used > limit) {
            var end = if (taskIndex >= 0) taskIndex else out.size
            while (used > limit && end > 1) {
                used -= TokenEstimate.tokens(out[1])
                out.removeAt(1)
                end--
            }
        }

        return out
    }

    /** A turn as the model saw or wrote it (its rendered text); anything else unchanged. */
    private fun asSent(message: ChatMessage): ChatMessage = when {
        message is ChatMessage.User && message.rendered != null -> ChatMessage.User(message.rendered, note = message.note)
        message is ChatMessage.Assistant && message.rendered != null -> ChatMessage.Assistant(message.rendered)
        else -> message
    }

    /**
     * The compacted summary, if the loop prepended one, else null.
     * Checks position 0 only: a summary anywhere else would be indistinguishable
     * from a real conversation turn, and guessing here would corrupt the context.
     */
    private fun summaryOf(history: List<ChatMessage>): ChatMessage.System? {
        val first = history.firstOrNull() as? ChatMessage.System ?: return null
        return if (first.text.startsWith(SUMMARY_PREFIX)) first else null
    }

    /**
     * The candidate turn window: the newest [ContextLimits.MAX_HISTORY_SCAN]
     * messages, minus a leading summary. A subList VIEW, not a copy, so scanning
     * a 10,000-message session allocates nothing.
     */
    private fun turnWindow(history: List<ChatMessage>, hasSummary: Boolean): List<ChatMessage> {
        val start = if (hasSummary) 1 else 0
        return history.subList(maxOf(start, history.size - ContextLimits.MAX_HISTORY_SCAN), history.size)
    }

    /**
     * Where the kept part of [turns] starts: the newest whole turns that fit in
     * [budget] tokens, as one contiguous run that ends at the newest turn.
     * Returns `turns.size` when not even the newest turn fits.
     */
    private fun newestFitFrom(turns: List<ChatMessage>, budget: Int): Int {
        var spent = 0
        var from = turns.size
        while (from > 0) {
            val cost = TokenEstimate.tokens(turns[from - 1])
            if (spent + cost > budget) break
            spent += cost
            from--
        }
        return from
    }

    /**
     * Memories as labelled one-per-line facts. Blank entries are dropped rather
     * than rendered: an empty bullet is pure waste in the tightest budget in the
     * system. Per-line truncation is the ONE place slicing is allowed — a memory
     * is prose the runtime wrote, never model-emitted JSON.
     */
    private fun renderMemories(memories: List<Memory>): String? {
        if (memories.isEmpty()) return null
        val lines = ArrayList<String>(ContextLimits.MAX_MEMORY_LINES)
        for (memory in memories) {
            if (lines.size >= ContextLimits.MAX_MEMORY_LINES) break
            val text = memory.text.trim()
            if (text.isEmpty()) continue
            lines += "- " + truncate(text, ContextLimits.MEMORY_LINE_CHARS)
        }
        if (lines.isEmpty()) return null
        return buildString {
            appendLine(MEMORY_HEADER)
            lines.forEach { appendLine(it) }
        }.trimEnd()
    }

    private fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"
}

/**
 * Character-based token estimate, matching `NoopModelBackend.countTokens`
 * (`length / 4`) so the builder's budget and the backend's accounting agree
 * without :core taking a dependency on a live model.
 *
 * Deliberately an `object` with no state: it allocates nothing and holds nothing.
 */
internal object TokenEstimate {
    /** Matches the 4-chars-per-token rule used by NoopModelBackend. */
    const val CHARS_PER_TOKEN = 4

    /** Role marker + separator, per message. */
    const val MESSAGE_OVERHEAD_TOKENS = 4

    fun tokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    fun tokens(message: ChatMessage): Int = when (message) {
        is ChatMessage.System -> tokens(message.text) + MESSAGE_OVERHEAD_TOKENS
        // What the model is given: the rendered turn when there is one.
        is ChatMessage.User -> tokens(message.rendered ?: message.text) + MESSAGE_OVERHEAD_TOKENS
        is ChatMessage.Assistant -> tokens(message.rendered ?: message.text) + MESSAGE_OVERHEAD_TOKENS
        is ChatMessage.ToolObservation ->
            tokens(message.modelFacing()) + MESSAGE_OVERHEAD_TOKENS
    }

    fun tokens(messages: List<ChatMessage>): Int = messages.sumOf { tokens(it) }
}

/** Context-assembly constants. Grouped here so tests reference one place. */
internal object ContextLimits {
    /**
     * How many history messages are even considered. A session is compacted long
     * before 64 turns, so this is a RAM bound, not a quality knob. Older messages
     * are represented by the compacted summary; if there is no summary, the
     * window means those turns were dropped — which is the correct outcome anyway,
     * they did not fit.
     */
    const val MAX_HISTORY_SCAN = 64

    /** docs/architecture.md section 14: 3-5 memories maximum. */
    const val MAX_MEMORY_LINES = 5

    /** Per-memory line cap. 5 x 160 chars ~= 200 tokens for the whole block. */
    const val MEMORY_LINE_CHARS = 160
}

/** Header on the memory block. A label, so a small model reads the lines as facts. */
internal const val MEMORY_HEADER = "Remembered facts:"

/**
 * Put between the task and its context in a rendered turn. The label tells the
 * model the context below is not the request. Exact text: it is what was measured.
 */
internal const val CONTEXT_SEPARATOR =
    "\n\n---\nContext for this message (not part of the request):\n"

/**
 * Which tasks get the clock line. WHY: measured with the app's own agent loop
 * (LFM3-1.7B, 36 runs), a clock line on every turn made short messages read as
 * scheduling requests (the model set alarms on "hi"). With the clock only for
 * time-related tasks, 34/36 passed against 19-20/36 with it always present.
 * Case-insensitive, whole words.
 */
internal val TIME_TASK = Regex(
    """\b(today|tomorrow|tonight|yesterday|morning|evening|afternoon|noon|midnight|week|weekend|month|year|monday|tuesday|wednesday|thursday|friday|saturday|sunday|minutes?|hours?|seconds?|at \d|\d:\d|\d ?(am|pm)|calendar|agenda|schedule|alarm|wake|remind|timer|date|time|morgen|vandaag|vanavond|uur|minuten)\b""",
    RegexOption.IGNORE_CASE,
)
