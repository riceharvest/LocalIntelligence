package dev.localintelligence.core.metrics

import dev.localintelligence.core.agent.StepTrace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One tool call reduced to a value that can be compared with another.
 *
 * WHY args are canonicalised rather than stored verbatim: `{"a":1,"b":2}` and
 * `{"b":2,"a":1}` are the same call, and a duplicate detector that cannot see
 * that is a duplicate detector that reports a retry storm of zero. The
 * canonical form is a key-sorted JSON rendering, nested objects included, so
 * argument order can never manufacture or hide a duplicate.
 */
@Serializable
data class ToolCallKey(
    val name: String,
    val args: String,
) {
    companion object {
        /** Canonicalises [args] into a key-order-independent identity. */
        fun of(name: String, args: JsonObject): ToolCallKey =
            ToolCallKey(name, canonicalJson(args))
    }
}

/**
 * Duration of one phase of one step.
 *
 * [phase] reuses [StepTrace.Kind] rather than inventing a parallel enum: the
 * metrics must not be able to describe a run the trace cannot. A second
 * vocabulary would drift the first time a phase was added to the loop, and the
 * drift would stay invisible until someone compared two files by hand.
 */
@Serializable
data class StepTiming(
    val step: Int,
    val phase: StepTrace.Kind,
    val durationMs: Long,
    val success: Boolean = true,
)

/**
 * Everything one agent run is measured on, and nothing else.
 *
 * This is the single authoritative per-run record for the project. The eval
 * harness's `TaskMetrics` carries the same fields minus timings, and when the
 * two disagree this type is the one CI diffs — because it is the one that
 * survives a commit boundary.
 *
 * WHY `@Serializable` and versioned rather than an in-memory struct: the
 * project's rule is "if a feature does not move a number here, it does not
 * ship". A number nobody can read back from last week's commit is not a number,
 * it is a story. See [MetricsJson] for the schema and its compatibility rules.
 *
 * Every field carries a default, so a document written by an older or newer
 * commit still decodes: an absent field means "not measured", never "corrupt".
 */
@Serializable
data class RunMetrics(
    /** Stable identity of this run, so two run sets can be paired run-for-run. */
    val runId: String = "",
    /** Eval task slug, when the run came from a suite. Free-form by design. */
    val taskId: String = "",
    /** Model id, so a run is still interpretable months later, when "the model" moved. */
    val modelId: String = "",
    /** Primary metric (`docs/evals.md`): did the task actually get done. */
    val success: Boolean = false,
    val steps: Int = 0,
    /** Tool calls that actually executed. */
    val toolCalls: Int = 0,
    /** Calls the validator refused before execution. */
    val invalidToolCalls: Int = 0,
    /**
     * Calls the model asked for more than once, counted over *attempts*.
     *
     * Attempts, not executions, on purpose: a model retrying the same call 13
     * times while the loop detector correctly blocks 12 of them is a 13-call
     * model problem, and counting executions would report 1 — credit the
     * detector for fixing the model, and the model never improves.
     */
    val duplicateCalls: Int = 0,
    val inputTokens: Int = 0,
    /** The secondary metric's denominator: what the model generated, not what it read. */
    val outputTokens: Int = 0,
    val prefillMs: Long = 0,
    /** Raw decode time. [decodeTokensPerSecond] derives from it; it is never stored twice. */
    val decodeMs: Long = 0,
    val totalMs: Long = 0,
    val stepTimings: List<StepTiming> = emptyList(),
) {
    /**
     * Decode throughput, in generated tokens per second.
     *
     * 0.0 when nothing was decoded — not Infinity, not a throw. A run that
     * failed before generating a token has no throughput, and a metric layer
     * that throws on a legitimate outcome turns a bad run into a crashed suite.
     */
    val decodeTokensPerSecond: Double
        get() = if (decodeMs > 0) outputTokens * 1000.0 / decodeMs else 0.0

    /**
     * Secondary metric (`docs/evals.md`): task success per 1,000 generated tokens.
     *
     * WHY 1,000 and not 1: a raw per-token ratio reads 0.0007, and a metric
     * nobody can read at a glance does not survive contact with a dashboard.
     * The scaling is a constant and changes nothing statistically.
     *
     * WHY 0.0 and not Infinity at zero tokens: a failed run that generated
     * nothing would otherwise report infinite efficiency, which is precisely the
     * number that makes a broken release look like a breakthrough.
     */
    val successPerThousandTokens: Double
        get() = if (outputTokens <= 0) 0.0 else (if (success) 1000.0 else 0.0) / outputTokens

    /** Sum of every measured phase. Exceeds [totalMs] only if phases overlap. */
    val measuredMs: Long get() = stepTimings.sumOf { it.durationMs }

    /**
     * A single fixed-format line, for diffing run sets between commits in CI.
     *
     * WHY a second format: pretty JSON is for humans reading an artifact; this is
     * for `git diff` between two commits, where a reordered object key would read
     * as a change. Fixed field order, one line per run, tab-separated.
     */
    fun toStableLine(): String = listOf(
        runId,
        taskId,
        if (success) "1" else "0",
        steps.toString(),
        toolCalls.toString(),
        invalidToolCalls.toString(),
        duplicateCalls.toString(),
        inputTokens.toString(),
        outputTokens.toString(),
        prefillMs.toString(),
        decodeMs.toString(),
        totalMs.toString(),
    ).joinToString("\t")
}

/**
 * A set of runs plus the identity of the set, ready to write to disk.
 *
 * [schemaVersion] is declared first, so it is the first key in the JSON and the
 * first thing a human sees in a diff.
 */
@Serializable
data class RunSet(
    val schemaVersion: Int = MetricsJson.SCHEMA_VERSION,
    /** e.g. a git sha or "baseline-2026-09-25". Free-form, never parsed. */
    val label: String = "",
    val runs: List<RunMetrics> = emptyList(),
) {
    /** True when the document was written by a newer writer than this reader. */
    fun isFromNewerWriter(current: Int = MetricsJson.SCHEMA_VERSION): Boolean =
        schemaVersion > current
}

/**
 * A canonical JSON rendering with recursively sorted keys.
 *
 * Internal: it exists to key [ToolCallKey], not to be a serializer. `Json`
 * cannot sort keys, so a duplicate detector that relied on `toString()` would
 * compare `{"a":1,"b":2}` and `{"b":2,"a":1}` as different calls.
 */
internal fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonObject -> buildString {
        append('{')
        element.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append(JsonPrimitive(entry.key)).append(':').append(canonicalJson(entry.value))
        }
        append('}')
    }

    is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }

    else -> element.toString()
}
