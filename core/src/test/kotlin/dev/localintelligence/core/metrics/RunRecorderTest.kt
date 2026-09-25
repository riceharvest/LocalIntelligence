package dev.localintelligence.core.metrics

import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.StopReason
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The instrumented path. These tests are the reason a timing assertion in this
 * project can be exact: the clock is injected, so a run's duration is whatever
 * the test says it is, and a regression in the recording logic shows up as a
 * wrong number rather than as a flaky tolerance.
 */
class RunRecorderTest {

    private fun generation(
        prompt: Int = 0,
        completion: Int = 0,
        prefill: Long = 0,
        decode: Long = 0,
    ) = GenerationResult(
        text = "x",
        promptTokens = prompt,
        completionTokens = completion,
        stopReason = StopReason.COMPLETED,
        prefillMs = prefill,
        decodeMs = decode,
    )

    // ------------------------------------------------------------ exact timing

    @Test
    fun `a run records the exact durations the fake clock advanced`() {
        val time = FakeTimeSource()
        val recorder = RunRecorder(runId = "r1", taskId = "single/battery", modelId = "fake-3b", time = time)

        recorder.beginStep(1)
        time.advanceMs(120)
        recorder.endPhase(StepTrace.Kind.GENERATION)
        time.advanceMs(30)
        recorder.endPhase(StepTrace.Kind.TOOL_CALL)
        time.advanceMs(8)
        recorder.endPhase(StepTrace.Kind.OBSERVATION)

        recorder.beginStep(2)
        time.advanceMs(50)
        recorder.endPhase(StepTrace.Kind.GENERATION)

        val run = recorder.finish(success = true)

        assertEquals(208L, run.totalMs)
        assertEquals(2, run.steps)
        assertEquals(4, run.stepTimings.size)
        assertEquals(listOf(120L, 30L, 8L, 50L), run.stepTimings.map { it.durationMs })
        assertEquals(208L, run.measuredMs)
    }

    @Test
    fun `a step timing is attributed to the step it happened in`() {
        val time = FakeTimeSource()
        val recorder = RunRecorder(time = time)
        recorder.beginStep(1)
        time.advanceMs(10)
        recorder.endPhase(StepTrace.Kind.GENERATION)
        recorder.beginStep(2)
        time.advanceMs(20)
        recorder.endPhase(StepTrace.Kind.GENERATION)

        val run = recorder.finish(success = false)
        assertEquals(listOf(1, 2), run.stepTimings.map { it.step })
        assertFalse(run.success)
    }

    @Test
    fun `the phase helper times a block it is given`() {
        val time = FakeTimeSource()
        val recorder = RunRecorder(time = time)
        recorder.beginStep(1)
        val result = recorder.phase(StepTrace.Kind.COMPACTION) {
            time.advanceMs(7)
            "compacted"
        }
        assertEquals("compacted", result)
        assertEquals(7L, recorder.finish(success = true).stepTimings.single().durationMs)
    }

    @Test
    fun `a failed phase is recorded as a failure`() {
        val time = FakeTimeSource()
        val recorder = RunRecorder(time = time)
        recorder.beginStep(1)
        time.advanceMs(3)
        recorder.endPhase(StepTrace.Kind.MALFORMED, success = false)
        val timing = recorder.finish(success = false).stepTimings.single()
        assertEquals(StepTrace.Kind.MALFORMED, timing.phase)
        assertFalse(timing.success)
    }

    // ----------------------------------------------------------------- tokens

    @Test
    fun `generation numbers come from the backend and are folded across steps`() {
        val time = FakeTimeSource()
        val recorder = RunRecorder(time = time)
        recorder.recordGeneration(generation(prompt = 120, completion = 30, prefill = 90, decode = 300))
        recorder.recordGeneration(generation(prompt = 200, completion = 45, prefill = 150, decode = 900))

        val run = recorder.finish(success = true)
        assertEquals(320, run.inputTokens)
        assertEquals(75, run.outputTokens)
        assertEquals(240L, run.prefillMs)
        assertEquals(1200L, run.decodeMs)
        // 75 tokens in 1.2 s.
        assertEquals(62.5, run.decodeTokensPerSecond, 1e-9)
    }

    // -------------------------------------------------------------- duplicates

    @Test
    fun `duplicates are counted over attempts, with argument order normalised`() {
        val recorder = RunRecorder()
        val forward = buildJsonObject { put("x", 1); put("y", 2) }
        val reversed = buildJsonObject { put("y", 2); put("x", 1) }
        val different = buildJsonObject { put("x", 9) }

        assertFalse(recorder.recordToolCall("calendar.search", forward, executed = true))
        // Same call, arguments in a different order: still the same call.
        assertTrue(recorder.recordToolCall("calendar.search", reversed, executed = true))
        assertFalse(recorder.recordToolCall("calendar.search", different, executed = false))

        val run = recorder.finish(success = false)
        assertEquals(1, run.duplicateCalls)
        assertEquals(2, run.toolCalls)
        assertEquals(1, run.invalidToolCalls)
    }

    @Test
    fun `nested argument objects are canonicalised too`() {
        val recorder = RunRecorder()
        val inner1 = buildJsonObject { put("b", 2); put("a", 1) }
        val inner2 = buildJsonObject { put("a", 1); put("b", 2) }
        assertFalse(recorder.recordToolCall("t", buildJsonObject { put("args", inner1) }, executed = true))
        assertTrue(recorder.recordToolCall("t", buildJsonObject { put("args", inner2) }, executed = true))
        assertEquals(1, recorder.finish(success = false).duplicateCalls)
    }

    @Test
    fun `a retry storm is thirteen attempts and one execution`() {
        val recorder = RunRecorder()
        val args = buildJsonObject { put("q", "meeting") }
        var duplicates = 0
        repeat(13) { index ->
            if (recorder.recordToolCall("calendar.search", args, executed = index == 0)) duplicates++
        }
        val run = recorder.finish(success = false)
        assertEquals(12, duplicates)
        assertEquals(12, run.duplicateCalls)
        assertEquals(1, run.toolCalls)
    }

    @Test
    fun `the attempted call log is returned in order for diagnostics`() {
        val recorder = RunRecorder()
        recorder.recordToolCall("a", buildJsonObject { put("k", 1) }, executed = true)
        recorder.recordToolCall("b", buildJsonObject { put("k", 2) }, executed = false)
        assertEquals(listOf("a", "b"), recorder.attemptedCalls().map { it.name })
        assertEquals("{\"k\":1}", recorder.attemptedCalls().first().args)
    }

    // ------------------------------------------------------- zero-token safety

    @Test
    fun `a run that generated no tokens reports zero, not infinity`() {
        val run = RunRecorder(time = FakeTimeSource()).finish(success = true)
        assertEquals(0, run.outputTokens)
        assertEquals(0.0, run.successPerThousandTokens, 0.0)
        assertEquals(0.0, run.decodeTokensPerSecond, 0.0)
        assertTrue(run.successPerThousandTokens.isFinite())
    }

    @Test
    fun `a run with zero decode time has no throughput to report`() {
        val run = RunMetrics(success = true, outputTokens = 50, decodeMs = 0)
        assertEquals(0.0, run.decodeTokensPerSecond, 0.0)
        // 1000 / 50 generated tokens. The metric still works with no decode time.
        assertEquals(20.0, run.successPerThousandTokens, 1e-9)
    }
}
