package dev.localintelligence.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping a platform denial into a structured state.
 *
 * The distinction being tested is the one no single API call can make: whether
 * the agent has already prompted. Two identical `SecurityException`s mean
 * opposite things depending on that, which is why `askedAlready` is an explicit
 * parameter instead of something this class guesses.
 */
class PlatformDenialTest {

    @Test
    fun `a security exception before any ask is a soft denial`() {
        val result = PlatformDenial.classify("java.lang.SecurityException", askedAlready = false)
        assertTrue(result.isPermissionDenial)
        assertEquals(PermissionState.DENIED, result.state)
        assertTrue(result.state.isRetryable)
    }

    @Test
    fun `a security exception after an ask is a permanent denial`() {
        val result = PlatformDenial.classify("java.lang.SecurityException", askedAlready = true)
        assertTrue(result.isPermissionDenial)
        assertEquals(PermissionState.DENIED_PERMANENTLY, result.state)
        assertFalse("this is the state that must never be retried", result.state.isRetryable)
    }

    @Test
    fun `a simple class name is accepted as well as a qualified one`() {
        assertTrue(PlatformDenial.classify("SecurityException").isPermissionDenial)
    }

    @Test
    fun `an unrelated exception is not treated as a permission denial`() {
        // An exception whose message merely mentions "permission" must not be
        // misread — the allowlist matches on type, not on message text.
        for (name in listOf("IllegalStateException", "NullPointerException", "IOException", null, "")) {
            val result = PlatformDenial.classify(name)
            assertFalse("$name must not be a denial", result.isPermissionDenial)
        }
    }

    @Test
    fun `a null or empty class name is handled safely`() {
        assertEquals(
            PlatformDenial.Classified.NOT_A_DENIAL,
            PlatformDenial.classify(null),
        )
        assertEquals(
            PlatformDenial.Classified.NOT_A_DENIAL,
            PlatformDenial.classify(""),
        )
    }

    @Test
    fun `observation through this path is still sanitised`() {
        val text = PlatformDenial.observationFor(
            PermissionState.DENIED_PERMANENTLY,
            "your calendar",
        )
        assertTrue(PermissionObservation.containsNoPlatformNoise(text))
        assertTrue(text.length <= PermissionObservation.BUDGET_CHARS)
    }
}
