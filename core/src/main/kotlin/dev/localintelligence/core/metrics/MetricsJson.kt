package dev.localintelligence.core.metrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one encoder/decoder for every metrics document.
 *
 * WHY the format is a contract and not an implementation detail: `docs/evals.md`
 * says a feature that does not move a number does not ship. A number that cannot
 * be read back from an older commit, compared across commits, and diffed in CI
 * does not count. So the JSON below is versioned, additive-only, and pinned by
 * tests.
 *
 * The compatibility rules, which every future change must keep:
 *  - **Add fields, never remove or rename.** A removed field loses history that
 *    no re-run can recreate.
 *  - **Every field keeps its default.** An absent field decodes to the default
 *    ("not measured"), so an old reader never chokes on a new document.
 *  - **`schemaVersion` increments only for a breaking change**, and a reader
 *    that sees a higher version still decodes — [RunSet.isFromNewerWriter] lets
 *    a pipeline log that it did.
 *  - **Unknown keys are ignored**, not rejected. Forward compatibility is the
 *    whole point: commit A's artifact must be readable by commit B's CI.
 *
 * `encodeDefaults = true` is the one non-obvious setting. A field that equals its
 * default is written out anyway, because a run that recorded 0 invalid tool calls
 * is a measurement, and omitting it would make "measured zero" and "never
 * measured" the same bytes — the exact ambiguity this package exists to remove.
 */
object MetricsJson {

    /** Bump only for a breaking change. 1 is the initial schema. */
    const val SCHEMA_VERSION = 1

    val format: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        // Forward compatibility: an unknown key is a newer writer, not an error.
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** Compact single-line form, for artifacts that are diffed line by line. */
    val compact: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    fun encode(runSet: RunSet): String = format.encodeToString(RunSet.serializer(), runSet)

    fun decodeRunSet(text: String): RunSet = format.decodeFromString(RunSet.serializer(), text)

    fun encode(aggregate: RunAggregate): String =
        format.encodeToString(RunAggregate.serializer(), aggregate)

    fun decodeAggregate(text: String): RunAggregate =
        format.decodeFromString(RunAggregate.serializer(), text)

    fun encode(comparison: RunComparison): String =
        format.encodeToString(RunComparison.serializer(), comparison)

    fun decodeComparison(text: String): RunComparison =
        format.decodeFromString(RunComparison.serializer(), text)

    /**
     * Reads the schema version out of a document without decoding the rest.
     *
     * WHY: a CI job that has to decode a run set from a branch it no longer
     * shares code with needs to ask "can I even trust this?" before it parses
     * thousands of records, and it must be able to ask without catching a parse
     * exception to find out.
     */
    fun readSchemaVersion(text: String): Int? = runCatching {
        format.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int
    }.getOrNull()

    /**
     * Version check for a pipeline boundary. Returns a warning string, or null
     * when the document is safe to read.
     *
     * Reading a *newer* schema is allowed and reported, never blocked: refusing
     * to read a document because it is newer would make a metrics file useless
     * the moment anyone added a field. Reading a *much* older one is also
     * allowed, because a v0 document with no field means "not measured".
     */
    fun compatibilityWarning(text: String): String? {
        val version = readSchemaVersion(text) ?: return "document has no schemaVersion; treating as v1"
        return when {
            version > SCHEMA_VERSION ->
                "document is schema v$version, this reader is v$SCHEMA_VERSION; " +
                    "unknown fields were ignored"
            version < SCHEMA_VERSION ->
                "document is schema v$version, this reader is v$SCHEMA_VERSION; " +
                    "fields added after v$version decode to their defaults"
            else -> null
        }
    }

    /** Compact tab-separated lines, one per run: the format `git diff` should see. */
    fun stableDiff(runSet: RunSet): String = buildString {
        appendLine("# schemaVersion\t${runSet.schemaVersion}\tlabel\t${runSet.label}")
        appendLine(
            "# runId\ttaskId\tsuccess\tsteps\ttoolCalls\tinvalidToolCalls\t" +
                "duplicateCalls\tinputTokens\toutputTokens\tprefillMs\tdecodeMs\ttotalMs",
        )
        runSet.runs.forEach { appendLine(it.toStableLine()) }
    }
}
