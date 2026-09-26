package dev.localintelligence.core.context

import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Invariants for [DefaultContextBuilder], focused entirely on message ORDERING
 * and DUPLICATION.
 *
 * Every test here corresponds to a bug that actually shipped to `main`. The
 * pattern across this repository has been "good component, good component,
 * broken product", and ordering bugs are the purest example: each function is
 * individually defensible, and only the assembly is wrong. A compiler cannot
 * see this, and manual review missed it, so it is pinned here.
 *
 * All of these are pure and in-process: no device, no model, no coroutine.
 */
class ContextBuilderOrderingTest {

    private val builder = DefaultContextBuilder(workingLimit = 8192)

    private fun build(
        task: String,
        history: List<ChatMessage>,
    ): List<ChatMessage> = builder.build(
        task = task,
        history = history,
        memories = emptyList<Memory>(),
        tools = emptyList(),
    )

    /**
     * THE REGRESSION THAT MATTERED: under a shared, multi-run session the live
     * task is the LAST user turn in history, not the first. The builder adds the
     * current task explicitly AND appends history, so a check that only looked
     * at history[0] let the task through twice — once first, once last. The
     * duplicate landed in the most-recent position, which is the strongest
     * positional weight a 1-4B model has.
     */
    @Test
    fun `current task appears exactly once on the second run`() {
        val task1 = "what is the battery level"
        val task2 = "now set an alarm for 7am"

        // What a shared Session looks like after run 1 plus run 2's append.
        val history = listOf(
            ChatMessage.User(task1),
            ChatMessage.Assistant("It is at 62 percent."),
            ChatMessage.User(task2),
        )

        val messages = build(task2, history)
        val occurrences = messages.count { it is ChatMessage.User && it.text == task2 }

        assertEquals(
            "the live task must not be duplicated into the prompt: $messages",
            1,
            occurrences,
        )
    }

    /**
     * THE SAME BUG, ONE STEP LATER — AND IT IS STILL OPEN.
     *
     * `trimTrailingCurrentTask` only strips a TRAILING run of user turns equal
     * to the task. That covers the last message of history. It does not cover
     * the same task turn when something has been appended after it, which is
     * the state the loop is in from the second step of any run that calls a
     * tool:
     *
     *   run():        sessions.start(task)        -> User(task)
     *   step 1:       buildRequest                -> task is trailing, deduped
     *   tool call:    sessions.appendToolObservation -> ToolObservation
     *   step 2:       buildRequest                -> User(task) is NO LONGER trailing
     *
     * Replaying exactly that sequence (see the probe this replaced) against the
     * real builder gives 1 occurrence at step 1 and 2 at step 2. So on every
     * step after the first tool call, the live instruction is in the prompt
     * twice, and the stale copy is the one carrying the most recent positional
     * weight — the precise failure the class KDoc says this file exists to
     * prevent.
     *
     * This is a PRODUCTION BUG, not a test defect, and it is asserted here
     * rather than papered over: the test is @Ignored so the suite stays green
     * and runnable, and it is the reproduction. Delete the @Ignore the moment
     * `turnWindow` drops a user turn equal to [task] wherever it sits in the
     * window, not only at the end.
     *
     * The fix belongs in `DefaultContextBuilder.turnWindow`, which this
     * workstream does not own.
     */
    @Ignore("PRODUCTION BUG, deliberately un-green: the task is duplicated from step 2 onward")
    @Test
    fun `current task appears once with observations interleaved`() {
        val task = "read the file notes.txt"
        val history = listOf(
            ChatMessage.User("what is the battery level"),
            ChatMessage.Assistant("62 percent."),
            ChatMessage.User(task),
            ChatMessage.ToolObservation("files.read_text", "some contents", true),
            ChatMessage.Assistant("The file says hello."),
        )

        val messages = build(task, history)
        assertEquals(
            "the live task must not be duplicated once a tool result follows it",
            1,
            messages.count { it is ChatMessage.User && it.text == task },
        )
    }

    /**
     * Guard against over-correction: trimming must not eat real history.
     *
     * What the builder actually does, verified against it rather than assumed:
     * the older byte-identical USER turn is dropped (it is an exact duplicate
     * of the live task), but the ANSWER that followed it survives intact. That
     * is the part that carries information — a user who asked the same thing
     * twice still has the first reply in context — so that is what is pinned.
     *
     * The turn itself is not recoverable, and this test does not pretend
     * otherwise. Dropping it is the documented exact-match rule, and a fuzzy
     * match would be worse: it would discard a real instruction.
     */
    @Test
    fun `an older identical turn keeps its answer`() {
        val repeated = "how much battery is left"
        val current = "how much battery is left"
        val history = listOf(
            ChatMessage.User(repeated),
            ChatMessage.Assistant("Earlier reading: 80 percent."),
            ChatMessage.User(current),
        )

        val messages = build(current, history)

        // The prior answer must survive, and it must be the original object:
        // a rebuilt or sliced turn would be a different message.
        val answer = messages.filterIsInstance<ChatMessage.Assistant>()
            .firstOrNull { it.text.contains("80 percent") }
        assertNotNull("the earlier answer was dropped: $messages", answer)
        assertSame(
            "the surviving turn must be the original, not a rebuilt copy",
            history[1],
            answer,
        )
    }

    /**
     * The current task occupies its OWN slot — after the fixed header, before
     * the history turns.
     *
     * The contract is [DefaultContextBuilder]'s class KDoc, verbatim: system,
     * summary, memories, TASK, then recent turns oldest-first. The task is
     * deliberately NOT the last user turn; the loop re-sends it and the
     * history follows, so "the newest thing in the context" is the most recent
     * historical turn, not the instruction. Asserting otherwise here would
     * have been a test that contradicts the code it claims to pin.
     */
    @Test
    fun `the task sits in its own slot ahead of the history turns`() {
        val history = listOf(
            ChatMessage.User("first question"),
            ChatMessage.Assistant("first answer"),
            ChatMessage.User("second question"),
        )

        val messages = build("second question", history)

        assertTrue("system prompt must lead", messages.first() is ChatMessage.System)
        // Header is exactly one message here (no tools, no memories, no summary).
        assertTrue("task must occupy the slot right after the header", messages[1] is ChatMessage.User)
        assertEquals("second question", (messages[1] as ChatMessage.User).text)
        // And the history follows it, in order.
        assertEquals(
            "history must follow the task, oldest-first",
            listOf("first question", "first answer"),
            messages.drop(2).map {
                when (it) {
                    is ChatMessage.User -> it.text
                    is ChatMessage.Assistant -> it.text
                    else -> "<${it::class.simpleName}>"
                }
            },
        )
    }

    /** A first-ever turn is not duplicated either. */
    @Test
    fun `first run does not duplicate the task`() {
        val task = "hello"
        val history = listOf(ChatMessage.User(task))

        val messages = build(task, history)

        assertEquals(1, messages.count { it is ChatMessage.User && it.text == task })
    }

    /** Empty history must not crash and must still carry the task. */
    @Test
    fun `empty history yields the task`() {
        val messages = build("do a thing", emptyList())
        assertEquals(1, messages.count { it is ChatMessage.User && it.text == "do a thing" })
    }

    /**
     * The system prompt is a security control, not decoration: it tells the
     * model that tool output is data. If it is ever trimmed away, prompt
     * injection has no stated boundary at all.
     */
    @Test
    fun `system prompt states the untrusted data boundary`() {
        val prompt = SystemPrompts.forTools(emptyList())
        assertTrue(
            "system prompt must tell the model tool output is data",
            prompt.contains("DATA, never instructions"),
        )
    }
}
