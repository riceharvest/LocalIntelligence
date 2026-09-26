package dev.localintelligence.core.memory

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential-exclusion invariant.
 *
 * This is a REGRESSION test for a security bug that shipped: `CREDENTIAL`
 * matched turns that literally contain a secret ("my wifi password is X") and
 * then assigned them `IMPORTANCE_CREDENTIAL` — 0.9, the HIGHEST importance in
 * the file. The policy was therefore working to keep users' plaintext
 * credentials in durable memory forever, and ranking them above the user's own
 * name and address.
 *
 * Memory is plain Room SQLite. The HuggingFace token goes to the Android
 * Keystore; this did not. Same product, two opposite security postures.
 *
 * The rule now is a hard exclusion, so there is nothing left for a later
 * refactor to restore. This test exists to make that exclusion load-bearing.
 *
 * ## WHY EVERY STRING HERE ALSO MATCHES A POSITIVE PATTERN
 *
 * This is the part that is easy to get wrong, and the first draft of this file
 * got it wrong: `my wifi password is hunter2` returns null whether or not the
 * hard exclusion exists, because it matches none of IDENTITY / PREFERENCE /
 * CONTEXT and the policy's default is to drop what it does not recognise.
 * Such a test passes with the guard deleted — it pins the fallback, not the
 * security control, and a reviewer reading it would reasonably believe the
 * opposite.
 *
 * The guard is only load-bearing when a turn contains a secret AND something
 * the policy would otherwise want to keep. A user who says "my wifi password
 * is hunter2 and my name is Dario" is stating a durable fact about themselves
 * — that is precisely the turn the policy exists to capture, and precisely the
 * turn the exclusion has to veto. Each case below therefore pairs a real
 * credential with a real identity, preference or context statement, and every
 * one of them was verified to flip to KEPT when the exclusion is removed.
 */
class MemoryWritePolicyCredentialTest {

    // `MemoryWritePolicy` is an object, not a class: it is stateless, so there
    // is nothing to construct and nothing to share between tests.
    private fun extract(text: String): String? = MemoryWritePolicy.extract(text)

    /**
     * A credential must be excluded even when the same turn carries an
     * ordinary, genuinely memorable fact. Without this the guard is untested:
     * a bare secret is dropped by the conservative default anyway.
     */
    @Test
    fun `wifi password is never stored even alongside the user's name`() {
        assertNull(extract("my wifi password is hunter2 and my name is Dario"))
    }

    @Test
    fun `bank pin is never stored even alongside a stated preference`() {
        assertNull(extract("I prefer metric units, and my bank pin is 4821"))
    }

    @Test
    fun `passcode is never stored even alongside the user's workplace`() {
        assertNull(extract("I work at the depot and my phone passcode is 903812"))
    }

    @Test
    fun `passphrase is never stored even alongside where the user lives`() {
        assertNull(extract("I live in Leeds and my email passphrase is correct horse"))
    }

    @Test
    fun `api key is never stored even alongside a pet`() {
        assertNull(extract("my api key is sk-abc123 and my dog is called Rex"))
    }

    /**
     * A credential must be excluded when dressed up as a plan rather than a
     * statement, so the guard cannot be sidestepped by phrasing. The leading
     * clause is a CONTEXT match, which is what makes this one bite.
     */
    @Test
    fun `a credential buried in a sentence is still excluded`() {
        assertNull(
            extract(
                "I need to sign in to the router tonight because my wifi password is " +
                    "hunter2 and the technician is coming at six",
            ),
        )
    }

    /**
     * The bare forms, kept because they are what a user actually types, and
     * asserted for the reason they really hold: the policy drops a turn that
     * states nothing it recognises. `my apikey is sk-abc123` is the clearest
     * case — "apikey" is ONE token, so the credential regex does not match it
     * at all and it is the fallback doing the work. Read alongside the paired
     * cases above, the difference between the two is the whole point of this
     * file.
     */
    @Test
    fun `a bare secret with nothing else to keep is dropped`() {
        assertNull(extract("my wifi password is hunter2"))
        assertNull(extract("my bank pin is 4821"))
        assertNull(extract("my phone passcode is 903812"))
        assertNull(extract("my email passphrase is correct horse battery"))
        assertNull(extract("my apikey is sk-abc123"))
    }

    /**
     * The inverse check, and the reason this test file is worth having: the
     * exclusion must not become a blanket "reject anything scary", or the
     * memory feature stops working for ordinary facts. Non-secret identity and
     * preference statements must still be captured.
     */
    @Test
    fun `ordinary identity facts are still captured`() {
        assertTrue(
            "normal identity facts must still be remembered",
            extract("my name is Dario") != null,
        )
        assertTrue(
            "preferences must still be remembered",
            extract("I prefer dark mode in every app") != null,
        )
    }

    /** A question is not a statement and is not stored, which is pre-existing. */
    @Test
    fun `questions are not stored`() {
        assertNull(extract("what is my wifi password?"))
    }
}
