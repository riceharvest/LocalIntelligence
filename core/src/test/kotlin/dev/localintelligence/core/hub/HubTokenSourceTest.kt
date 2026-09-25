package dev.localintelligence.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-token path, which is what every ungated download takes.
 *
 * These are the tests that prove a fresh install works with no account, no
 * credential and no stored secret — the state the app ships in.
 */
class HubTokenSourceTest {

    private fun repo(raw: String): HubRepoId =
        (HubRepoId.parse(raw) as HubRepoId.Result.Valid).repoId

    @Test
    fun `the default source returns null, so a fresh install has no token`() {
        assertNull(HubTokenSource.NONE.token())
    }

    @Test
    fun `a blank or whitespace token is normalised to null`() {
        // A `Bearer ` header with an empty value makes HF answer 401, which the
        // user would read as "my token is wrong" when they never had one.
        assertNull(HubTokenSource.of("").token())
        assertNull(HubTokenSource.of("   ").token())
        assertNull(HubTokenSource.of(null).token())
    }

    @Test
    fun `a real token is returned verbatim`() {
        assertEquals("hf_example", HubTokenSource.of("hf_example").token())
    }

    @Test
    fun `the client sends no Authorization header when the source is empty`() {
        val transport = FakeTransport { FakeTransport.json(Fixtures.MIXED_QUANT_REPO) }
        HuggingFaceClient(transport, tokenSource = HubTokenSource.NONE)
            .listGgufFiles(repo("o/n"))
        assertNull(transport.requests.single().authToken)
    }

    @Test
    fun `the client picks the token up from its source on every request`() {
        // WHY this matters: a token added in settings must take effect without
        // the client being rebuilt, so the source is read per request.
        var current: String? = null
        val transport = FakeTransport { FakeTransport.json(Fixtures.MIXED_QUANT_REPO) }
        val client = HuggingFaceClient(transport, tokenSource = { current })

        client.listGgufFiles(repo("o/n"))
        assertNull(transport.requests.last().authToken)

        current = "hf_added_later"
        client.listGgufFiles(repo("o/n"))
        assertEquals("hf_added_later", transport.requests.last().authToken)
    }

    @Test
    fun `an explicit per-call token overrides the source`() {
        val transport = FakeTransport { FakeTransport.json(Fixtures.MIXED_QUANT_REPO) }
        HuggingFaceClient(transport, tokenSource = HubTokenSource.of("from_source"))
            .listGgufFiles(repo("o/n"), token = "explicit")
        assertEquals("explicit", transport.requests.single().authToken)
    }

    @Test
    fun `no request ever carries a token in its url`() {
        val transport = FakeTransport { FakeTransport.json(Fixtures.MIXED_QUANT_REPO) }
        HuggingFaceClient(transport, tokenSource = HubTokenSource.of("hf_secret_value"))
            .listGgufFiles(repo("o/n"))
        val request = transport.requests.single()
        assertFalse(request.url.contains("secret"))
        assertFalse(request.url.contains("hf_"))
        assertFalse(request.url.contains("?token="))
    }

    @Test
    fun `the token source is not echoed by the client or the request`() {
        val client = HuggingFaceClient(
            FakeTransport { FakeTransport.json(Fixtures.MIXED_QUANT_REPO) },
            tokenSource = HubTokenSource.of("hf_secret_value"),
        )
        assertFalse("client toString leaked", client.toString().contains("secret"))
        val request = HubRequest("https://huggingface.co/o/n", authToken = "hf_secret_value")
        assertFalse("request toString leaked", request.toString().contains("secret"))
        assertTrue("a token must be visibly present as a header slot", request.toString().contains("authToken"))
    }

    @Test
    fun `a gated repo without a token reports gated, with a fix the user can act on`() {
        val transport = FakeTransport {
            FakeTransport.FakeResponse(401, "{}", mapOf("X-Error-Code" to "GatedRepo"))
        }
        val client = HuggingFaceClient(transport)
        val error = runCatching { client.listGgufFiles(repo("meta-llama/Llama-3.2-1B")) }
            .exceptionOrNull() as HubError
        assertTrue(error is HubError.GatedRepo)
        assertTrue(error.message!!.contains("huggingface.co"))
        assertTrue("must offer a token as one of the two fixes", error.message!!.contains("token"))
    }
}
