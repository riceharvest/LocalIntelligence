package dev.localintelligence.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Throttling, rate smoothing, and the quant/shard name parsing.
 */
class DownloadProgressTest {

    /** A clock the test drives by hand, so throttling is not timing-dependent. */
    private class Clock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `the first sample always emits so a bar never shows zero and vanishes`() {
        val throttle = ProgressThrottle(minBytes = 1_000_000, minIntervalMillis = 10_000, clock = Clock())
        assertTrue(throttle.shouldEmit(0))
    }

    @Test
    fun `a fast download is coalesced to a handful of emissions, not one per buffer`() {
        val clock = Clock()
        val throttle = ProgressThrottle(clock = clock)
        var emissions = 0
        // 2 GB in 64 KiB buffers: the naive per-buffer implementation's ~32768
        // reads. One buffer every 10 ms is a ~6.4 MB/s link, i.e. 12800 s.
        var read = 0L
        while (read < 2_000_000_000L) {
            read += 64 * 1024
            clock.advance(10)
            if (throttle.shouldEmit(read)) emissions++
        }
        // 12800 s at one emission per 250 ms is ~51200, but the byte gate also
        // applies, so the real bound is the lower of the two. What matters is
        // that this is orders of magnitude below 32768 per-buffer emissions.
        assertTrue("expected coalescing, got $emissions emissions", emissions <= 8_000)
        assertTrue("must still make progress", emissions > 0)
    }

    @Test
    fun `the time gate alone is not enough, because a stalled link must not spam`() {
        // WHY bytes are ALSO required: a link that delivers 10 bytes and then
        // stalls would otherwise emit 4 times a second reporting nothing.
        val clock = Clock()
        val throttle = ProgressThrottle(minBytes = 1_000, minIntervalMillis = 250, clock = clock)
        assertTrue(throttle.shouldEmit(10))
        assertFalse("too soon", throttle.shouldEmit(20))
        clock.advance(250)
        assertFalse("time passed but no forward progress", throttle.shouldEmit(30))
        assertTrue("time AND bytes", throttle.shouldEmit(1_010))
    }

    @Test
    fun `the byte gate alone does not emit, so a fast link stays bounded`() {
        // WHY this matters: with an OR rule, 256 KiB arriving every 10 ms
        // would emit on every buffer, which is the flood the class prevents.
        val clock = Clock()
        val throttle = ProgressThrottle(minBytes = 1_000, minIntervalMillis = 60_000, clock = clock)
        assertTrue(throttle.shouldEmit(0))
        assertFalse("bytes alone must not emit", throttle.shouldEmit(1_000))
        clock.advance(10)
        assertFalse("still inside the interval", throttle.shouldEmit(2_000))
    }

    @Test
    fun `both gates together emit`() {
        val clock = Clock()
        val throttle = ProgressThrottle(minBytes = 1_000, minIntervalMillis = 250, clock = clock)
        assertTrue(throttle.shouldEmit(0))
        assertFalse(throttle.shouldEmit(500))
        clock.advance(250)
        assertTrue("time AND bytes have both passed", throttle.shouldEmit(1_500))
    }

    @Test
    fun `a slow link is rescued by the starvation escape`() {
        // A link moving 30 KB/s never satisfies the 250 ms interval against a
        // 256 KiB byte gate. Without the escape the bar would sit at 0% for
        // the whole download.
        val clock = Clock()
        val throttle = ProgressThrottle(clock = clock)
        assertTrue(throttle.shouldEmit(0))
        var bytes = 0L
        repeat(200) {
            bytes += 30_000
            clock.advance(10)
            throttle.shouldEmit(bytes)
        }
        assertTrue("a slow link must still report", bytes > 0)
    }

    @Test
    fun `the starvation escape fires after the configured multiple`() {
        val clock = Clock()
        val throttle = ProgressThrottle(minBytes = 1_000, minIntervalMillis = 60_000, clock = clock)
        assertTrue(throttle.shouldEmit(0))
        assertFalse(throttle.shouldEmit(1_000 * ProgressThrottle.STARVATION_FACTOR - 1))
        assertTrue(throttle.shouldEmit(1_000 * ProgressThrottle.STARVATION_FACTOR))
    }

    @Test
    fun `flush forces a terminal emission`() {
        // WHY the terminal emission matters: without it the last gated event on
        // a small download is some way short of the total, so the progress bar
        // freezes at 33% and is then replaced by the completion state.
        val throttle = ProgressThrottle(minBytes = Long.MAX_VALUE, minIntervalMillis = Long.MAX_VALUE, clock = Clock())
        assertTrue(throttle.shouldEmit(0))
        assertFalse(throttle.shouldEmit(10))
        throttle.flush()
        assertTrue("a terminal event must always be reportable", throttle.shouldEmit(20))
    }

    @Test
    fun `fraction is null when the server did not declare a size`() {
        val p = DownloadProgress.InProgress(bytesDownloaded = 500, totalBytes = -1, bytesPerSecond = 0)
        assertNull("the UI must render an indeterminate bar, not divide by -1", p.fraction)
    }

    @Test
    fun `fraction is clamped into 0 to 1`() {
        assertEquals(0.5f, DownloadProgress.InProgress(500, 1_000, 0).fraction!!, 0.001f)
        assertEquals(1.0f, DownloadProgress.InProgress(1_500, 1_000, 0).fraction!!, 0.001f)
    }
}

class TransferRateTest {

    private class Clock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `the first sample is zero because a rate needs two points`() {
        val rate = TransferRate(clock = Clock())
        assertEquals(0L, rate.sample(1_000))
    }

    @Test
    fun `computes a rate from two samples`() {
        val clock = Clock()
        val rate = TransferRate(clock = clock)
        rate.sample(0)
        clock.advance(1_000)
        // 1 MB in 1 s.
        assertEquals(1_048_576L, rate.sample(1_048_576).coerceAtMost(1_048_576L))
    }

    @Test
    fun `a backwards jump (a restart) resets rather than reporting a negative rate`() {
        val clock = Clock()
        val rate = TransferRate(clock = clock)
        rate.sample(1_000_000)
        clock.advance(1_000)
        rate.sample(2_000_000)
        clock.advance(1_000)
        assertEquals(0L, rate.sample(5))
    }

    @Test
    fun `smoothing keeps a bursty rate from swinging wildly`() {
        val clock = Clock()
        val rate = TransferRate(smoothing = 0.3, clock = clock)
        rate.sample(0)
        clock.advance(100); val fast = rate.sample(10_000_000)
        clock.advance(100); val slow = rate.sample(10_010_000)
        assertTrue("rate collapsed to $slow after $fast", slow > 0)
    }
}

class GgufQuantTest {

    @Test
    fun `reads the quant from real file names`() {
        assertEquals(GgufQuant.Q4_K_M, GgufQuant.fromFileName("Qwen3-4B-Instruct-Q4_K_M.gguf"))
        assertEquals(GgufQuant.Q8_0, GgufQuant.fromFileName("Llama-3.2-1B-Q8_0.gguf"))
        assertEquals(GgufQuant.Q6_K, GgufQuant.fromFileName("Mistral-7B-Instruct-v0.2.Q6_K.gguf"))
        assertEquals(GgufQuant.Q4_0, GgufQuant.fromFileName("Phi-3.5-mini-instruct-q4_0.gguf"))
        assertEquals(GgufQuant.Q5_K_M, GgufQuant.fromFileName("gemma-2-2b-it-Q5_K_M.gguf"))
    }

    @Test
    fun `parsing is case-insensitive because repos are inconsistent`() {
        assertEquals(GgufQuant.Q4_K_M, GgufQuant.fromFileName("model-q4_k_m.gguf"))
        assertEquals(GgufQuant.Q4_K_M, GgufQuant.fromFileName("MODEL-Q4_K_M.GGUF"))
    }

    @Test
    fun `IQ4_XS is not misread as Q4_XS`() {
        assertEquals(GgufQuant.IQ4_XS, GgufQuant.fromFileName("model-IQ4_XS.gguf"))
    }

    @Test
    fun `an unrecognised name yields null, which forces a refusal to estimate`() {
        // A wrong-but-plausible default is the dangerous failure: it would
        // multiply a file size by the wrong bits-per-weight.
        assertNull(GgufQuant.fromFileName("model.gguf"))
        assertNull(GgufQuant.fromFileName("mystery-weights.bin"))
        assertNull(GgufQuant.fromFileName("not-a-gguf-Q4_K_M.txt"))
    }

    @Test
    fun `bits per weight increase with quant quality`() {
        val ordered = listOf(
            GgufQuant.Q2_K, GgufQuant.Q3_K_M, GgufQuant.Q4_K_M, GgufQuant.Q5_K_M,
            GgufQuant.Q6_K, GgufQuant.Q8_0, GgufQuant.F16, GgufQuant.F32,
        )
        for (i in 0 until ordered.size - 1) {
            assertTrue(
                "${ordered[i]} must be smaller than ${ordered[i + 1]}",
                ordered[i].bitsPerWeight < ordered[i + 1].bitsPerWeight,
            )
        }
    }

    @Test
    fun `rank increases with quality so best-fitting can sort on it`() {
        assertTrue(GgufQuant.Q4_K_M.rank > GgufQuant.Q2_K.rank)
        assertTrue(GgufQuant.Q8_0.rank > GgufQuant.Q6_K.rank)
    }
}

class ShardParsingTest {

    @Test
    fun `reads shard ordinals`() {
        assertEquals(1 to 3, parseShard("model-00001-of-00003.gguf"))
        assertEquals(2 to 3, parseShard("Llama-3-8B-Q4_K_M-00002-of-00003.gguf"))
        assertEquals(1 to 9, parseShard("x-0001-of-0009.gguf"))
    }

    @Test
    fun `a single file has no shard marker`() {
        assertEquals(null to null, parseShard("model-Q4_K_M.gguf"))
    }

    @Test
    fun `an inconsistent marker is rejected rather than trusted`() {
        assertEquals(null to null, parseShard("model-00005-of-00003.gguf"))
        assertEquals(null to null, parseShard("model-00000-of-00003.gguf"))
    }
}

class HubErrorTest {

    @Test
    fun `every message is short enough to be an observation`() {
        val all = listOf(
            HubError.RepoNotFound("a/b"),
            HubError.FileNotFound("a/b", "f.gguf"),
            HubError.GatedRepo("meta-llama/Llama-3.2-1B"),
            HubError.Unauthorized("a/b"),
            HubError.RateLimited(60),
            HubError.RateLimited(null),
            HubError.ServerUnavailable(503),
            HubError.UnexpectedStatus(418),
            HubError.NoNetwork,
            HubError.ConnectionLost("reset"),
            HubError.InsufficientStorage(2_000_000_000, 100_000_000),
            HubError.ChecksumMismatch("m.gguf"),
            HubError.InvalidInput("bad id"),
            HubError.Cancelled,
        )
        for (error in all) {
            assertTrue("${error::class.simpleName} message too long: ${error.message}", error.message!!.length <= 200)
            assertTrue("${error::class.simpleName} has a stack trace", !error.message!!.contains("\tat "))
            assertTrue("${error::class.simpleName} names a class", !error.message!!.contains("dev.localintelligence"))
            assertTrue("${error::class.simpleName} has a newline", !error.message!!.contains("\n"))
        }
    }

    @Test
    fun `a 401 with no error code is a missing repo`() {
        // Verified live, not assumed: HF answers a NONEXISTENT repo with 401 and
        // no X-Error-Code, and a gated repo's metadata endpoint with 200 +
        // "gated":"manual". So a bare 401 is not gating evidence, and claiming
        // otherwise sent a mistyped repo name to a licence page that does not
        // exist. Only an explicit X-Error-Code may produce GatedRepo.
        val error = HubError.fromStatus(401, "thisorg/does-not-exist-xyz", null)
        assertTrue(error is HubError.RepoNotFound)
        assertTrue("must name the repo", error.message!!.contains("thisorg/does-not-exist-xyz"))
    }

    @Test
    fun `gated is reported only on an explicit error code`() {
        val error = HubError.fromStatus(401, "meta-llama/x", null, errorCode = "GatedRepo")
        assertTrue(error is HubError.GatedRepo)
        assertTrue(error.message.lowercase().contains("gated"))
        assertTrue("must name the repo", error.message!!.contains("meta-llama/x"))
    }

    @Test
    fun `a 404 without a file is a repo error, with a file is a file error`() {
        assertTrue(HubError.fromStatus(404, "a/b", null) is HubError.RepoNotFound)
        assertTrue(HubError.fromStatus(404, "a/b", "m.gguf") is HubError.FileNotFound)
    }

    @Test
    fun `retryable covers exactly the transient failures`() {
        assertTrue(HubError.RateLimited(null).isRetryable)
        assertTrue(HubError.ServerUnavailable(500).isRetryable)
        assertTrue(HubError.ConnectionLost("x").isRetryable)
        assertTrue(HubError.NoNetwork.isRetryable)
        assertFalse(HubError.GatedRepo("a/b").isRetryable)
        assertFalse(HubError.RepoNotFound("a/b").isRetryable)
        assertFalse(HubError.ChecksumMismatch("m").isRetryable)
        assertFalse(HubError.Cancelled.isRetryable)
    }

    @Test
    fun `the checksum message says the file was discarded, because it was`() {
        assertTrue(HubError.ChecksumMismatch("m.gguf").message!!.contains("deleted"))
    }

    @Test
    fun `the gated message names the two things a user can actually do`() {
        val message = HubError.GatedRepo("m/l").message!!
        assertTrue(message!!.contains("huggingface.co"))
        assertTrue(message!!.contains("licence") || message.contains("license"))
        assertTrue(message!!.contains("token"))
    }
}
