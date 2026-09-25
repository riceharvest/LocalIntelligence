package dev.pidroid.core.context

import dev.pidroid.core.model.ChatMessage
import dev.pidroid.core.model.GenerationRequest
import dev.pidroid.core.model.GenerationResult
import dev.pidroid.core.model.ModelBackend
import dev.pidroid.core.model.ModelCapabilities
import dev.pidroid.core.model.ModelSpec
import dev.pidroid.core.model.NoopModelBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compaction. Two properties matter more than anything else here:
 * the FAILURES slot survives, and the output is byte-identical for identical
 * input. Everything else is bookkeeping.
 */
class ContextCompactorTest {

    private val model = NoopModelBackend()
    private val compactor = ContextCompactor()

    // ---------------------------------------------------------------- helpers

    private fun ok(name: String, observation: String) =
        ChatMessage.ToolObservation(name, observation, success = true)

    private fun failed(name: String, observation: String) =
        ChatMessage.ToolObservation(name, observation, success = false)

    // ------------------------------------------------------- shouldCompact

    @Test
    fun `shouldCompact is false below the threshold and true above`() {
        // 4096 * 0.65 = 2662.4, well under the 6000 working limit, so the
        // model-context term binds here.
        assertFalse(compactor.shouldCompact(activeTokens = 2_600, modelContext = 4_096))
        assertTrue(compactor.shouldCompact(activeTokens = 2_700, modelContext = 4_096))
    }

    /**
     * min(modelContext * 0.65, workingLimit). With a 4K context the first term
     * binds (2662); with a 32K context the second does (6000). Both regimes must
     * hold, or the working limit is decorative.
     */
    @Test
    fun `the min of context times fraction and workingLimit holds in both regimes`() {
        val small = ContextCompactor(workingLimit = 6_000, triggerFraction = 0.65)
        // Regime 1: model context binds.
        assertFalse(small.shouldCompact(activeTokens = 2_662, modelContext = 4_096))
        assertTrue(small.shouldCompact(activeTokens = 2_663, modelContext = 4_096))

        // Regime 2: working limit binds.
        assertFalse(small.shouldCompact(activeTokens = 5_999, modelContext = 32_768))
        assertTrue(small.shouldCompact(activeTokens = 6_001, modelContext = 32_768))
    }

    @Test
    fun `a custom triggerFraction is respected`() {
        val eager = ContextCompactor(workingLimit = 6_000, triggerFraction = 0.5)
        assertFalse(eager.shouldCompact(activeTokens = 1_999, modelContext = 4_096))
        assertTrue(eager.shouldCompact(activeTokens = 2_049, modelContext = 4_096))
    }

    // ------------------------------------------------------------ compaction

    @Test
    fun `failures slot is populated from unsuccessful observations`() = runTest {
        val history = listOf(
            ChatMessage.Assistant("Looking for the file."),
            ok("storage.search", "found 3 files"),
            failed("storage.share", "no app handles mime application/pdf"),
            ChatMessage.Assistant("Trying another approach."),
        )

        val state = compactor.compact(history, summary = null, model = model)

        assertEquals(1, state.failures.size)
        assertTrue(state.failures.single().contains("storage.share FAILED"))
        assertTrue(state.failures.single().contains("no app handles mime"))
        assertTrue("render must keep the labelled failure slot", state.render().contains("Failures:"))
    }

    @Test
    fun `failures survive REPEATED compaction`() = runTest {
        val first = compactor.compact(
            listOf(failed("alarm.set", "permission denied")),
            summary = null,
            model = model,
        )
        // A second compaction over a later window that contains no failure at all.
        val second = compactor.compact(
            listOf(ok("clock.read", "08:45"), ChatMessage.Assistant("Done.")),
            summary = first,
            model = model,
        )

        assertEquals("the earlier failure must be carried forward", 1, second.failures.size)
        assertTrue(second.failures.single().contains("alarm.set FAILED"))
        assertTrue(second.render().contains("Failures:"))
    }

    @Test
    fun `progress and actions are separated into their own slots`() = runTest {
        val history = listOf(
            ChatMessage.User("share the pdf"),
            ChatMessage.Assistant("Searching downloads."),
            ok("storage.search", "3 matches"),
            ChatMessage.Assistant("Sharing the newest one."),
            ok("storage.share", "shared to Drive"),
        )

        val state = compactor.compact(history, summary = null, model = model)

        assertEquals(2, state.progress.size)
        assertEquals(2, state.actionsTaken.size)
        assertTrue(state.actionsTaken.any { it.startsWith("storage.search:") })
        assertTrue(state.actionsTaken.any { it.startsWith("storage.share:") })
        assertTrue(state.failures.isEmpty())
        assertTrue(state.render().contains("Actions already taken:"))
    }

    @Test
    fun `the task comes from the first user message`() = runTest {
        val state = compactor.compact(
            listOf(
                ChatMessage.User("find the pdf i downloaded yesterday"),
                ChatMessage.Assistant("Searching."),
            ),
            summary = null,
            model = model,
        )

        assertEquals("find the pdf i downloaded yesterday", state.task)
    }

    @Test
    fun `an earlier task is not overwritten by later drift`() = runTest {
        val previous = CompactedState(task = "find the pdf i downloaded yesterday")
        val state = compactor.compact(
            listOf(ChatMessage.User("actually just check the battery"), ChatMessage.Assistant("ok")),
            summary = previous,
            model = model,
        )

        assertEquals("find the pdf i downloaded yesterday", state.task)
    }

    @Test
    fun `an absent task is reported honestly rather than invented`() = runTest {
        val state = compactor.compact(listOf(ok("clock.read", "08:45")), summary = null, model = model)

        assertEquals(ContextCompactor.UNKNOWN, state.task)
        assertTrue(state.render().startsWith("Task: "))
    }

    /**
     * Determinism is the whole reason the summariser is a pass-through. Same
     * input, byte-identical output — otherwise the eval suite measures noise.
     */
    @Test
    fun `compaction is deterministic - same input gives byte-identical output`() = runTest {
        val history = listOf(
            ChatMessage.User("set an alarm for 8 30"),
            ChatMessage.Assistant("Checking the clock."),
            ok("clock.read", "08:45"),
            failed("alarm.set", "no permission"),
            ok("clock.read", "08:46"),
            ChatMessage.Assistant("Retrying with a different id."),
        )

        val a = compactor.compact(history, summary = null, model = model)
        val b = compactor.compact(history, summary = null, model = model)

        assertEquals(a, b)
        assertEquals(a.render(), b.render())
        assertEquals(a.render().toByteArray().toList(), b.render().toByteArray().toList())
    }

    @Test
    fun `compaction is deterministic even for a huge history`() = runTest {
        val history = (1..2_000).map { i ->
            if (i % 5 == 0) failed("tool.x", "error $i") else ok("tool.y", "result $i")
        }

        val a = compactor.compact(history, summary = null, model = model)
        val b = compactor.compact(history.reversed(), summary = null, model = model).let {
            // Order differs, so compare the invariant that must hold regardless.
            compactor.compact(history, summary = null, model = model)
        }

        assertEquals(a.render(), b.render())
    }

    /**
     * A compacted state must be bounded no matter how long the session runs.
     * 10,000 messages must not produce a 10,000-entry slot.
     */
    @Test
    fun `the result stays bounded for a 10000 message history`() = runTest {
        val history = (1..10_000).map { i ->
            when (i % 3) {
                0 -> failed("tool.f", "failure number $i with a reasonably wordy observation")
                1 -> ok("tool.s", "success number $i with a reasonably wordy observation")
                else -> ChatMessage.Assistant("assistant step $i doing some work")
            }
        }

        val state = compactor.compact(history, summary = null, model = model)

        assertTrue("progress: ${state.progress.size}", state.progress.size <= ContextCompactor.MAX_SLOT_ENTRIES)
        assertTrue("actions: ${state.actionsTaken.size}", state.actionsTaken.size <= ContextCompactor.MAX_SLOT_ENTRIES)
        assertTrue("failures: ${state.failures.size}", state.failures.size <= ContextCompactor.MAX_SLOT_ENTRIES)

        val chars = state.render().length
        assertTrue("rendered state was $chars chars", chars <= 5 * ContextCompactor.MAX_SLOT_ENTRIES * ContextCompactor.MAX_ENTRY_CHARS + 200)
        assertTrue("rendered state was $chars chars", TokenEstimate.tokens(state.render()) < 1_900)
    }

    @Test
    fun `a repeated identical failure occupies one line, not many`() = runTest {
        val history = (1..50).map { failed("alarm.set", "permission denied") }

        val state = compactor.compact(history, summary = null, model = model)

        assertEquals(1, state.failures.size)
    }

    @Test
    fun `an enormous observation cannot own a slot`() = runTest {
        val state = compactor.compact(
            listOf(failed("tool.x", "y".repeat(100_000))),
            summary = null,
            model = model,
        )

        val line = state.failures.single()
        assertTrue("line was ${line.length} chars", line.length <= ContextCompactor.MAX_ENTRY_CHARS + 1)
    }

    @Test
    fun `a multi line observation collapses to one line`() = runTest {
        val state = compactor.compact(
            listOf(ok("calendar.search", "line one\nline two\n\tline three")),
            summary = null,
            model = model,
        )

        val line = state.actionsTaken.single()
        assertFalse("line must not contain a newline", line.contains("\n"))
        assertEquals("calendar.search: line one line two line three", line)
    }

    /**
     * Compaction changes what goes into the NEXT INFERENCE CONTEXT. It must not
     * touch stored history — the transcript is the only durable record, and a
     * device has no backup.
     */
    @Test
    fun `no original message is lost - compaction does not touch stored history`() = runTest {
        val stored: MutableList<ChatMessage> = mutableListOf(
            ChatMessage.User("share the pdf"),
            ChatMessage.Assistant("Searching."),
            ok("storage.search", "3 matches"),
            failed("storage.share", "no handler"),
        )
        val before = stored.toList()

        val state = compactor.compact(stored, summary = null, model = model)

        assertEquals("message count changed", before.size, stored.size)
        before.forEachIndexed { index, message ->
            assertSame("message $index was replaced", message, stored[index])
        }
        assertEquals("summarisation must not rewrite observations", before, stored)
        assertNotNull(state)
    }

    @Test
    fun `compacting an empty history yields a valid empty state`() = runTest {
        val state = compactor.compact(emptyList(), summary = null, model = model)

        assertEquals(ContextCompactor.UNKNOWN, state.task)
        assertTrue(state.progress.isEmpty())
        assertTrue(state.failures.isEmpty())
        assertNotNull(state.render())
    }

    @Test
    fun `the shipped summariser spends no inference`() = runTest {
        // A backend that fails loudly if it is ever actually called. Implements
        // ModelBackend directly because NoopModelBackend is final.
        val exploding = object : ModelBackend {
            override val id = "exploding"
            override val capabilities = ModelCapabilities.UNKNOWN
            override suspend fun load(model: ModelSpec) = Unit
            override suspend fun generate(request: GenerationRequest): GenerationResult =
                throw AssertionError("compaction must not call the model")
            override suspend fun unload() = Unit
            override fun countTokens(text: String): Int = text.length / 4
            override fun cancel() = Unit
        }

        val state = compactor.compact(
            listOf(ChatMessage.Assistant("Working."), failed("tool.z", "nope")),
            summary = null,
            model = exploding,
        )

        assertEquals(1, state.failures.size)
    }

    @Test
    fun `summaryMessage renders a system message the builder recognises`() {
        val state = CompactedState(task = "t", failures = listOf("alarm.set FAILED: denied"))
        val message = compactor.summaryMessage(state)

        assertTrue(message is ChatMessage.System)
        assertTrue(message.text.startsWith(SUMMARY_PREFIX))
        assertTrue(message.text.contains("Failures:"))

        // And the builder puts it in slot 2, not in the turn window.
        val out = DefaultContextBuilder().build("t", listOf(message), emptyList(), emptyList())
        assertSame(message, out[1])
    }
}
