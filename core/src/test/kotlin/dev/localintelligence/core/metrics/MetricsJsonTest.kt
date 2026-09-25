package dev.localintelligence.core.metrics

import dev.localintelligence.core.agent.StepTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The serialization contract.
 *
 * These tests are the reason the JSON is a *format* rather than an implementation
 * detail: an artifact written on one commit has to be readable on the next one,
 * and a field added by a future writer must not break a current reader. If a
 * future change makes one of these fail, it broke the contract, not the test.
 */
class MetricsJsonTest {

    private val sample = RunSet(
        label = "baseline-fa7a578",
        runs = listOf(
            RunMetrics(
                runId = "single/battery-level",
                taskId = "single/battery-level",
                modelId = "qwen2.5-3b-q4",
                success = true,
                steps = 2,
                toolCalls = 1,
                inputTokens = 120,
                outputTokens = 38,
                prefillMs = 90,
                decodeMs = 380,
                totalMs = 1_200,
                stepTimings = listOf(
                    StepTiming(1, StepTrace.Kind.GENERATION, 300),
                    StepTiming(1, StepTrace.Kind.TOOL_CALL, 40),
                ),
            ),
            RunMetrics(runId = "failure/permission-denied", success = false, steps = 14, outputTokens = 380),
        ),
    )

    // -------------------------------------------------------------- round trip

    @Test
    fun `a run set survives a round trip`() {
        val text = MetricsJson.encode(sample)
        assertEquals(sample, MetricsJson.decodeRunSet(text))
    }

    @Test
    fun `re-encoding a decoded document is byte-identical`() {
        // This is what makes a CI diff show real changes instead of encoder
        // noise: two commits must produce the same bytes for the same data.
        val text = MetricsJson.encode(sample)
        assertEquals(text, MetricsJson.encode(MetricsJson.decodeRunSet(text)))
    }

    @Test
    fun `the version field is written, read back, and is the first key`() {
        val text = MetricsJson.encode(sample)
        assertEquals(MetricsJson.SCHEMA_VERSION, MetricsJson.readSchemaVersion(text))
        assertEquals(MetricsJson.SCHEMA_VERSION, MetricsJson.decodeRunSet(text).schemaVersion)
        val firstKey = Json.parseToJsonElement(text).jsonObject.keys.first()
        assertEquals("schemaVersion", firstKey)
    }

    @Test
    fun `an aggregate and a comparison round trip too`() {
        val aggregate = RunAggregator.aggregate(sample.runs, label = "baseline")
        assertEquals(aggregate, MetricsJson.decodeAggregate(MetricsJson.encode(aggregate)))

        val comparison = RunComparer().compare(sample.runs, sample.runs)
        assertEquals(comparison, MetricsJson.decodeComparison(MetricsJson.encode(comparison)))
    }

    @Test
    fun `every measured field survives the round trip`() {
        val decoded = MetricsJson.decodeRunSet(MetricsJson.encode(sample))
        val run = decoded.runs.first()
        assertEquals(38, run.outputTokens)
        assertEquals(120, run.inputTokens)
        assertEquals(90L, run.prefillMs)
        assertEquals(380L, run.decodeMs)
        assertEquals(1_200L, run.totalMs)
        assertEquals("qwen2.5-3b-q4", run.modelId)
        assertEquals(2, run.stepTimings.size)
        assertEquals(StepTrace.Kind.TOOL_CALL, run.stepTimings[1].phase)
        assertEquals(40L, run.stepTimings[1].durationMs)
    }

    // --------------------------------------------------------- forward compat

    @Test
    fun `an unknown field from a newer writer is ignored, not rejected`() {
        val run = (Json.parseToJsonElement(MetricsJson.encode(RunSet(runs = listOf(sample.runs.first()))))
            .jsonObject.getValue("runs") as JsonArray)
            .single().jsonObject.toMutableMap().apply {
                put("speculativeDecoding", JsonPrimitive(true))
                put("futureNested", buildJsonObject { put("x", 1) })
            }
        val fromTheFuture = buildJsonObject {
            put("schemaVersion", MetricsJson.SCHEMA_VERSION)
            put("label", sample.label)
            put("quantumThroughput", buildJsonObject { put("nested", "unknown") })
            put("runs", JsonArray(listOf(JsonObject(run))))
        }

        val decoded = MetricsJson.decodeRunSet(fromTheFuture.toString())
        assertEquals(sample.label, decoded.label)
        assertEquals(1, decoded.runs.size)
        assertEquals(sample.runs.first().outputTokens, decoded.runs.first().outputTokens)
        assertEquals(sample.runs.first().stepTimings, decoded.runs.first().stepTimings)
    }

    @Test
    fun `a document from a newer schema version still decodes and says so`() {
        val fromTheFuture = """{"schemaVersion":99,"label":"next","runs":[]}"""
        val decoded = MetricsJson.decodeRunSet(fromTheFuture)
        assertEquals(99, decoded.schemaVersion)
        assertTrue(decoded.isFromNewerWriter())
        assertTrue(!decoded.isFromNewerWriter(current = 99))
        assertTrue(MetricsJson.compatibilityWarning(fromTheFuture)!!.contains("v99"))
    }

    @Test
    fun `a run missing every optional field decodes to defaults, not an error`() {
        val minimal = """{"schemaVersion":1,"label":"v0","runs":[{"runId":"old"}]}"""
        val decoded = MetricsJson.decodeRunSet(minimal)
        assertEquals("v0", decoded.label)
        assertEquals(1, decoded.runs.size)
        assertEquals("old", decoded.runs.first().runId)
        assertEquals(0, decoded.runs.first().steps)
        assertEquals(false, decoded.runs.first().success)
        assertEquals(0.0, decoded.runs.first().successPerThousandTokens, 0.0)
    }

    @Test
    fun `a document with no version at all is reported rather than guessed at`() {
        val warning = MetricsJson.compatibilityWarning("""{"label":"ancient"}""")
        assertNotNull(warning)
        assertTrue(warning!!, warning.contains("no schemaVersion"))
        assertNull(MetricsJson.compatibilityWarning(MetricsJson.encode(sample)))
    }

    @Test
    fun `readSchemaVersion returns null for something that is not a metrics document`() {
        assertNull(MetricsJson.readSchemaVersion("not json at all"))
        assertNull(MetricsJson.readSchemaVersion("""{"label":"no version here"}"""))
    }

    @Test
    fun `a measured zero is written out rather than dropped as a default`() {
        // encodeDefaults = true is what makes "measured zero" and "never
        // measured" different bytes. Without it these keys would vanish.
        val text = MetricsJson.encode(RunSet(runs = listOf(RunMetrics(invalidToolCalls = 0, duplicateCalls = 0))))
        assertTrue(text, text.contains("invalidToolCalls"))
        assertTrue(text, text.contains("duplicateCalls"))
    }

    // ---------------------------------------------------------------- helpers

    @Test
    fun `the stable diff is one tab-separated line per run, in order`() {
        val lines = MetricsJson.stableDiff(sample).lines().filter { it.isNotBlank() }
        assertEquals(4, lines.size) // two header lines plus two runs
        assertTrue(lines[0].startsWith("# schemaVersion"))
        assertEquals(sample.runs[0].toStableLine(), lines[2])
        assertEquals(sample.runs[1].toStableLine(), lines[3])
        assertEquals(12, lines[2].split('\t').size)
    }

    @Test
    fun `a run keeps its stable line across a JSON round trip`() {
        val decoded = MetricsJson.decodeRunSet(MetricsJson.encode(sample))
        assertEquals(sample.runs[0].toStableLine(), decoded.runs[0].toStableLine())
    }
}
