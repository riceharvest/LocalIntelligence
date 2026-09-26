package dev.localintelligence.core.agent

import dev.localintelligence.core.compaction.RetainedHistory
import dev.localintelligence.core.context.CompactedState
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import java.util.IdentityHashMap

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
 *
 * ## AND THE IDENTITY MAPS, WHICH WERE NOT
 *
 * The list above used to end there, and the omission mattered: the private
 * `ids` map and [writtenIds] were both unbounded, because an `IdentityHashMap`
 * keyed on a message object pins that object and no code path ever released
 * one. [releaseUnreachableIdentities] is called by the loop at the end of every
 * run and makes the claim true. See its KDoc for why pruning cannot lose a
 * durable write and why message ids are still never reused.
 */
class Session(
    val id: Long = 0L,
    /** Messages kept verbatim after a compaction; older ones are folded into [workingSummary]. */
    val keepRecent: Int = 4,
) {
    val messages: MutableList<ChatMessage> = mutableListOf()

    /**
     * Identity of every message already handed to the durable store.
     *
     * WHY THIS EXISTS, AND WHY A BUFFER WAS NOT ENOUGH: the store is written at
     * run boundaries, but a trim (`foldWindow`, `dropOldest*`) is destructive
     * and happens MID-run, so a message can be gone from [messages] before any
     * persist call sees it. The container used to infer what was unwritten with
     * `takeLast(appendedCount - durableWriteCount)`, assuming "a trim only ever
     * removes from the front" — false, since `foldWindow` clears and re-adds
     * `head + tail`.
     *
     * Two attempts, both simulated, both wrong:
     *  - count only: a 6-message run whose window folded to 4 wrote 1 row twice
     *    and lost 3 permanently (`durableWriteCount` advanced past the gap, so
     *    no later run retried it).
     *  - count + a drain of removed messages: no message was lost from the
     *    removed set, but `durableWriteCount` still advanced past a window that
     *    was never written — 3 still lost and 11 duplicated.
     *
     * A set of identities makes this exact and order-independent. A message is
     * written once no matter how many times it appears, survives being removed
     * and re-added, and needs no positional reasoning at all. It is bounded by
     * the number of messages in the conversation, which is already bounded.
     */
    val writtenIds: MutableSet<Long> = HashSet()

    /**
     * A stable id for [message], assigned on first ask and never reused.
     *
     * Identity cannot be content-derived: a user legitimately repeating a
     * message, or a tool returning the same observation twice, would collide
     * and silently drop the second one.
     *
     * ## THIS MAP IS A STRONG-REFERENCE PIN ON EVERY MESSAGE THE PROCESS HAS EVER SEEN
     *
     * `IdentityHashMap` holds its KEY by reference, so an entry here is not a
     * bare number — it is a number *plus a live reference to the whole
     * ChatMessage*, including its observation text, which
     * [dev.localintelligence.core.tool.ObservationTruncator] caps at 2048
     * characters. Nothing in this class ever removed an entry, so between
     * [RetainedHistory.bound] trimming the window to 32 and this map emptying
     * itself, the window was the smaller of the two by a factor that grows all
     * day: `messages` is bounded at 32, this is bounded at every message the
     * process has ever created.
     *
     * That made the KDoc on [writtenIds] wrong in the direction that matters
     * most. It said "bounded by the number of messages in the conversation,
     * which is already bounded". The second half was true and the first half
     * was not, so the sentence read as a proof of a bound that did not exist.
     * [releaseUnreachableIdentities] is what makes the sentence true; without
     * it both this map and [writtenIds] grow without limit on a device whose
     * primary metric is RAM — one unreachable observation retained per step,
     * for the life of the process, in a process already holding a
     * multi-gigabyte model.
     */
    private val ids = java.util.IdentityHashMap<ChatMessage, Long>()
    private var nextIdentity: Long = 1L

    /**
     * Forgets the identity of every message that is no longer in [messages].
     *
     * ## WHY THIS LOSES NOTHING
     *
     * A message's identity is read in exactly one place: the durable writer
     * iterates `session.messages` and asks for each one's id
     * (`AppContainer.persistConversation`). A message that has left the window
     * is therefore unreachable by the only consumer, and the entry pinning it
     * is unreachable by definition. There is no second reader to break:
     * [markAllWritten] also walks `messages`.
     *
     * ## WHY IT IS SAFE AGAINST A TRIM THAT RACES A WRITE
     *
     * The window is trimmed mid-run and the checkpoint fires *before* each
     * trim, on a separate coroutine, so a write for a message that is about to
     * leave the window can genuinely still be in flight. Pruning does not make
     * that worse, because the writer selects its work from `messages` at the
     * moment it runs: a message already trimmed is already invisible to it,
     * with or without this method. Pruning removes only what that writer could
     * not have reached anyway. A message that is STILL in the window keeps its
     * entry — that is the whole invariant — so no live message can be handed a
     * second id and written twice.
     *
     * ## WHY [nextIdentity] IS NOT RESET
     *
     * Ids must never be reused, or a row already in the durable store could
     * collide with a new message. [nextIdentity] only ever increases, so a
     * pruned id is never handed out again even though its entry is gone. That
     * is the one property a prune must not break, and it is why this removes
     * entries rather than rebuilding the map.
     */
    fun releaseUnreachableIdentities() {
        if (ids.isEmpty()) return
        // Read-only lookup, NOT `idOf`. `idOf` INSERTS, so building the live set
        // through it would hand an id to every message currently in the window
        // and grow the very map this method exists to shrink. A message in the
        // window with no entry yet has no `writtenIds` mark either, so it
        // contributes nothing that `retainAll` below could wrongly drop, and the
        // writer will assign it an id the first time it selects it.
        val live = java.util.IdentityHashMap<ChatMessage, Long>(messages.size.coerceAtLeast(1))
        messages.forEach { live[it] = ids[it] }

        val it = ids.entries.iterator()
        while (it.hasNext()) {
            if (!live.containsKey(it.next().key)) it.remove()
        }
        // Only the reachable subset, and only when the gap is wide enough to be
        // worth the rebuild. `writtenIds` holds boxed Longs rather than message
        // references, so an entry there is orders of magnitude cheaper than one
        // in `ids` was — for this set the trim is hygiene, not the fix.
        //
        // SAFE AGAINST A DOUBLE WRITE, and this is the one thing worth stating
        // precisely: the writer marks a row by selecting it FROM `messages`
        // (`session.messages.filter { session.writtenIds.add(...) }`). A message
        // absent from `messages` is therefore already invisible to the writer,
        // so dropping its mark changes nothing it could act on. A message still
        // in the window is in `live` and keeps its mark, which is what stops the
        // next checkpoint from writing it a second time.
        if (writtenIds.size > live.size) {
            writtenIds.retainAll(live.values.filterNotNull())
        }
    }

    /**
     * A stable id for [message], assigned on first ask and never reused.
     *
     * Public because the durable writer in `:app` is the other half of this
     * mechanism and cannot be `internal` to this module.
     */
    fun idOf(message: ChatMessage): Long = ids.getOrPut(message) { nextIdentity++ }

    /**
     * Marks everything currently in [messages] as already written.
     *
     * Used after a restore, where the rows came FROM the store and must not be
     * written back. Marking the live objects is exact: a message not in the
     * window is not marked, so nothing is silently skipped.
     */
    fun markAllWritten() {
        messages.forEach { writtenIds.add(idOf(it)) }
    }

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
     * Retrieval keywords for tool selection, from the latest user turn only.
     * `docs/architecture.md` §11 — lexical only, no embeddings.
     *
     * NOT "the task plus the two most recent observations" — that summary
     * outlived the fix below and described behaviour this method deliberately
     * no longer has. See the comment on the observations for why.
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
        //
        // ## WHY THE SEARCH IS BACKWARDS AND THE BUILDERS ARE GONE
        //
        // The loop calls this once per STEP, and the previous shape allocated
        // three times over to reach one string: `filterIsInstance` built a List
        // of every user turn in the window to take the last one, `buildString`
        // then wrapped that single append in a StringBuilder, and
        // `String.lowercase()` copied the whole turn again. On a shared session
        // that window holds up to 32 messages, so a step paid for a full
        // materialisation of the conversation's user turns to read one of them.
        //
        // Scanning backwards for the last `User` and returning its text
        // unchanged produces byte-identical output: `buildString { append(t) }`
        // IS `t`, so the StringBuilder was pure overhead, and `.lastOrNull()`
        // after a filter is the same element a reverse scan stops on. The
        // `lowercase()` and `split` below are unavoidable — they are the
        // transformation — but they now run over one turn instead of a
        // materialised list of them.
        var index = messages.lastIndex
        while (index >= 0) {
            val message = messages[index]
            if (message is ChatMessage.User) {
                return keywordsOf(message.text)
            }
            index--
        }
        return emptyList()
    }

    /**
     * Lowercase, split on non-alphanumerics, drop 3-or-fewer-character tokens,
     * dedupe, cap.
     *
     * Extracted so [currentKeywords] has one copy of the rule and the loop's
     * own callers cannot drift from it. The split uses a PRE-COMPILED pattern:
     * the inline `Regex("...")` this replaced was constructed on every step,
     * and `Regex` compilation is not free — it is the kind of per-step cost
     * that hides inside a call everybody assumes is a string operation.
     */
    private fun keywordsOf(text: String): List<String> =
        text.lowercase()
            .split(KEYWORD_SPLIT)
            .filter { it.length > 2 }
            .distinct()
            .take(KEYWORD_LIMIT)

    /**
     * Active token estimate for the compaction trigger.
     *
     * Unchanged in what it RETURNS, and that is the constraint this method is
     * written under. It still hands the fully rendered window to
     * [ModelBackend.countTokens], because with a model resident that call is a
     * real BPE tokenisation through `llama_tokenize` and NOT a function of the
     * text's length. Deriving the number arithmetically would be cheaper and
     * would be a behaviour change: compaction would fire on a different step,
     * and the model would be shown a different context. The trigger's firing
     * point is behaviour, so it stays.
     *
     * What changed is that [cannotReachTokenLimit] runs first, so the string is
     * built only on the steps where the answer can actually be "yes". See that
     * method for why skipping is exactly equivalent rather than approximately
     * equivalent.
     */
    fun tokens(model: ModelBackend): Int {
        val buffer = StringBuilder()
        messages.forEach { buffer.append(render(it)).append('\n') }
        workingSummary?.let { buffer.append(it.render()) }
        return model.countTokens(buffer.toString())
    }

    /**
     * True when the window provably CANNOT reach [limit] tokens, so the caller
     * can skip building the string [tokens] would have counted.
     *
     * ## THE ARGUMENT, PRECISELY
     *
     * [tokens] builds `buffer` and calls `model.countTokens(buffer.toString())`.
     * This method skips that when the same call provably could not have exceeded
     * [limit]. Two facts make the skip exact:
     *
     * 1. **A BPE tokenizer never emits more tokens than the text has
     *    characters.** Every token consumes at least one character. So
     *    `countTokens(t) <= t.length + SPECIAL_TOKENS`, where the slack is for
     *    the special tokens the native path adds — `tokenize()` in
     *    `llama_bridge.cpp` calls `llama_tokenize` with `add_special=true`,
     *    which prepends BOS and can append EOS. The approximation used when no
     *    model is resident is `length / 4` plus one per newline, which is also
     *    far below the character count.
     * 2. **This count is not below [limit].**
     *
     * Together those give the bound the caller needs: if the buffer's length is
     * under the limit, the token count is under it, [tokens] would have returned
     * at or under the limit, and the caller would have returned without
     * compacting. No threshold, no fudge factor, no case where the two answers
     * differ.
     *
     * ## WHY IT BAILS OUT WHEN A WORKING SUMMARY EXISTS
     *
     * A compacted window carries a [CompactedState] whose [CompactedState.render]
     * walks five arbitrarily long lists and builds a `String` — which is the
     * allocation this method exists to avoid. There is no cheap way to bound it:
     * `oneLine` collapses newlines but does not truncate, so entry count alone
     * does not bound length. So rather than guess a ceiling, this returns `false`
     * and lets the exact path run. That costs the optimisation on the steps
     * where a summary is present, which is the minority — a summary appears only
     * after a compaction — and it is the correct trade against a wrong bound on
     * a compaction trigger.
     *
     * ## WHY THE `": "` SEPARATOR IS COUNTED
     *
     * [render] formats an observation as `"${toolName}: ${observation}"`. Two
     * characters of separator per observation, omitted, would make the bound
     * optimistic by twice the observation count — and an optimistic bound on a
     * compaction trigger is exactly the almost-right that produces a bug nobody
     * can reproduce.
     *
     * ## WHAT IT COSTS WHEN IT ANSWERS FALSE, OR WHEN IT BAILS
     *
     * One pass over the window reading `String.length`, which is a field read
     * per message and allocates nothing.
     */
    fun cannotReachTokenLimit(limit: Int): Boolean {
        if (limit <= 0) return false
        // See above. Exactness is the whole contract of this method, so the
        // summary path opts out rather than approximating.
        if (workingSummary != null) return false
        // One per message: `tokens` appends '\n' after every one.
        var chars = messages.size
        for (message in messages) {
            chars += when (message) {
                is ChatMessage.System -> message.text.length
                is ChatMessage.User -> message.text.length
                is ChatMessage.Assistant -> message.text.length
                is ChatMessage.ToolObservation ->
                    message.toolName.length + OBSERVATION_SEPARATOR.length +
                        message.observation.length
            }
        }
        return chars + SPECIAL_TOKENS < limit
    }

    /**
     * Characters of conversation text the session is RETAINING, counted exactly.
     *
     * ## THIS IS NOT THE SAME NUMBER AS THE ONE [cannotReachTokenLimit] COUNTS,
     * AND THE DIFFERENCE IS DELIBERATE
     *
     * That method counts the RENDERED form, which includes the `": "` between a
     * tool's name and its observation, because that is what gets tokenised. This
     * one counts the strings the session HOLDS, which does not include it,
     * because the separator is not allocated — it is a format artifact
     * conjured by [render] each time and thrown away.
     *
     * The distinction is the whole point of each: one answers "is the model
     * about to overflow", the other answers "how much is the agent's own code
     * holding in RAM", and a RAM figure that included characters that are not
     * allocated would be over-reporting the thing it is supposed to measure.
     * Conflating them was a real mistake during this work, so the comment above
     * is here to stop the next reader from "fixing" one to match the other.
     *
     * The tool name IS counted, because it IS a retained `String` on the
     * message — not a rendering artifact.
     *
     * O(n) in the window with no allocation — [String.length] is a field read —
     * and it is a count, not an estimate, which is what lets a memory probe
     * report it. Public for that reason: the figure a diagnostics screen shows
     * has to be derivable, and the only honest way to derive it is to walk the
     * list the number describes.
     */
    fun exactRetainedChars(): Int {
        var total = 0
        for (message in messages) {
            total += when (message) {
                is ChatMessage.System -> message.text.length
                is ChatMessage.User -> message.text.length
                is ChatMessage.Assistant -> message.text.length
                // The tool name is a retained String on the message. The ": "
                // is NOT — see the KDoc on why this differs from the count in
                // `cannotReachTokenLimit`.
                is ChatMessage.ToolObservation ->
                    message.toolName.length + message.observation.length
            }
        }
        return total
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
        // The separator is the SAME constant `cannotReachTokenLimit` counts, so
        // the two cannot drift apart. A bound computed against a different
        // format than the one `tokens` counts is a bound that is wrong by
        // exactly the difference, silently.
        is ChatMessage.ToolObservation ->
            message.toolName + OBSERVATION_SEPARATOR + message.observation
    }

    private companion object {
        const val SUMMARY_BUDGET_CHARS = 1200
        const val LINE_CHARS = 120
        const val KEYWORD_LIMIT = 12

        /**
         * Between an observation's tool name and its text, in [render].
         *
         * Shared with [cannotReachTokenLimit] so the rendered format and the
         * bound computed against it cannot drift. See the KDoc on
         * [cannotReachTokenLimit] for why an optimistic bound there matters.
         */
        const val OBSERVATION_SEPARATOR = ": "

        /**
         * Slack for the special tokens the native tokenizer adds.
         *
         * `llama_bridge.cpp` tokenizes with `add_special=true`, so a text of N
         * characters can come back as N+2 tokens — a leading BOS and a trailing
         * EOS. Without this the bound would be tight to the character and could
         * exceed the limit by two tokens on the exact step that decides whether
         * to compact. Two is the whole number: one per end, and a vocabulary
         * that adds more than one at an end is not a thing BPE vocabularies do.
         */
        const val SPECIAL_TOKENS = 2

        /**
         * The token split for [currentKeywords], compiled once.
         *
         * WHY A CONSTANT: the pattern used to be written inline as
         * `Regex("[^a-z0-9]+")` inside the method, so the agent loop recompiled
         * it on every step of every run. `MemoryQueries` in `:android` has
         * always held its identical pattern as a `private val` for this reason,
         * so this is the codebase's own convention catching a file that had not
         * caught up yet.
         */
        val KEYWORD_SPLIT = Regex("[^a-z0-9]+")
    }
}
