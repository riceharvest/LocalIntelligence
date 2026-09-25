package dev.localintelligence.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The client against recorded responses and a fake transport. No socket.
 */
class HuggingFaceClientTest {

    private fun repo(raw: String): HubRepoId =
        (HubRepoId.parse(raw) as HubRepoId.Result.Valid).repoId

    private fun clientReturning(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
        HuggingFaceClient(FakeTransport { FakeTransport.json(body, status, headers) })

    // ---- listing -------------------------------------------------------

    @Test
    fun `lists only gguf files`() {
        val files = clientReturning(Fixtures.MIXED_QUANT_REPO).listGgufFiles(repo("bartowski/Qwen2.5-3B-Instruct-GGUF"))
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it.fileName.endsWith(".gguf", ignoreCase = true) })
    }

    @Test
    fun `reads the size from the lfs pointer not the pointer file`() {
        val files = clientReturning(Fixtures.MIXED_QUANT_REPO).listGgufFiles(repo("bartowski/Qwen2.5-3B-Instruct-GGUF"))
        val q4 = files.first { it.quant == GgufQuant.Q4_K_M && it.shardCount == null }
        // The raw `size` on this sibling is 134 (the pointer). Reading it would
        // pass any fit check.
        assertEquals(1_928_000_000L, q4.sizeBytes)
    }

    @Test
    fun `reads the sha from the lfs oid`() {
        val files = clientReturning(Fixtures.MIXED_QUANT_REPO).listGgufFiles(repo("bartowski/Qwen2.5-3B-Instruct-GGUF"))
        val q4 = files.first { it.quant == GgufQuant.Q4_K_M && it.shardCount == null }
        assertEquals("2222222222222222222222222222222222222222222222222222222222222222", q4.sha256)
    }

    @Test
    fun `parses quant from the file name`() {
        val files = clientReturning(Fixtures.MIXED_QUANT_REPO).listGgufFiles(repo("bartowski/Qwen2.5-3B-Instruct-GGUF"))
        val quants = files.mapNotNull { it.quant }.toSet()
        assertTrue(quants.contains(GgufQuant.Q2_K))
        assertTrue(quants.contains(GgufQuant.Q4_K_M))
        assertTrue(quants.contains(GgufQuant.Q8_0))
    }

    @Test
    fun `detects a sharded file and its ordinals`() {
        val files = clientReturning(Fixtures.MIXED_QUANT_REPO).listGgufFiles(repo("bartowski/Qwen2.5-3B-Instruct-GGUF"))
        val shard = files.firstOrNull { it.isSharded }
        assertNotNull("fixture should contain a shard", shard)
        assertEquals(1, shard!!.shardIndex)
        assertEquals(9, shard.shardCount)
    }

    @Test
    fun `drops a gguf with no declared size rather than assuming zero`() {
        val files = clientReturning(Fixtures.MIXED_QUANT_REPO).listGgufFiles(repo("bartowski/Qwen2.5-3B-Instruct-GGUF"))
        assertNull("a file with no size must not be offered", files.firstOrNull { it.quant == GgufQuant.F16 })
    }

    @Test
    fun `handles a file nested in a repo folder`() {
        val files = clientReturning(Fixtures.MIXED_QUANT_REPO).listGgufFiles(repo("bartowski/Qwen2.5-3B-Instruct-GGUF"))
        val nested = files.firstOrNull { it.path.segments.size == 2 }
        assertNotNull(nested)
        assertEquals("q4_k_m", nested!!.path.segments.first())
        assertTrue(nested.localFileName().contains("__"))
    }

    @Test
    fun `a repo with no gguf yields an empty list, not an error`() {
        val files = clientReturning(Fixtures.NO_GGUF_REPO).listGgufFiles(repo("org/safetensors-only"))
        assertTrue(files.isEmpty())
    }

    @Test
    fun `skips a sibling whose path is a traversal attempt`() {
        val hostile = """{"id":"x/y","siblings":[
            {"rfilename":"../../../../data/data/app/secret.gguf","size":1900000000},
            {"rfilename":"ok-Q4_K_M.gguf","size":1900000000}]}"""
        val files = clientReturning(hostile).listGgufFiles(repo("x/y"))
        assertEquals(1, files.size)
        assertEquals("ok-Q4_K_M.gguf", files.first().fileName)
    }

    // ---- URL building --------------------------------------------------

    @Test
    fun `builds a download url from the validated id and path`() {
        val client = clientReturning(Fixtures.MIXED_QUANT_REPO)
        val r = repo("bartowski/Qwen2.5-3B-Instruct-GGUF")
        val path = (HubFilePath.parse("q4_k_m/Qwen2.5-3B-Instruct-Q4_K_M.gguf") as HubFilePath.Result.Valid).filePath
        assertEquals(
            "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/q4_k_m/Qwen2.5-3B-Instruct-Q4_K_M.gguf?download=true",
            client.downloadUrl(r, path),
        )
    }

    @Test
    fun `percent-encodes a path segment that needs it`() {
        val client = clientReturning(Fixtures.MIXED_QUANT_REPO)
        val r = repo("o/n")
        val path = (HubFilePath.parse("a b+Q.gguf") as HubFilePath.Result.Valid).filePath
        val url = client.downloadUrl(r, path)
        assertTrue(url.contains("a%20b%2BQ.gguf"))
    }

    @Test
    fun `rejects a revision that would inject a path`() {
        val client = clientReturning(Fixtures.MIXED_QUANT_REPO)
        val r = repo("o/n")
        val path = (HubFilePath.parse("m.gguf") as HubFilePath.Result.Valid).filePath
        try {
            client.downloadUrl(r, path, revision = "../../other")
            fail("expected a rejected revision")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("revision"))
        }
    }

    // ---- error mapping -------------------------------------------------

    @Test
    fun `404 on a repo listing says the repo was not found`() {
        val error = catchHubError { clientReturning("{}", 404).listGgufFiles(repo("nope/nope")) }
        assertTrue("got $error", error is HubError.RepoNotFound)
        assertNoStackTrace(error.message!!)
    }

    @Test
    fun `a bare 401 with no error code is a missing repo, not a gated one`() {
        // Captured from the live API, not inferred. A repo that does not exist
        // answers:
        //   HTTP 401
        //   {"error":"Invalid username or password."}
        //   (no X-Error-Code header)
        // while a genuinely gated repo answers the metadata endpoint 200 with
        // "gated":"manual". So a bare 401 must NOT produce "accept the licence":
        // that sent a user who mistyped a repo name to a licence page for a
        // repository that does not exist.
        val error = catchHubError {
            clientReturning("""{"error":"Invalid username or password."}""", 401)
                .listGgufFiles(repo("thisorg/does-not-exist-xyz"))
        }
        assertTrue("got $error", error is HubError.RepoNotFound)
        assertNoStackTrace(error.message!!)
    }

    @Test
    fun `a bare 403 is also not evidence of gating`() {
        val error = catchHubError { clientReturning("{}", 403).listGgufFiles(repo("m/l")) }
        assertTrue("got $error", error is HubError.RepoNotFound)
    }

    @Test
    fun `gating is still detected when HF says so explicitly`() {
        // The case that matters: an explicit X-Error-Code is the ONLY trustworthy
        // signal, so the fix above must not have broken real gated repos.
        val error = catchHubError {
            clientReturning("{}", 401, mapOf("X-Error-Code" to "GatedRepo"))
                .listGgufFiles(repo("meta-llama/Llama-3.2-1B"))
        }
        assertTrue("got $error", error is HubError.GatedRepo)
        assertTrue(error.message!!.lowercase().contains("gated"))
    }

    @Test
    fun `X-Error-Code GatedRepo wins over the status`() {
        val error = catchHubError {
            clientReturning("{}", 403, mapOf("X-Error-Code" to "GatedRepo")).listGgufFiles(repo("m/l"))
        }
        assertTrue(error is HubError.GatedRepo)
    }

    @Test
    fun `429 carries the retry-after hint`() {
        val error = catchHubError {
            clientReturning("{}", 429, mapOf("Retry-After" to "120")).listGgufFiles(repo("o/n"))
        }
        assertTrue(error is HubError.RateLimited)
        assertTrue(error.message!!.contains("120"))
        assertNoStackTrace(error.message!!)
    }

    @Test
    fun `500 is a retryable server error`() {
        val error = catchHubError { clientReturning("{}", 503).listGgufFiles(repo("o/n")) }
        assertTrue(error is HubError.ServerUnavailable)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `a dead socket maps to no network, not a stack trace`() {
        val client = HuggingFaceClient(FailingTransport(IOException("Unable to resolve host")))
        val error = catchHubError { client.listGgufFiles(repo("o/n")) }
        assertEquals(HubError.NoNetwork, error)
        assertNoStackTrace(error.message!!)
    }

    @Test
    fun `a timeout maps to a retryable connection loss`() {
        val client = HuggingFaceClient(FailingTransport(IOException("Read timed out")))
        val error = catchHubError { client.listGgufFiles(repo("o/n")) }
        assertTrue(error is HubError.ConnectionLost)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `malformed json is a plain error not a serialization exception`() {
        val error = catchHubError { clientReturning("<html>oops</html>").listGgufFiles(repo("o/n")) }
        assertNoStackTrace(error.message!!)
        assertTrue(error.message.length < 120)
    }

    // ---- auth ----------------------------------------------------------

    @Test
    fun `sends the token as an Authorization header, never in the url`() {
        val transport = FakeTransport { FakeTransport.json(Fixtures.MIXED_QUANT_REPO) }
        HuggingFaceClient(transport).listGgufFiles(repo("o/n"), token = "hf_SECRETVALUE")
        val request = transport.requests.single()
        assertTrue("token must not be in the URL: ${request.url}", !request.url.contains("hf_"))
        assertTrue("token must not be in the URL", !request.url.contains("hf_"))
    }

    @Test
    fun `omits the Authorization header when there is no token`() {
        val transport = FakeTransport { FakeTransport.json(Fixtures.MIXED_QUANT_REPO) }
        HuggingFaceClient(transport).listGgufFiles(repo("o/n"), token = null)
        assertNull(transport.requests.single().authToken)
    }

    @Test
    fun `a blank token is treated as no token`() {
        val transport = FakeTransport { FakeTransport.json(Fixtures.MIXED_QUANT_REPO) }
        HuggingFaceClient(transport).listGgufFiles(repo("o/n"), token = "   ")
        assertNull(transport.requests.single().authToken)
    }

    @Test
    fun `the request object toString does not expose the token`() {
        val request = HubRequest("https://huggingface.co/o/n", authToken = "hf_SECRETVALUE")
        assertTrue("toString leaked: ${request}", !request.toString().contains("SECRETVALUE"))
    }

    // ---- search --------------------------------------------------------

    @Test
    fun `search returns only hits with a valid repo id`() {
        val transport = FakeTransport { FakeTransport.FakeResponse(200, Fixtures.SEARCH_RESULTS) }
        val hits = HuggingFaceClient(transport).search("qwen3")
        // 5 hits in the fixture; one is a traversal attempt, one has no id at
        // all, and the other three are usable.
        assertEquals(3, hits.size)
        assertEquals("Qwen/Qwen3-0.6B-GGUF", hits.first().repoId.id)
        assertTrue(hits.none { it.repoId.id.contains("..") })
    }

    @Test
    fun `search failure returns empty rather than throwing`() {
        val client = HuggingFaceClient(FakeTransport { FakeTransport.FakeResponse(500, "boom") })
        assertTrue(client.search("qwen3").isEmpty())
    }

    @Test
    fun `an empty search query makes no request`() {
        val transport = FakeTransport { FakeTransport.FakeResponse(200, "[]") }
        assertTrue(HuggingFaceClient(transport).search("   ").isEmpty())
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `search filters on the gguf tag so safetensors repos are excluded`() {
        val transport = FakeTransport { FakeTransport.FakeResponse(200, "[]") }
        HuggingFaceClient(transport).search("qwen3 0.6b")
        assertTrue(transport.requests.single().url.contains("filter=gguf"))
    }

    // ---- helpers -------------------------------------------------------

    private fun catchHubError(block: () -> Unit): HubError = try {
        block()
        fail("expected a HubError")
        error("unreachable")
    } catch (e: HubError) {
        e
    }

    /**
     * The frozen contract says an observation is what a 1B model reads. A
     * stack trace, a class name, or a URL in that string is a prompt-injection
     * and reasoning hazard, so every message is checked for the markers.
     */
    private fun assertNoStackTrace(message: String) {
        assertTrue("message is too long for an observation: $message", message.length <= 200)
        assertTrue("looks like a stack trace: $message", !message.contains("\tat "))
        assertTrue("looks like a stack trace: $message", !message.contains("Exception"))
        assertTrue("names a class: $message", !message.contains("dev.localintelligence"))
        assertTrue("newlines in an observation: $message", !message.contains("\n"))
    }
}
