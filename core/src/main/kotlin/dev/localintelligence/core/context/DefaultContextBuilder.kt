package dev.localintelligence.core.context

import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.token.ContextCeiling
import dev.localintelligence.core.tool.ToolDefinition

/**
 * Deterministic prompt assembly for a small local model.
 *
 * Order of the returned list is the whole contract:
 *
 *  1. system message — the tiny base prompt plus ONLY the currently selected tools
 *  2. the compacted working summary, if the loop supplied one (see [SummaryWriter])
 *  3. memories, as short labelled lines, capped and truncated
 *  4. the current task, as the current user message
 *  5. recent turns, oldest-first, trimmed to fit what is left of the budget
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
 * ## The ceiling, and why it is not a constructor argument you can set freely
 *
 * This is the component that decides how much text reaches the model, so it is
 * where the 6000-vs-4096 defect lived: it was handed `workingLimit = 6000` and
 * assembled a 4259-token prompt against a 4096-token KV cache, which is a
 * prefill llama.cpp silently clamps. The limit is now [ContextCeiling]'s to
 * decide, from the window the loaded model reports, and this class only
 * *reads* it — see [modelContextTokens].
 *
 * ## RAM (docs/architecture.md section 16)
 *
 * Every buffer here has a stated worst case, for a [ContextCeiling] working
 * limit:
 *
 *  - the output list: 4 fixed + at most [MAX_HISTORY_SCAN] turns = 68 refs
 *  - the history window: a [java.util.List.subList] VIEW, zero copy
 *  - the newest-first accumulation buffer: at most 64 refs
 *  - generated strings: system prompt (bounded by the caller's tool set, which
 *    architecture section 11 caps the set at 10 tools), memories <= ~1 KB
 *
 * Worst case builder-owned heap: **~12 KB**. The builder holds no state between
 * calls, so it is garbage the moment `build` returns. There is no cache here and
 * there must never be one.
 */
class DefaultContextBuilder(
    private val systemPrompt: (List<ToolDefinition>) -> String = SystemPrompts::forTools,
    /**
     * The loaded model's real context window, read at BUILD time rather than
     * captured at construction.
     *
     * A lambda, not an Int, and that is the entire fix. The model is loaded
     * after the container exists, and it can be swapped or unloaded, so a
     * limit captured in a constructor is a limit priced against whatever
     * happened to be loaded when the object was built. Reading it per call is
     * what makes "if the model reports a different context length at load, the
     * budget follows THAT" true rather than aspirational.
     *
     * Defaults to the fallback window, so a builder constructed without a model
     * still refuses to exceed the smallest cache the app can create.
     */
    private val modelContextTokens: () -> Int = { ContextCeiling.FALLBACK_WINDOW_TOKENS },
    /**
     * An EXPLICIT working limit, overriding the derived one.
     *
     * Escape hatch, not a tuning knob: it exists for a caller that has measured
     * a real window and wants to state it directly (the eval harness does).
     *
     * WHY IT IS NOT THE DEFAULT ANY MORE: this parameter is how a 6000-token
     * prompt got assembled against a 4096-token KV cache. The number is now
     * [ContextCeiling]'s to derive from the model, and anything that reaches
     * for this is asserting a fact about hardware it should be reading from
     * [modelContextTokens] instead.
     */
    private val workingLimit: Int? = null,
) : ContextBuilder {

    override fun build(
        task: String,
        history: List<ChatMessage>,
        memories: List<Memory>,
        tools: List<ToolDefinition>,
    ): List<ChatMessage> {
        val limit = (workingLimit ?: ContextCeiling.workingLimit(modelContextTokens()))
            .coerceAtLeast(0)

        val summary = summaryOf(history)
        val turns = turnWindow(history, summary != null, task)

        // Capacity: 4 fixed slots + the window. Never grows beyond the scan cap.
        val out = ArrayList<ChatMessage>(4 + ContextLimits.MAX_HISTORY_SCAN)
        var used = 0

        // 1. System prompt + tool definitions. Budgeted first, never dropped.
        val system = ChatMessage.System(systemPrompt(tools))
        out.add(system)
        used += TokenEstimate.tokens(system)

        // 2. Compacted working summary.
        if (summary != null) {
            out.add(summary)
            used += TokenEstimate.tokens(summary)
        }

        // 3. Memories. Facts, not prose: capped, one truncated line each.
        val memoryBlock = renderMemories(memories)
        if (memoryBlock != null) {
            val message = ChatMessage.User(memoryBlock)
            out.add(message)
            used += TokenEstimate.tokens(message)
        }

        // 4. The current task. A blank task is not a task; spending 4 tokens on
        //    an empty user turn helps nobody.
        val taskIndex = if (task.isBlank()) -1 else {
            val message = ChatMessage.User(task)
            out.add(message)
            used += TokenEstimate.tokens(message)
            out.size - 1
        }

        // 5. Recent turns, oldest-first. Accumulate newest-first so the kept set
        //    is always a contiguous suffix, then reverse to restore the order.
        val kept = ArrayList<ChatMessage>(ContextLimits.MAX_HISTORY_SCAN)
        for (i in turns.indices.reversed()) {
            val cost = TokenEstimate.tokens(turns[i])
            if (used + cost > limit) break
            kept.add(turns[i])
            used += cost
        }
        kept.reverse()
        out.addAll(kept)

        // 6. Still over budget even with zero turns kept. That can only mean the
        //    fixed slots alone exceed the limit, so drop the oldest remaining
        //    non-system message and re-check. Removable range is index 1 up to
        //    (not including) the task; the system message and the task survive.
        if (used > limit) {
            val removableEnd = if (taskIndex >= 0) taskIndex else out.size
            var i = 1
            while (used > limit && i < removableEnd) {
                used -= TokenEstimate.tokens(out[i])
                out.removeAt(i)
                // Dropping one message can only shrink `out`, so the protected
                // task's index moves down with it.
                i++
            }
        }

        return out
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
     *
     * Also drops a leading user turn that is byte-identical to [task]. The loop
     * re-sends the current task as its own message, and the session's opening
     * turn is usually that same text. Emitting it twice costs tokens and, worse,
     * makes a 3B model read the request as two separate instructions. Exact
     * match only — never a fuzzy one, because dropping a merely similar message
     * would silently discard a real instruction.
     */
    private fun turnWindow(
        history: List<ChatMessage>,
        hasSummary: Boolean,
        task: String,
    ): List<ChatMessage> {
        var start = if (hasSummary) 1 else 0
        if (start < history.size) {
            val first = history[start]
            if (first is ChatMessage.User && first.text == task) start++
        }
        // The current task is ALSO appended to the shared session, so it is
        // normally the LAST user turn in history, not the first one. The check
        // above only ever examined index `start`, which under a shared session
        // is the first-ever turn — so from run 2 onward the builder emitted:
        //
        //     User(task)      <- added explicitly, as the live instruction
        //     User(task 1) / Assistant(a1) / User(task 2)   <- history
        //
        // i.e. the live instruction appeared TWICE, once first and once last.
        // For a 1-4B model that is not a token-budget nuisance: the duplicated
        // instruction is the most recent thing in the context and gets the
        // strongest positional weight, so the model follows the older copy.
        //
        // Drop the trailing run of user messages equal to the current task —
        // `Session.start` appends exactly one, but matching a trailing block
        // costs nothing and is robust to a future that appends the preamble
        // before it.
        val historyEnd = trimTrailingCurrentTask(history, start, task)
        if (start >= historyEnd) return emptyList()
        val from = maxOf(start, historyEnd - ContextLimits.MAX_HISTORY_SCAN)
        val window = history.subList(from, historyEnd)

        // A repeat of the live task anywhere in the window, not just at either
        // edge. This is the state the loop reaches as soon as history holds a
        // PRIOR exchange, because the task turn then sits in the middle:
        //
        //   User(older) / Assistant(a) / User(task) / ToolObservation(o)
        //
        // The two edge cases above miss that entirely - it is not the leading
        // turn and not the trailing run - so the builder emitted the live
        // instruction twice, and the stale middle copy is the one the model
        // sees with the strongest positional weight.
        //
        // The ANSWER that followed an identical earlier question is kept. Only
        // the instruction itself is dropped: re-asking the same thing must not
        // cost the user the reply they already received.
        //
        // Exact match only, so a merely similar request is never discarded.
        val kept = window.filterNot { it is ChatMessage.User && it.text == task }
        return if (kept.size == window.size) window else kept
    }

    /**
     * Index one past the last historical message that is not a repeat of the
     * current task, scanning back from the end of history.
     *
     * Never scans past [floor]: a summary or genuine earlier turn with the same
     * text is history worth keeping.
     */
    private fun trimTrailingCurrentTask(
        history: List<ChatMessage>,
        floor: Int,
        task: String,
    ): Int {
        var end = history.size
        while (end > floor) {
            val message = history[end - 1]
            if (message is ChatMessage.User && message.text == task) end-- else break
        }
        return end
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
        is ChatMessage.User -> tokens(message.text) + MESSAGE_OVERHEAD_TOKENS
        is ChatMessage.Assistant -> tokens(message.text) + MESSAGE_OVERHEAD_TOKENS
        // The model sees the tool name and the observation, so both are paid for.
        is ChatMessage.ToolObservation ->
            tokens(message.toolName) + tokens(message.observation) + MESSAGE_OVERHEAD_TOKENS
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
