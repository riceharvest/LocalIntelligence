package dev.localintelligence.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The observation rule for permission denials.
 *
 * docs/tool-contract.md: `observation` is the only thing fed back to the LLM.
 * docs/architecture.md §18: it must stay under budget. A denial is the failure a
 * confused model re-reads on every following turn, so a leaked class name here
 * costs tokens on every step of a stuck conversation.
 */
class PermissionObservationTest {

    @Test
    fun `every state produces a non-empty human-readable sentence`() {
        for (state in PermissionState.entries) {
            val text = PermissionObservation.forState(state, "your calendar")
            assertTrue("$state produced nothing", text.isNotBlank())
            assertTrue("$state produced whitespace only", text.isNotBlank())
            assertEquals("$state lost its trailing space", text, text.trim())
        }
    }

    @Test
    fun `every blocking state tells the model what it cannot do and why`() {
        // GRANTED is excluded on purpose: it is the one state that is not a
        // denial, and its sentence says so. The other three all have to tell
        // the model the capability is unusable and why.
        for (state in PermissionState.entries.filter { it.blocksExecution }) {
            val text = PermissionObservation.forState(state, "your calendar").lowercase()
            assertTrue(
                "$state does not say the capability is unusable",
                text.contains("cannot"),
            )
        }
    }

    @Test
    fun `the granted sentence is not a denial`() {
        // Guards against an edit that makes the granted hint talk about
        // permissions the user must fix.
        val text = PermissionObservation.forState(PermissionState.GRANTED, "your calendar")
            .lowercase()
        assertFalse(text.contains("cannot"))
        assertFalse(text.contains("do not retry"))
    }

    @Test
    fun `soft denial invites one more ask, permanent denial forbids retrying`() {
        val soft = PermissionObservation.forState(PermissionState.DENIED, "your calendar")
            .lowercase()
        assertTrue("a soft denial must invite a retry", soft.contains("try again"))

        val permanent =
            PermissionObservation.forState(PermissionState.DENIED_PERMANENTLY, "your calendar")
                .lowercase()
        assertTrue(
            "a permanent denial must tell the model to stop retrying",
            permanent.contains("do not retry") || permanent.contains("cannot ask again"),
        )
    }

    @Test
    fun `observation is under the stated length budget for every state`() {
        for (state in PermissionState.entries) {
            val text = PermissionObservation.forState(state, "your calendar")
            assertTrue(
                "$state observation was ${text.length} chars, over the " +
                    "${PermissionObservation.BUDGET_CHARS} budget",
                text.length <= PermissionObservation.BUDGET_CHARS,
            )
        }
    }

    @Test
    fun `observation is far under the general tool observation budget`() {
        // Stated so the relationship to the frozen 2048 budget is explicit and
        // a future edit cannot quietly inflate this into a paragraph.
        assertTrue(PermissionObservation.BUDGET_CHARS < 512)
        for (state in PermissionState.entries) {
            assertTrue(
                PermissionObservation.forState(state, "x").length <= 320,
            )
        }
    }

    @Test
    fun `sanitiser removes an exception class name`() {
        val raw = "java.lang.SecurityException: Permission Denial: " +
            "opening provider requires android.permission.READ_CALENDAR"
        val clean = PermissionObservation.sanitize(raw)
        assertFalse(
            "leaked a qualified class name: $clean",
            clean.contains("java.lang.SecurityException"),
        )
        assertFalse(clean.contains("android.permission.READ_CALENDAR"))
        assertTrue(
            "sanitising must not empty the sentence",
            clean.isNotBlank(),
        )
    }

    @Test
    fun `sanitiser removes a bare exception type name`() {
        val clean = PermissionObservation.sanitize("SecurityException thrown by the provider")
        assertFalse(clean.contains("SecurityException"))
    }

    @Test
    fun `sanitiser removes a stack trace`() {
        val raw = """
            Permission denied.
            ${'\t'}at com.example.Thing.run(Thing.kt:42)
            ${'\t'}at android.os.Handler.dispatchMessage(Handler.java:106)
        """.trimIndent()
        val clean = PermissionObservation.sanitize(raw)
        assertFalse("leaked a stack frame: $clean", clean.contains("at "))
        assertFalse(clean.contains("Thing.kt:42"))
    }

    @Test
    fun `sanitiser removes raw platform error codes`() {
        val clean = PermissionObservation.sanitize("request failed with code 403 -13 0xDEAD")
        assertFalse("leaked a decimal code: $clean", clean.contains("403"))
        assertFalse("leaked a negative code: $clean", clean.contains("-13"))
        assertFalse("leaked a hex code: $clean", clean.contains("0xDEAD"))
    }

    @Test
    fun `sanitiser keeps ordinary small numbers`() {
        // Over-eager stripping would turn "3 events" into "events" and quietly
        // change the meaning of an observation.
        val clean = PermissionObservation.sanitize("Found 3 events on 12 March")
        assertTrue("lost an ordinary count: $clean", clean.contains("3"))
        assertTrue(clean.contains("12"))
    }

    @Test
    fun `the real android denial message survives sanitising without leaking`() {
        // The literal message a real device produces. If this one leaks, the
        // sanitiser is not doing its job.
        val real = "java.lang.SecurityException: Permission Denial: querying " +
            "content://com.android.calendar/raw/events requires " +
            "android.permission.READ_CALENDAR or android.permission.WRITE_CALENDAR"
        val clean = PermissionObservation.sanitize(real)
        assertTrue(
            "leaked: $clean",
            PermissionObservation.containsNoPlatformNoise(clean),
        )
        assertFalse(clean.contains("SecurityException"))
        assertFalse(clean.contains("content://"))
        assertFalse(clean.contains("READ_CALENDAR"))
    }

    @Test
    fun `generated observations are themselves free of platform noise`() {
        for (state in PermissionState.entries) {
            val text = PermissionObservation.forState(state, "your calendar")
            assertTrue(
                "$state observation leaked platform noise: $text",
                PermissionObservation.containsNoPlatformNoise(text),
            )
        }
    }

    @Test
    fun `clamp cuts on a word boundary and stays within budget`() {
        val long = "word ".repeat(500)
        val clamped = PermissionObservation.clamp(long)
        assertTrue(clamped.length <= PermissionObservation.BUDGET_CHARS)
        assertFalse("cut mid-word leaves a broken token", clamped.contains("wor…"))
    }

    @Test
    fun `clamp is a no-op below budget`() {
        assertEquals("short enough", "short enough", PermissionObservation.clamp("short enough"))
    }

    @Test
    fun `sanitiser is total and never throws`() {
        val nasty = listOf(
            "", "   ", "\n\n", "@@@ ###", "\u0000\u0001", "a".repeat(5000),
            "at ", "at ()", "1 2 3 4 5 6 7 8 9 100 1000",
        )
        for (input in nasty) {
            val out = PermissionObservation.sanitize(input)
            assertTrue("produced a control character for ${input.take(20)}", out.none { it < ' ' })
            assertTrue(PermissionObservation.containsNoPlatformNoise(out))
        }
    }

    @Test
    fun `a blank label does not produce a blank or broken sentence`() {
        val text = PermissionObservation.forState(PermissionState.DENIED, "   ")
        assertTrue(text.isNotBlank())
        assertTrue(PermissionObservation.containsNoPlatformNoise(text))
    }
}
