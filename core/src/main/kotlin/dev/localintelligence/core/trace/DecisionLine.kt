package dev.localintelligence.core.trace

import dev.localintelligence.core.tool.redaction.SecretRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The machine-readable record of what the agent loop actually DID, one line per
 * decision.
 *
 * ## WHY THIS IS NOT [dev.localintelligence.core.agent.StepTrace]
 *
 * [StepTrace] is a display list. It carries a `detail: String` that is prose
 * built for a screen, it is capped at 512 characters, and it is held for the
 * lifetime of one run in the loop's own field. Three of the questions this file
 * exists to answer are literally unanswerable from it:
 *
 *  - **"Why was `calendar.create` not callable this turn?"** The trace says the
 *    model chose `device.battery`. The selector's ranking is not in it, and an
 *    unselected tool is *unspeakable* - it is absent from the GBNF grammar - so
 *    "the model chose not to" and "the model could not" are the same trace today.
 *  - **"Did the budget gate lie toward FITS?"** The trace says a step was
 *    refused, with a sentence. It does not say which priced bucket carried the
 *    tokens, which drop legs fired, or - the defect this repository has actually
 *    shipped - whether a leg the gate *claimed* freed space did not.
 *  - **"Was it a parse failure or a bad choice?"** The raw generation is capped
 *    at 512 characters and the parsed [dev.localintelligence.core.agent.AgentAction]
 *    is not recorded at all, so a model that emitted a nearly-correct tool call
 *    and one that emitted noise are indistinguishable below the cut.
 *
 * Everything here is a value, not a sentence. `git diff` on two dumps is a
 * review, and `jq` is a query language.
 *
 * ## THE SHAPE, AND WHY IT IS ONE FLAT OBJECT
 *
 * A `sealed interface` with a polymorphic serializer would nest every payload
 * and make the line order depend on the type. A flat record with a `type`
 * discriminator and nullable payload fields keeps one line per decision, keeps
 * the key order stable across two runs, and means a reader can filter on
 * `type == "BUDGET"` without knowing the other seven shapes exist.
 *
 * Compatibility follows [dev.localintelligence.core.metrics.MetricsJson]: every
 * field defaults, unknown keys are ignored, and the schema version moves only
 * for a breaking change.
 */
@Serializable
data class DecisionLine(
    /** Schema version. First key in the line, so a diff shows it first. */
    val v: Int = DecisionTraceJson.VERSION,
    /** Monotonic across the whole process, so two runs can be told apart. */
    val seq: Int = 0,
    /** The loop step this decision belongs to. 0 for run-level records. */
    val step: Int = 0,
    val type: EventType = EventType.RUN,
    val run: RunRecord? = null,
    val selection: SelectionReport? = null,
    val budget: BudgetVerdict? = null,
    val prompt: PromptCapture? = null,
    val generation: GenerationCapture? = null,
    val parse: ParseCapture? = null,
    val call: CallCapture? = null,
    val context: ContextCapture? = null,
) {
    /**
     * Characters this line costs to keep, for the ring buffer's RAM budget.
     *
     * [TracedText.chars] is the TRUE length of the source string and is not
     * what is retained; only `text` is. Summing `text.length` is therefore the
     * honest RAM figure, and [LINE_OVERHEAD_CHARS] covers the record's own
     * fixed size, so an all-metadata trace is bounded in BOTH dimensions -
     * bytes and line count - rather than being unbounded in one of them.
     */
    internal fun retainedChars(): Int = LINE_OVERHEAD_CHARS +
        (run?.task?.length ?: 0) +
        (run?.modelId?.length ?: 0) +
        (selection?.selected?.sumOf { it.length } ?: 0) +
        (selection?.unselected?.sumOf { it.name.length + it.reason.length } ?: 0) +
        // Accounted for even though it is empty in normal runs: a record that
        // holds strings the cap did not know about is a cap that can be
        // exceeded, and this is the only place that can go wrong silently.
        (selection?.unmatched?.sumOf { it.length } ?: 0) +
        (selection?.fallbackReason?.length ?: 0) +
        (budget?.dropLegs?.sumOf { it.label.length + it.note.length } ?: 0) +
        (prompt?.messages?.sumOf { it.role.length + it.text.text.length } ?: 0) +
        (prompt?.allowedTools?.sumOf { it.length } ?: 0) +
        (generation?.raw?.text?.length ?: 0) +
        (parse?.reason?.length ?: 0) +
        (parse?.respondText?.text?.length ?: 0) +
        (parse?.args?.text?.length ?: 0) +
        (call?.observation?.text?.length ?: 0) +
        (call?.args?.text?.length ?: 0) +
        (call?.refusal?.length ?: 0) +
        (context?.note?.length ?: 0)
}

/**
 * Per-record fixed cost for the ring buffer's accounting.
 *
 * File scope rather than a companion on [DecisionLine]: a private companion on
 * a `@Serializable` class shadows the plugin-generated `serializer()` companion
 * member for every reference to it in the same file, which breaks
 * [DecisionTraceJson] with a confusing "cannot access companion object" error.
 * A top-level constant has no such interaction.
 */
private const val LINE_OVERHEAD_CHARS = 256

/** What kind of decision a line records. Named, never numeric. */
@Serializable
enum class EventType {
    RUN,
    SELECTION,
    BUDGET,
    PROMPT,
    GENERATION,
    PARSE,
    CALL,
    CONTEXT,
}

/** Run-level identity. Recorded once per run so a dump is self-describing. */
@Serializable
data class RunRecord(
    /** `"start"` or the terminal outcome; the loop's own words, sanitised. */
    val phase: String = "start",
    val task: String = "",
    val modelId: String = "",
    val contextLength: Int = 0,
    val workingTokenLimit: Int = 0,
    val maxSteps: Int = 0,
    val maxVisibleTools: Int = 0,
)

/** A captured string: what survived, how long the original was, and whether
 * the filter touched it.
 *
 * ## WHY `redactions` IS PART OF THE RECORD AND NOT JUST A PROPERTY OF THE TEXT
 *
 * A redacted trace is a trace that no longer says what happened, and this
 * repository has been bitten by exactly that: the policy note on filtering
 * downstream of the model says "a log that misdescribes the run is worse than an
 * unfiltered one, because it is wrong in the direction that looks like safety".
 *
 * So the trace is scrubbed AND says how much it scrubbed. A reader can tell
 * "the tool returned `password = [redacted]`" from "the tool returned something
 * and the filter removed a span before this line was written" - genuinely
 * different facts about a run, and conflating them is how a defect hides inside
 * a debug artifact.
 *
 * The model is unaffected either way: this happens strictly downstream of what
 * the model read, which is the boundary
 * [dev.localintelligence.core.tool.redaction.RedactingToolRegistry] owns.
 */
@Serializable
data class TracedText(
    /** What is retained. Empty when the policy withholds bodies. */
    val text: String = "",
    /** Length of the ORIGINAL string, always recorded even with no body. */
    val chars: Int = 0,
    /** How many spans the filter replaced on the way in. */
    val redactions: Int = 0,
    /**
     * True when the body ALREADY carried a redaction marker when it arrived.
     *
     * Distinct from [redactions], which counts only what THIS pass removed. A
     * tool observation is sanitised by the registry before the loop ever sees
     * it, so [redactions] is legitimately 0 for a body that visibly says
     * `password = [redacted]`. This flag is what stops that from reading as
     * "nothing secret was here".
     */
    val redactedUpstream: Boolean = false,
    /** True when the policy withheld or shortened the body to save RAM. */
    val omitted: Boolean = false,
) {
    companion object {
        val EMPTY = TracedText()

        /**
         * Captures [raw] under [bodyChars], redacting it first.
         *
         * The filter is
         * [dev.localintelligence.core.tool.redaction.SecretRedactor] - the SAME
         * object the tool registry uses, not a second pattern set. It is a
         * documented fixed point (`redact(redact(x)) == redact(x)`), so running
         * it again here is safe, and it is bounded internally at 64K
         * characters, so this cannot become the memory hazard the agent loop
         * runs inside.
         *
         * [bodyChars] of 0 means "record the length, keep nothing". That is the
         * default, and it is what makes always-on tracing free of the large
         * buffers that are the one thing this project cannot afford.
         */
        fun capture(raw: String, bodyChars: Int, redact: Boolean = true): TracedText {
            val filtered = if (redact) SecretRedactor.redact(raw) else null
            val text = filtered?.text ?: raw
            // WHY THE UPSTREAM FLAG IS SEPARATE FROM THE COUNT
            //
            // A tool's observation reaches this function ALREADY redacted, by
            // `RedactingToolRegistry` on the way back to the model. This second
            // pass is a fixed point, so it finds nothing left and reports
            // `redactions = 0` for a body that plainly says `password =
            // [redacted]`. Read alone, that is the worst possible ambiguity in
            // this whole file: it looks like "no secret was present here" when
            // the truth is "a secret WAS present and was removed before the
            // trace could see it".
            //
            // Those need different responses from whoever reads the trace - one
            // is a clean run, the other means the redaction path is live and
            // working - so the marker is detected and reported in its own right.
            val alreadyRedacted = alreadyRedacted(text)
            if (bodyChars <= 0) {
                return TracedText(
                    text = "",
                    chars = text.length,
                    redactions = filtered?.count ?: 0,
                    redactedUpstream = alreadyRedacted,
                    omitted = text.isNotEmpty(),
                )
            }
            val kept = if (text.length <= bodyChars) text else text.take(bodyChars)
            return TracedText(
                text = kept,
                chars = text.length,
                redactions = filtered?.count ?: 0,
                redactedUpstream = alreadyRedacted,
                omitted = text.length > bodyChars,
            )
        }

        /**
         * True when the body already carries a redaction marker on arrival.
         *
         * Deliberately a literal substring test over the exact marker strings
         * [SecretRedactor] emits, for the same reason its own list is a literal
         * rather than a pattern: a looser test would match text the redactor
         * deliberately left alone. A false NEGATIVE here is harmless - it only
         * costs a sentence of explanation - so the test is allowed to be
         * conservative, but a false POSITIVE would claim a credential was
         * filtered from a body that never had one.
         */
        private fun alreadyRedacted(text: String): Boolean {
            if (text.length < 4) return false
            return MARKERS.any { text.contains(it) }
        }

        private val MARKERS = arrayOf(
            "[redacted]", "[jwt redacted]", "[private key redacted]",
            "[api key redacted]", "[bearer token redacted]",
            "[basic token redacted]", "[one-time code redacted]",
        )
    }
}

/**
 * What the selector chose, and - the part that matters - what it did not.
 *
 * An unselected tool is not "less likely", it is **uncallable**: the same list
 * feeds `GrammarBuilder.forActions`, so a name outside it cannot be produced by
 * a grammar-constrained decode. That makes this report the difference between
 * "the model decided against it" and "the model was never offered it", and the
 * second is invisible everywhere else in the codebase.
 */
@Serializable
data class SelectionReport(
    /**
     * Which code path ran. Named rather than inferred, because the shipped
     * selector has three: it scores, it passes everything through untouched when
     * the tool set already fits, and it returns nothing when there are no tools.
     * A trace that reported only the resulting list would render two of those
     * as "the selector chose these", which is a claim it did not make.
     */
    val strategy: String = "unknown",
    val maxTools: Int = 0,
    val availableCount: Int = 0,
    val selected: List<String> = emptyList(),
    val unselected: List<UnselectedTool> = emptyList(),
    /** True when the selector threw and the loop fell back to first-N. */
    val fellBack: Boolean = false,
    /** The throwable's type name, when [fellBack]. Never its message. */
    val fallbackReason: String = "",
    /**
     * Names the selector returned that are not in the registry, so they never
     * reached the grammar.
     *
     * Empty in every normal run, and it is recorded rather than silently
     * dropped because a selector that names a tool which does not exist is a
     * defect, and a trace that reported the selector's own list instead of the
     * resolved one would describe a tool set the model never saw.
     */
    val unmatched: List<String> = emptyList(),
)

@Serializable
data class UnselectedTool(
    val name: String = "",
    /** The selector's score, or [SCORE_UNREPORTED] when it reported none. */
    val score: Int = SCORE_UNREPORTED,
    /** 1-based position in the selector's own ranking; 0 when unranked. */
    val rank: Int = 0,
    /** Why it is absent, in the selector's terms. */
    val reason: String = "",
) {
    companion object {
        /** -1 rather than 0: zero is a real score, and a very common one. */
        const val SCORE_UNREPORTED = -1
    }
}

/**
 * The per-step budget gate's arithmetic, in full.
 *
 * The verdict is the answer; the fields beneath it are why the verdict is
 * trustworthy. A gate that says FITS and cannot show its working is exactly the
 * gate this repository shipped once already, so [dropLegs] carries
 * [DropLeg.applied] - false for a leg the plan asked for and the loop could not
 * actually perform - and [enforcement] says plainly when the gate was not
 * running at all.
 */
@Serializable
data class BudgetVerdict(
    /**
     * `"active"`, or the reason there is nothing to enforce against.
     * A gate that silently did not run reads identically to a gate that ran and
     * said FITS; that is the whole defect.
     */
    val enforcement: String = "active",
    /** `"FITS"`, `"TRIM"`, `"REFUSED"`, or `"NOT_PRICED"`. */
    val verdict: String = "NOT_PRICED",
    /** Priced tokens per [dev.localintelligence.core.model.token.UsageBucket]. */
    val buckets: Map<String, Int> = emptyMap(),
    val currentTokens: Int = 0,
    val addedTokens: Int = 0,
    val projectedTokens: Int = 0,
    val limit: Int = 0,
    val overage: Int = 0,
    val headroom: Int = 0,
    val dropLegs: List<DropLeg> = emptyList(),
    val dropped: Int = 0,
    /** True when the step is over the window and no drop can help. */
    val unfixable: Boolean = false,
    /** `"pre-tool"` or `"post-observation"`. */
    val phase: String = "pre-tool",
)

/** One drop leg: what the plan asked for, and whether it actually happened. */
@Serializable
data class DropLeg(
    val component: String = "",
    val label: String = "",
    val requestedTokens: Int = 0,
    /**
     * False means the gate is about to be credited with a drop that frees
     * nothing in the live window. This is the field that makes a permissive lie
     * visible rather than invisible, and it exists because the loop's own
     * KDoc records reproducing a FITS verdict at 2625 real tokens against a
     * 2406 ceiling, where one phantom MEMORY drop was the whole difference.
     */
    val applied: Boolean = false,
    /** Why it did not, when it did not. */
    val note: String = "",
)

/** The prompt as sent: every message, its role, and its length. */
@Serializable
data class PromptCapture(
    val messages: List<PromptMessage> = emptyList(),
    val totalChars: Int = 0,
    val estimatedTokens: Int = 0,
    val grammarChars: Int = 0,
    /** The names the grammar may choose from - the same list, by construction. */
    val allowedTools: List<String> = emptyList(),
)

@Serializable
data class PromptMessage(
    val role: String = "",
    val text: TracedText = TracedText.EMPTY,
)

/** The model's output, verbatim, BEFORE the parser saw it. */
@Serializable
data class GenerationCapture(
    val raw: TracedText = TracedText.EMPTY,
    val stopReason: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
)

/** What the parser made of the bytes above, or why it could not. */
@Serializable
data class ParseCapture(
    val ok: Boolean = false,
    /** `"RESPOND"`, `"CALL_TOOL"`, or `"MALFORMED"`. */
    val action: String = "",
    val reason: String = "",
    val toolName: String = "",
    val args: TracedText = TracedText.EMPTY,
    val respondText: TracedText = TracedText.EMPTY,
)

/**
 * One tool call, from the model's request to the phone's answer.
 *
 * [dispatched] separates "the model asked and it ran" from "the model asked and
 * the runtime refused", which the display trace renders as the same red row.
 */
@Serializable
data class CallCapture(
    val name: String = "",
    val args: TracedText = TracedText.EMPTY,
    val observation: TracedText = TracedText.EMPTY,
    val success: Boolean = false,
    val dispatched: Boolean = false,
    val durationMs: Long = 0,
    val refusal: String = "",
    /** The tool's declared risk, because the policy is evaluated against it. */
    val risk: String = "",
    /** The runtime's policy verdict, for calls that were gated. */
    val policy: String = "",
    /**
     * `"pre-tool"` when this record is a refusal, `"dispatched"` when the tool
     * actually ran.
     *
     * Named for what it is rather than reusing [BudgetVerdict.phase]: the two
     * record different things about the same step, and a reader filtering on
     * `"pre-tool"` across the whole dump should find both the gate's arithmetic
     * and the call that gate killed.
     */
    val phase: String = "dispatched",
)

/** Context lifecycle: a compaction, or a step-budget trim. */
@Serializable
data class ContextCapture(
    /** `"compaction"` or `"budget-trim"`. */
    val kind: String = "",
    val activeTokens: Int = 0,
    val limit: Int = 0,
    val windowMessagesBefore: Int = 0,
    val windowMessagesAfter: Int = 0,
    val kept: Int = 0,
    val dropped: Int = 0,
    val summaryChars: Int = 0,
    val note: String = "",
)

/**
 * The encoder. One compact object per line, which is what makes a dump
 * diffable line by line rather than as a reformatted document.
 *
 * The rules are [dev.localintelligence.core.metrics.MetricsJson]'s, for the same
 * reason: an artifact nobody can read back from an older commit is not evidence.
 */
object DecisionTraceJson {

    /** Bump only for a breaking change. 1 is the initial schema. */
    const val VERSION = 1

    /** One line per decision, no pretty printing, defaults written out. */
    val lines: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    fun encode(line: DecisionLine): String = lines.encodeToString(DecisionLine.serializer(), line)

    fun decode(text: String): DecisionLine =
        lines.decodeFromString(DecisionLine.serializer(), text)

    fun encodeAll(records: List<DecisionLine>): String =
        records.joinToString("\n") { encode(it) }

    /**
     * Reads the schema version without decoding the rest.
     *
     * Needed for the same reason as [dev.localintelligence.core.metrics.MetricsJson.readSchemaVersion]:
     * asking "can I trust this file" must not require a parse exception to find
     * out.
     */
    fun readSchemaVersion(text: String): Int? = runCatching {
        (lines.parseToJsonElement(text) as? JsonObject)
            ?.get("v")?.let { (it as? JsonPrimitive)?.content }?.toIntOrNull()
    }.getOrNull()
}
