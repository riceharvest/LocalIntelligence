package dev.localintelligence.core.trace

import dev.localintelligence.core.tool.redaction.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * How much the trace keeps, and what it costs.
 *
 * ## THE RULE THIS TYPE EXISTS TO ENFORCE
 *
 * **RAM is the product's primary metric.** The user does not care about disk.
 * So the default policy retains **structure and arithmetic but no prose**:
 * every event is recorded - which tools were and were not selected and with
 * what score, the gate's verdict and its per-bucket arithmetic, the parsed
 * action, the call's name, the compaction's before/after - and every string is
 * recorded as a LENGTH.
 *
 * That is the answer to "what did the agent actually do" for every failure mode
 * this project has shipped, at a cost bounded by
 * [maxTotalChars] regardless of how long a run goes.
 *
 * ## WHY [maxBodyChars] IS SEPARATE FROM [maxTotalChars]
 *
 * They answer different questions. [maxTotalChars] bounds the buffer, so a
 * thousand-step run cannot grow it. [maxBodyChars] bounds a SINGLE string, so
 * one 4 MB `logcat` observation cannot own the buffer on its own - which is the
 * case a total-only cap gets wrong, because a total cap is satisfied by
 * evicting everything else.
 *
 * Bodies are opt-in. Turning them on for a debugging session is one value
 * change, and it is a *policy* change rather than a code path, so there is no
 * second recorder to keep in sync.
 */
data class TracePolicy(
    /** Off means zero allocation and zero retention. See [DecisionTrace.off]. */
    val enabled: Boolean = true,
    /**
     * Hard ceiling on the ring buffer, in characters, INCLUDING retained prose.
     *
     * 64 KB is chosen against the two things that are actually in the window on
     * a phone: a 2.4 GB model (so 64 KB is 0.003% of the process) and a
     * conversation that has to survive a process death. Eviction is oldest-first
     * and the count of evicted lines is reported, so a truncated trace says so
     * rather than reading as a complete one.
     */
    val maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
    /**
     * Characters of a single string kept. 0 keeps none.
     *
     * At the default the trace answers every structural question and contains
     * no user text at all - which also means the default trace is safe to
     * attach to a bug report without a second thought.
     */
    val maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS,
    /**
     * Whether the filter runs on captured prose.
     *
     * Default TRUE, and it is not optional in practice: a trace is a file a
     * user may share, and the one thing that must never be in it is a
     * credential. The filter is
     * [dev.localintelligence.core.tool.redaction.SecretRedactor] - the same
     * object [dev.localintelligence.core.tool.redaction.RedactingToolRegistry]
     * uses, and a documented fixed point, so running it again here adds no
     * second pattern set and no second failure mode.
     */
    val redact: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_TOTAL_CHARS = 64 * 1024
        const val DEFAULT_MAX_BODY_CHARS = 0
    }
}

/**
 * The per-step observability sink the agent loop writes into.
 *
 * ## WHY THE LOOP CALLS THIS AND NOT LOGGER
 *
 * A logger stringifies at the call site and gives back a line. This returns a
 * structured record the caller can hold, diff, filter and dump, and it is the
 * difference between a trace that says `"step budget for calendar.create:
 * dropped 2 message(s) (drop oldest observation)"` and one that says
 * `"verdict=TRIM projected=4180 limit=3644 legs=[OBSERVATION applied=true,
 * MEMORY applied=false note=re-rendered-each-step]"`. The second is diffable
 * and the first is not, and only the second answers "did the gate lie".
 *
 * ## ZERO COST WHEN DISABLED
 *
 * [DecisionTrace.off] is a singleton whose methods are empty bodies, so a
 * disabled trace costs one virtual call per instrumented site and allocates
 * nothing. The loop holds it as a non-null field for exactly this reason: a
 * nullable sink would put a null check on every emission site, which is one more
 * thing to forget on the site added next quarter.
 */
interface DecisionTraceSink {
    /** Ring-buffer size and the current RAM cost. For the UI. */
    val stats: TraceStats

    fun run(record: RunRecord)
    fun selection(step: Int, report: SelectionReport)
    fun budget(step: Int, verdict: BudgetVerdict)
    fun prompt(step: Int, capture: PromptCapture)
    fun generation(step: Int, capture: GenerationCapture)
    fun parse(step: Int, capture: ParseCapture)
    fun call(step: Int, capture: CallCapture)
    fun context(step: Int, capture: ContextCapture)
}

/** What the buffer is holding, for the screen and for a bug report. */
data class TraceStats(
    val lines: Int = 0,
    val chars: Int = 0,
    val maxTotalChars: Int = 0,
    val maxBodyChars: Int = 0,
    val enabled: Boolean = true,
    /**
     * Lines evicted because the buffer was full.
     *
     * Reported rather than hidden: a truncated trace that reads as a complete
     * one is a trace that will be believed when it should be doubted.
     */
    val evicted: Int = 0,
) {
    /** The retention policy in one sentence, for the UI to print verbatim. */
    fun describe(): String = buildString {
        if (!enabled) {
            append("Tracing is off. Nothing is recorded.")
            return@buildString
        }
        append("Keeps the newest $maxTotalChars characters of decision records ")
        append("($lines now, $chars used")
        if (evicted > 0) append(", $evicted evicted") else append(", none evicted")
        append("). ")
        if (maxBodyChars <= 0) {
            append("Text is NOT captured — only its length — so a trace can ")
            append("never hold your messages or a page the agent fetched.")
        } else {
            append("Text is captured up to $maxBodyChars characters per field ")
            append("and filtered for secrets before it is written.")
        }
    }
}

/**
 * The shipped sink: a bounded ring buffer over [DecisionLine], plus a live
 * [records] view for the debug screen.
 *
 * ## WHY A RING AND NOT A LIST THAT TRIMS ON READ
 *
 * A trim-on-read list is an unbounded list that is bounded only if somebody
 * remembers to read it. On a device whose primary metric is RAM, the bound has
 * to be enforced at the write, where forgetting is not possible.
 *
 * ## THREAD SAFETY
 *
 * The loop writes from a background coroutine and the UI reads from the main
 * thread, so the buffer is synchronised. The cost is one uncontended monitor per
 * emission, which is microseconds next to an inference call, and the alternative
 * - a mutable list shared across threads - is a `ConcurrentModificationException`
 * on a screen, which is the one failure this feature exists to prevent.
 */
class DecisionTrace(
    policy: TracePolicy = TracePolicy(),
) : DecisionTraceSink {

    /**
     * The active policy, mutable at runtime.
     *
     * WHY IT IS NOT A CONSTRUCTOR-ONLY VAL: the whole point of this feature is
     * to answer a question from a trace collected *after* the fact, and on a
     * phone that usually means going back and re-running with more detail. A
     * capture toggle the user cannot reach is a capture toggle that does not
     * exist, and the honest default - lengths only - is the one most likely to
     * be insufficient exactly when someone is trying to diagnose something.
     *
     * Changing it NEVER grows what is already buffered. A record captured
     * under a smaller body cap stays short, because retroactively widening a
     * buffer to match a newly-loose policy is how a debugging aid becomes the
     * memory incident. The new cap applies from the next emission forward, and
     * the current buffer is re-checked against the total cap immediately.
     */
    @Volatile
    var policy: TracePolicy = policy
        set(value) {
            field = value
            // A tighter total cap must take effect NOW, not at the next
            // emission, or raising then lowering the cap around a quiet run
            // would leave the buffer over the ceiling with nothing to evict it.
            trim()
        }

    private val buffer = ArrayDeque<DecisionLine>()
    private var chars = 0
    private var evicted = 0
    private val sequence = AtomicLong(0)

    private val _records = MutableStateFlow<List<DecisionLine>>(emptyList())

    /**
     * The current contents, newest last.
     *
     * A [StateFlow] rather than a plain list so the debug screen updates
     * without polling, and a copy is published rather than the deque itself so
     * Compose is never handed a structure the loop is still mutating.
     */
    val records: StateFlow<List<DecisionLine>> = _records.asStateFlow()

    override val stats: TraceStats
        get() = synchronized(buffer) {
            TraceStats(
                lines = buffer.size,
                chars = chars,
                maxTotalChars = policy.maxTotalChars,
                maxBodyChars = policy.maxBodyChars,
                enabled = policy.enabled,
                evicted = evicted,
            )
        }

    /** The buffer as JSON Lines, oldest first. This is the shareable artifact. */
    fun toJsonLines(): String = synchronized(buffer) {
        buffer.joinToString("\n") { DecisionTraceJson.encode(it) }
    }

    /** Drops everything. A retention policy with an explicit floor, not a leak. */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            chars = 0
            evicted = 0
        }
        _records.value = emptyList()
    }

    override fun run(record: RunRecord) = emit(EventType.RUN, 0) { it.copy(run = record) }

    override fun selection(step: Int, report: SelectionReport) =
        emit(EventType.SELECTION, step) { it.copy(selection = report) }

    override fun budget(step: Int, verdict: BudgetVerdict) =
        emit(EventType.BUDGET, step) { it.copy(budget = verdict) }

    override fun prompt(step: Int, capture: PromptCapture) =
        emit(EventType.PROMPT, step) { it.copy(prompt = capture) }

    override fun generation(step: Int, capture: GenerationCapture) =
        emit(EventType.GENERATION, step) { it.copy(generation = capture) }

    override fun parse(step: Int, capture: ParseCapture) =
        emit(EventType.PARSE, step) { it.copy(parse = capture) }

    override fun call(step: Int, capture: CallCapture) =
        emit(EventType.CALL, step) { it.copy(call = capture) }

    override fun context(step: Int, capture: ContextCapture) =
        emit(EventType.CONTEXT, step) { it.copy(context = capture) }

    /**
     * One record into the ring.
     *
     * The evict-then-append order is load-bearing for the bound: appending
     * first and evicting afterwards would briefly hold one record over the
     * limit, and for a single 4 MB body that is the whole allocation.
     *
     * A single record LARGER than [TracePolicy.maxTotalChars] still gets in, and
     * then evicts everything else. That is deliberate: dropping the newest
     * record instead would leave a trace that stops exactly when the
     * interesting thing happened. The per-field cap
     * [TracePolicy.maxBodyChars] is what bounds a single record, and this loop
     * bounds the total - two caps for two problems, which is the same reason
     * `ContextBudget` prices a step and `StepEnforcer` prices the run.
     */
    private inline fun emit(type: EventType, step: Int, build: (DecisionLine) -> DecisionLine) {
        if (!policy.enabled) return
        val line = build(DecisionLine(seq = sequence.incrementAndGet().toInt(), step = step, type = type))
        val cost = line.retainedChars()
        val snapshot: List<DecisionLine> = synchronized(buffer) {
            evictDownTo(cost)
            buffer.addLast(line)
            chars += cost
            buffer.toList()
        }
        _records.value = snapshot
    }

    /**
     * Drops oldest-first until [incoming] more characters would fit.
     *
     * MUST be called while holding [buffer]. The condition is
     * `chars + incoming > cap` and not `chars > cap`, so a single record
     * larger than the whole cap evicts everything else and is then still
     * admitted: the alternative is to refuse to record it, which would leave
     * the trace silently missing the single largest and most diagnostic event
     * in the run. The cap is a RAM ceiling, and one record's overshoot is
     * bounded by the per-field body cap rather than by anything unbounded.
     *
     * Evicted records are COUNTED, not just dropped. A trace that quietly
     * discards its own history reads as a complete record of a run, and the
     * difference between "the agent did two steps" and "we only kept the last
     * two" is exactly the kind of thing this trace exists to establish.
     */
    private fun evictDownTo(incoming: Int) {
        while (buffer.isNotEmpty() && chars + incoming > policy.maxTotalChars) {
            chars -= buffer.removeFirst().retainedChars()
            evicted++
        }
    }

    /**
     * Enforces the current total cap against what is ALREADY buffered.
     *
     * Called when the policy is replaced. Without it, raising the cap, then
     * lowering it again around a quiet period, would leave the buffer above the
     * new ceiling with no emission to trigger an eviction, and the process
     * would sit above the memory budget it was told to respect.
     */
    private fun trim() {
        if (!policy.enabled) {
            clear()
            return
        }
        val snapshot: List<DecisionLine> = synchronized(buffer) {
            evictDownTo(0)
            buffer.toList()
        }
        _records.value = snapshot
    }

    companion object {
        /** A sink that records nothing. See [NullDecisionTrace]. */
        val off: DecisionTraceSink = NullDecisionTrace
    }
}

/**
 * The no-op sink.
 *
 * A singleton rather than a nullable field so a disabled trace is an ordinary
 * object and the loop's emission sites carry no null checks - see
 * [DecisionTraceSink] on why that matters.
 */
object NullDecisionTrace : DecisionTraceSink {
    override val stats: TraceStats = TraceStats(enabled = false, maxTotalChars = 0)
    override fun run(record: RunRecord) = Unit
    override fun selection(step: Int, report: SelectionReport) = Unit
    override fun budget(step: Int, verdict: BudgetVerdict) = Unit
    override fun prompt(step: Int, capture: PromptCapture) = Unit
    override fun generation(step: Int, capture: GenerationCapture) = Unit
    override fun parse(step: Int, capture: ParseCapture) = Unit
    override fun call(step: Int, capture: CallCapture) = Unit
    override fun context(step: Int, capture: ContextCapture) = Unit
}
