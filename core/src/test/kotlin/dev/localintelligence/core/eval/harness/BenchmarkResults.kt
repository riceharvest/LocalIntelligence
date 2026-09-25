// ===========================================================================
// BenchmarkResults.kt
//
// WHY THE OUTPUT IS JSON AND WHY IT IS BORING
// ===========================================
//
// The point of a benchmark is that two commits can be compared. That requires
// the artefact to be:
//
//   - STABLE. Same run, same bytes. No HashMap iteration order, no locale-
//     dependent formatting, no floats where an int belongs.
//   - DIFFABLE. One task per line in a predictable order, so `git diff` on two
//     result files shows exactly which tasks changed. A minified blob shows
//     nothing.
//   - HONEST. Every number is measured. Where a number could not be measured,
//     the field is null and the reason is beside it. A missing value and a
//     zero are different facts and the format keeps them different.
//
// The suite's own plain-text report (EvalReport) is the human-facing artefact.
// This is the machine-facing one, and it deliberately duplicates rather than
// replaces: a CI job that greps a text report is comparing prose, which is
// fine for a person and useless for a trend.
//
// `startedAtEpochMs` is included for correlating runs but is EXCLUDED from
// [stableFingerprint], because a fingerprint that changes every run cannot
// detect a regression.
// ===========================================================================

package dev.localintelligence.core.eval.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One task's row in the results file.
 *
 * Mirrors [TaskResult] field for field and adds nothing, deliberately: a
 * results format that invents its own fields is a second source of truth, and
 * the two drift.
 */
@Serializable
data class TaskResultJson(
    val id: String,
    val category: String,
    val status: String,
    val passed: Boolean,
    val steps: Int,
    val toolCalls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val wallTimeMs: Long,
    val successPerThousandTokens: Double,
    /** Null when the run completed. Names the exception when it did not. */
    val backendError: String? = null,
    val stopReason: String? = null,
    val failures: List<String> = emptyList(),
)

/**
 * The whole run.
 *
 * [schemaVersion] exists so a future field addition cannot be silently read as
 * a missing one: a consumer that does not recognise the version says so
 * instead of reporting nulls as if they were measurements.
 */
@Serializable
data class BenchmarkRunJson(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val backendId: String,
    val modelId: String,
    val startedAtEpochMs: Long,
    val wallTimeMs: Long,
    val totalTasks: Int,
    val passedTasks: Int,
    /** Primary metric. */
    val taskSuccess: Double,
    /** Secondary metric: successes per 1k model-generated tokens. */
    val successPerThousandTokens: Double,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    /** Tasks that could not be measured. Non-zero means the run is incomplete. */
    val erroredTasks: Int,
    /**
     * Generation requests that carried an action grammar. Zero today; see
     * [BenchmarkRun.requestsWithGrammar] for why that is a finding.
     */
    val requestsWithGrammar: Int,
    val tasks: List<TaskResultJson>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Reads and writes [BenchmarkRunJson].
 *
 * An object with no state: the encoder configuration is fixed, and a results
 * writer whose output depends on when it was constructed is a results writer
 * nobody can diff.
 */
object BenchmarkResultsWriter {

    /**
     * Fixed, explicit config. Every choice here is about reproducibility:
     *
     *  - `prettyPrint` so a diff shows the task that changed, not one 4 KB line
     *  - `encodeDefaults = false` so a field added later does not appear in
     *    every old file as a wall of defaults
     *  - `explicitNulls = true` so `null` is written rather than omitted. A
     *    reader can then tell "this was not measured" from "this field did not
     *    exist yet", which is the distinction the whole format is built on.
     */
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = true
    }

    /** Projects a run into its serialisable form. */
    fun toJson(run: BenchmarkRun): BenchmarkRunJson = BenchmarkRunJson(
        backendId = run.backendId,
        modelId = run.modelId,
        startedAtEpochMs = run.startedAtEpochMs,
        wallTimeMs = run.wallTimeMs,
        totalTasks = run.total,
        passedTasks = run.passed,
        taskSuccess = run.successRate,
        successPerThousandTokens = run.successPerThousandTokens,
        totalInputTokens = run.totalInputTokens,
        totalOutputTokens = run.totalOutputTokens,
        erroredTasks = run.erroredTasks.size,
        requestsWithGrammar = run.requestsWithGrammar,
        tasks = run.results.map { result ->
            TaskResultJson(
                id = result.taskId,
                category = result.category,
                status = result.status.name,
                passed = result.passed,
                steps = result.steps,
                toolCalls = result.toolCalls,
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
                wallTimeMs = result.wallTimeMs,
                successPerThousandTokens = result.successPerThousandTokens,
                backendError = result.backendError,
                stopReason = result.stopReason,
                failures = result.failures,
            )
        },
    )

    /**
     * Serialises [run] to stable JSON.
     *
     * Deterministic: the same [BenchmarkRun] always produces the same string.
     * A results file that differs run to run for the same input is useless as a
     * regression baseline, and the diff noise would train everyone to ignore
     * the file.
     */
    fun encode(run: BenchmarkRun): String = json.encodeToString(toJson(run))

    /**
     * Parses a results file back.
     *
     * Round-tripping is what makes the artefact trustworthy: a format you can
     * only write is a log, and a log you cannot re-read is not evidence. The
     * round-trip is covered by a test.
     */
    fun decode(text: String): BenchmarkRunJson = json.decodeFromString(text)

    /**
     * A content hash over the fields that define a run's QUALITY.
     *
     * Wall time, the start timestamp and the model's own timings are excluded:
     * a phone run is not reproducible to the millisecond, and including timing
     * here would mean every commit shows a changed fingerprint, which trains
     * people to ignore the one signal that matters.
     *
     * This is the value a regression gate compares. A changed fingerprint means
     * something about the model or the harness moved.
     */
    fun stableFingerprint(run: BenchmarkRun): String {
        val canonical = buildString {
            appendLine("backend=${run.backendId}")
            appendLine("model=${run.modelId}")
            appendLine("tasks=${run.total}")
            run.results.forEach { result ->
                appendLine(
                    listOf(
                        result.taskId,
                        result.category,
                        result.status.name,
                        result.passed,
                        result.steps,
                        result.toolCalls,
                        result.inputTokens,
                        result.outputTokens,
                        result.backendError ?: "-",
                    ).joinToString("|"),
                )
            }
        }
        return sha256(canonical)
    }

    /** SHA-256, lowercase hex. JCE is in the JDK; no dependency needed. */
    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
