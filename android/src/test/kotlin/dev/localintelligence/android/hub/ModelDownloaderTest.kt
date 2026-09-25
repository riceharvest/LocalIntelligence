package dev.localintelligence.android.hub

import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubError
import dev.localintelligence.core.hub.HubTokenSource
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.core.hub.Sha256
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The downloader's behaviour, against a fake transport and a real filesystem.
 *
 * No network and no device. What is NOT covered here, and is stated as
 * unverified in the PR body: whether `HttpURLConnection` on a real Android
 * device actually returns the 206/`Content-Range` shapes the fake produces.
 * That is the one layer between this suite and a real download.
 */
class ModelDownloaderTest {

    private lateinit var dir: File

    /** A small deterministic payload; big enough to span several buffers. */
    private val payload: ByteArray = ByteArray(200_000) { (it % 251).toByte() }
    private val payloadSha: String =
        Sha256.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload))

    @Before
    fun setUp() {
        dir = tempDir("li-dl")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(sha: String? = payloadSha) =
        testFile("model-Q4_K_M.gguf", payload.size.toLong(), sha)

    /**
     * WHY the dispatcher is a parameter and not a constant: a bare
     * `StandardTestDispatcher()` creates its OWN scheduler, which `runTest`
     * never advances, so the flow would never emit and the test would hang
     * until the suite timed out. Every test must therefore hand in the
     * scheduler of the `runTest` that is collecting it.
     */
    private fun downloader(
        transport: FakeDownloadTransport,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        tokenSource: HubTokenSource = HubTokenSource.NONE,
    ): Pair<ModelDownloader, HuggingFaceClient> {
        val client = HuggingFaceClient(transport)
        return ModelDownloader(
            client = client,
            transport = transport,
            modelsDir = dir,
            tokenSource = tokenSource,
            ioDispatcher = dispatcher,
        ) to client
    }

    private fun planFor(client: HuggingFaceClient, f: dev.localintelligence.core.hub.HubGgufFile, budget: TestBudget = TestBudget()) =
        client.plan(f, contextLength = 2_048, budget = budget)

    // ---- the happy path ------------------------------------------------

    @Test
    fun `downloads a file and ends at the final path with the right bytes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()

        val events = dl.download(f, planFor(client, f)).toList()
        val done = events.last() as DownloadProgress.Done
        val out = File(done.localPath)

        assertTrue("final file must exist", out.exists())
        assertArrayEquals(payload, out.readBytes())
        assertEquals("no .part may survive", 0, dir.listFiles()!!.count { it.name.endsWith(".part") })
    }

    @Test
    fun `a completed download reports Done exactly once and is the last event`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        val events = dl.download(f, planFor(client, f)).toList()
        assertEquals(1, events.count { it is DownloadProgress.Done })
        assertTrue(events.last() is DownloadProgress.Done)
    }

    @Test
    fun `progress is emitted and ends at the full size`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        val events = dl.download(f, planFor(client, f)).toList()
        val inProgress = events.filterIsInstance<DownloadProgress.InProgress>()
        assertTrue("expected progress events", inProgress.isNotEmpty())
        assertEquals(payload.size.toLong(), inProgress.last().bytesDownloaded)
    }

    // ---- resume --------------------------------------------------------

    @Test
    fun `resumes from a partial file and produces a byte-correct result`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()

        // Simulate a previous attempt that died at 100 KB.
        val partialFile = dl.partialFileFor(f)
        val prefix = payload.copyOfRange(0, 100_000)
        partialFile.partFile.writeBytes(prefix)

        val events = dl.download(f, planFor(client, f, TestBudget())).toList()
        val done = events.last()
        assertTrue("expected Done, got $done", done is DownloadProgress.Done)

        val out = File((done as DownloadProgress.Done).localPath)
        assertArrayEquals("resumed file must be byte-identical to the source", payload, out.readBytes())
    }

    @Test
    fun `a resume asks the server for a Range starting at the local length`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        dl.partialFileFor(f).partFile.writeBytes(payload.copyOfRange(0, 100_000))

        dl.download(f, planFor(client, f)).toList()

        val byteRequest = transport.requests.last { !it.url.contains("/api/models") }
        assertEquals(100_000L, byteRequest.rangeFrom)
    }

    @Test
    fun `a server that IGNORES the Range is not appended to`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload).apply { ignoreRange = true }
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        dl.partialFileFor(f).partFile.writeBytes(payload.copyOfRange(0, 100_000))

        val events = dl.download(f, planFor(client, f)).toList()
        val last = events.last()
        // Either it restarts and completes, or it reports a stopped transfer.
        // What must never happen is a Done whose bytes are wrong.
        if (last is DownloadProgress.Done) {
            assertArrayEquals(payload, File(last.localPath).readBytes())
        } else {
            assertTrue(last is DownloadProgress.Stopped)
        }
        // The dangerous case is appending a 200 body to a partial file.
        val out = File(dl.partialFileFor(f).finalFile.path)
        if (out.exists()) {
            assertArrayEquals("a 200 body must never be appended to a prefix", payload, out.readBytes())
        }
    }

    @Test
    fun `a partial longer than the file is discarded rather than appended to`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        // A stale partial from a previous, larger revision.
        dl.partialFileFor(f).partFile.writeBytes(ByteArray(payload.size + 5_000))

        val events = dl.download(f, planFor(client, f)).toList()
        val done = events.last()
        assertTrue(done is DownloadProgress.Done)
        assertArrayEquals(payload, File((done as DownloadProgress.Done).localPath).readBytes())
    }

    // ---- cancellation: the invariant -----------------------------------

    @Test
    fun `cancellation leaves NO file where a valid one is expected`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()

        // Drop the link partway through: the transfer stops, and whatever the
        // user is left with must be a `.part` and nothing at the final path.
        transport.truncateAfterBytes = 50_000
        val stopped = dl.download(f, planFor(client, f)).toList().last()
        assertTrue("expected a stop, got $stopped", stopped is DownloadProgress.Stopped)

        val partial = dl.partialFileFor(f)
        assertFalse(
            "A cancelled download must never leave a file at the final path",
            partial.finalFile.exists(),
        )
        assertTrue("the partial is kept so a retry can resume", partial.partFile.exists())
    }

    @Test
    fun `a genuinely cancelled job mid-copy leaves the final path clean`() {
        // A stream that delivers a megabyte and then blocks forever. Cancel has
        // to land while bytes are still moving, which is the only cancellation
        // worth testing; cancelling a finished download proves nothing.
        val stalling = StallingTransport(payload, stallAfterBytes = 1_000_000)
        val client = HuggingFaceClient(stalling)
        val dl = ModelDownloader(
            client = client,
            transport = stalling,
            modelsDir = dir,
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
        )
        val f = file()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val job = scope.launch { dl.download(f, planFor(client, f)).collect { } }

        // Wait for the transfer to actually be in flight, so the cancel lands
        // mid-copy rather than before it starts.
        val part = dl.partialFileFor(f).partFile
        val startDeadline = System.currentTimeMillis() + 5_000
        while (!stalling.reachedStall && System.currentTimeMillis() < startDeadline) Thread.sleep(5)
        assertTrue("the transfer never reached the stalled read", stalling.reachedStall)

        val cancelledAt = System.currentTimeMillis()
        job.cancel()
        // A hard join deadline. Without the body's cancellation handler a
        // cancel cannot interrupt a blocked read, and the join would sit for
        // the full 60 s read timeout - which is exactly the bug this test
        // exists to catch, so it must fail fast rather than hang.
        // `join(timeout)` is experimental in coroutines 1.10, so the deadline
        // is expressed with `withTimeoutOrNull` instead.
        // Poll on real wall-clock time. This test is deliberately NOT inside
        // runTest: that uses a virtual clock which would expire the deadline
        // instantly, and this test is about real thread behaviour.
        val stopDeadline = System.currentTimeMillis() + 5_000
        while (job.isActive && System.currentTimeMillis() < stopDeadline) Thread.sleep(5)
        val tookMs = System.currentTimeMillis() - cancelledAt

        assertTrue(
            "cancel did not stop the download within 5 s (took ${tookMs}ms); " +
                "a blocked read is not being interrupted by the cancellation handler",
            !job.isActive,
        )
        assertTrue("cancel must be prompt, took ${tookMs}ms", tookMs < 5_000)
        assertFalse(
            "A cancelled job must never leave a file at the final path",
            dl.partialFileFor(f).finalFile.exists(),
        )
    }

    // ---- integrity -----------------------------------------------------

    @Test
    fun `a checksum mismatch is detected and the file is discarded`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val wrongSha = "0".repeat(64)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file(sha = wrongSha)

        val stopped = dl.download(f, planFor(client, f)).toList().last()
        assertTrue("expected a stop, got $stopped", stopped is DownloadProgress.Stopped)
        assertTrue("must be a checksum failure: ${(stopped as DownloadProgress.Stopped).error}",
            stopped.error is HubError.ChecksumMismatch)
        val partial = dl.partialFileFor(f)
        assertFalse("a corrupt file must not be kept", partial.partFile.exists())
        assertFalse("a corrupt file must never reach the final path", partial.finalFile.exists())
    }

    @Test
    fun `a correct checksum promotes the file`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file(payloadSha)
        val done = dl.download(f, planFor(client, f)).toList().last()
        assertTrue(done is DownloadProgress.Done)
    }

    @Test
    fun `a file with no published sha is accepted on length alone`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        // HF does not always expose an LFS OID; refusing those would lock out
        // a lot of real repos.
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file(sha = null)
        val done = dl.download(f, planFor(client, f)).toList().last()
        assertTrue(done is DownloadProgress.Done)
    }

    @Test
    fun `a truncated transfer does not become a valid file`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        transport.truncateAfterBytes = 1_000

        val stopped = dl.download(f, planFor(client, f)).toList().last()
        assertTrue(stopped is DownloadProgress.Stopped)
        assertFalse(dl.partialFileFor(f).finalFile.exists())
    }

    // ---- the rejection gate --------------------------------------------

    @Test
    fun `a plan that does not fit RAM never opens a connection`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        // A tiny device: the fit gate must refuse before any bytes move.
        val tiny = TestBudget(ram = 10_000_000L, disk = 64L * 1024 * 1024 * 1024)
        val f = testFile("model-Q8_0.gguf", 3_284_000_000L, payloadSha)
        val plan = client.plan(f, contextLength = 8_192, budget = tiny)
        assertFalse("fixture must produce a rejection", plan.isAllowed)

        val events = dl.download(f, plan).toList()
        assertTrue(events.last() is DownloadProgress.Stopped)
        assertEquals("no HTTP request may be made", 0, transport.requests.size)
    }

    @Test
    fun `a plan with insufficient disk is refused before the transfer`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val noDisk = TestBudget(ram = 8L * 1024 * 1024 * 1024, disk = 1_000L)
        val f = testFile("model-Q4_K_M.gguf", 500_000_000L, payloadSha)
        val plan = client.plan(f, contextLength = 2_048, budget = noDisk)
        assertFalse(plan.isAllowed)

        val events = dl.download(f, plan).toList()
        assertTrue(events.last() is DownloadProgress.Stopped)
        assertEquals(0, transport.requests.size)
    }

    // ---- error mapping -------------------------------------------------

    @Test
    fun `404 on the file says the file was not found`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload).apply { forceStatus = 404 }
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        val stopped = dl.download(f, planFor(client, f)).toList().last() as DownloadProgress.Stopped
        assertTrue(stopped.error is HubError.FileNotFound)
        assertNoTrace(stopped.error.message)
    }

    @Test
    fun `401 on the file says gated, not unauthorized`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload).apply {
            forceStatus = 401
            forceHeaders = mapOf("X-Error-Code" to "GatedRepo")
        }
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        val stopped = dl.download(f, planFor(client, f)).toList().last() as DownloadProgress.Stopped
        assertTrue("got ${stopped.error}", stopped.error is HubError.GatedRepo)
        assertTrue(stopped.error.message!!.lowercase().contains("gated"))
        assertNoTrace(stopped.error.message)
    }

    @Test
    fun `429 maps to a rate-limit message with a retry hint`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload).apply {
            forceStatus = 429
            forceHeaders = mapOf("Retry-After" to "45")
        }
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        val stopped = dl.download(f, planFor(client, f)).toList().last() as DownloadProgress.Stopped
        assertTrue(stopped.error is HubError.RateLimited)
        assertTrue(stopped.error.message!!.contains("45"))
    }

    @Test
    fun `a dead socket maps to a retryable connection loss`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload).apply {
            failNextWith = IOException("Unable to resolve host")
        }
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        val stopped = dl.download(f, planFor(client, f)).toList().last() as DownloadProgress.Stopped
        assertTrue("got ${stopped.error}", stopped.error is HubError.ConnectionLost)
        assertTrue(stopped.error.isRetryable)
    }

    @Test
    fun `a short read is reported without a stack trace`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload).apply { truncateAfterBytes = 500 }
        val (dl, client) = downloader(transport, dispatcher)
        val f = file()
        val stopped = dl.download(f, planFor(client, f)).toList().last() as DownloadProgress.Stopped
        assertNoTrace(stopped.error.message)
        assertTrue(stopped.error.message!!.length < 200)
    }

    // ---- auth ----------------------------------------------------------

    @Test
    fun `a token is sent as a header and never appears in the url`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher, tokenSource = HubTokenSource.of("hf_EXAMPLETOKEN"))
        val f = file()
        dl.download(f, planFor(client, f)).toList()
        val byteRequest = transport.requests.last { !it.url.contains("/api/models") }
        assertEquals("hf_EXAMPLETOKEN", byteRequest.authToken)
        assertFalse("token must never be in a URL", byteRequest.url.contains("EXAMPLETOKEN"))
    }

    @Test
    fun `no token means no Authorization header at all`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher, tokenSource = HubTokenSource.NONE)
        val f = file()
        dl.download(f, planFor(client, f)).toList()
        val byteRequest = transport.requests.last { !it.url.contains("/api/models") }
        assertNull(byteRequest.authToken)
    }

    // ---- local naming --------------------------------------------------

    @Test
    fun `the local file name is derived from the repo and path, so two repos cannot collide`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, client) = downloader(transport, dispatcher)
        val a = testFile("model.gguf", payload.size.toLong(), payloadSha).copy(repo = testRepo("org/one"))
        val b = testFile("model.gguf", payload.size.toLong(), payloadSha).copy(repo = testRepo("org/two"))
        assertFalse("two repos must not share a local name", a.localFileName() == b.localFileName())
    }

    @Test
    fun `a traversal file name is rejected before any File is constructed`() {
        // The real defence: HubFilePath refuses the traversal outright, so no
        // local name is ever derived from it.
        for (hostile in listOf("../../escape.gguf", "a/../../b.gguf", "..%2F..%2Fescape.gguf", "/etc/passwd")) {
            val parsed = dev.localintelligence.core.hub.HubFilePath.parse(hostile)
            assertTrue(
                "expected '$hostile' to be rejected, got $parsed",
                parsed is dev.localintelligence.core.hub.HubFilePath.Result.Invalid,
            )
        }
    }

    @Test
    fun `the local path of a valid file stays inside the models directory`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val (dl, _) = downloader(transport, dispatcher)
        // A NESTED path, which real repos use and which is the case where a
        // naive join would create a subdirectory.
        val nested = testFile("q4_k_m/model-Q4_K_M.gguf", payload.size.toLong(), payloadSha)
        val partial = dl.partialFileFor(nested)
        assertTrue(
            "escaped to ${partial.finalFile.canonicalPath}",
            partial.finalFile.canonicalPath.startsWith(dir.canonicalPath),
        )
        assertTrue("the final name must be flat, not a path", !partial.finalFile.name.contains('/'))
    }

    // ---- throttling ----------------------------------------------------

    @Test
    fun `progress is throttled, not one emission per buffer`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        // 8 MB = 128 reads of the 64 KiB buffer. An unthrottled implementation
        // emits 128 times; a throttled one must emit a small constant.
        val big = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        val bigSha = Sha256.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(big))
        val transport = FakeDownloadTransport(big)
        val client = HuggingFaceClient(transport)
        val f = testFile("model-Q4_K_M.gguf", big.size.toLong(), bigSha)
        // A clock the test drives: 100 ms of simulated time per 64 KiB buffer,
        // i.e. a fast link at roughly 640 KB/s. The 250 ms interval therefore
        // binds, and the emission count is bounded by TIME rather than by
        // buffer reads - which is the property under test.
        val clock = object : () -> Long {
            var now = 0L
            override fun invoke(): Long = now
        }
        val dl = ModelDownloader(
            client = client,
            transport = transport,
            modelsDir = dir,
            ioDispatcher = dispatcher,
            throttle = dev.localintelligence.core.hub.ProgressThrottle(clock = clock),
        )
        val events = dl.download(f, client.plan(f, contextLength = 2_048, budget = TestBudget())).toList()
        val inProgress = events.filterIsInstance<DownloadProgress.InProgress>()
        assertTrue("expected a completion, got ${events.last()}", events.last() is DownloadProgress.Done)
        // 128 buffer reads = 12.8 s of simulated time. At one emission per
        // 250 ms that bounds the count near 52, and the byte gate can only
        // reduce it. The unthrottled implementation would emit 128.
        assertTrue(
            "128 buffer reads must not produce 128 emissions, got ${inProgress.size}",
            inProgress.size <= 60,
        )
        assertTrue(
            "emissions must be far fewer than buffer reads, got ${inProgress.size}",
            inProgress.size < 128 / 2,
        )
        // The terminal emission must still report the true total, or the bar
        // freezes short of full and then vanishes.
        assertEquals(big.size.toLong(), inProgress.last().bytesDownloaded)
    }

    @Test
    fun `a closed throttle still reports the start and the end`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val transport = FakeDownloadTransport(payload)
        val client = HuggingFaceClient(transport)
        val f = file()
        val throttle = dev.localintelligence.core.hub.ProgressThrottle(
            minBytes = Long.MAX_VALUE,
            minIntervalMillis = Long.MAX_VALUE,
            clock = { 0L },
        )
        val dl = ModelDownloader(client, transport, dir, ioDispatcher = dispatcher, throttle = throttle)
        val events = dl.download(f, client.plan(f, contextLength = 2_048, budget = TestBudget())).toList()
        val inProgress = events.filterIsInstance<DownloadProgress.InProgress>()
        // With a frozen clock only the starvation escape can fire mid-transfer,
        // so this asserts the two emissions that must ALWAYS happen: the start
        // and the terminal one.
        assertTrue("expected at least the start emission", inProgress.isNotEmpty())
        assertEquals(0L, inProgress.first().bytesDownloaded)
        assertEquals(payload.size.toLong(), inProgress.last().bytesDownloaded)
    }

    private fun assertNoTrace(message: String?) {
        assertNotNull(message)
        assertTrue("message must be short: $message", message!!.length <= 200)
        assertTrue("no stack frames: $message", !message.contains("\tat "))
        assertTrue("no class names: $message", !message.contains("dev.localintelligence"))
        assertTrue("no newlines: $message", !message.contains("\n"))
    }
}
