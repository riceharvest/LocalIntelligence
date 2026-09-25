package dev.localintelligence.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rejection path: what a device can and cannot hold, and the message it
 * gets told *before* a byte moves.
 */
class FitGateTest {

    private val roomyRam = FakeBudget(availableRam = 8L * 1024 * 1024 * 1024, freeDisk = 64L * 1024 * 1024 * 1024)
    private val tightRam = FakeBudget(availableRam = 2L * 1024 * 1024 * 1024, freeDisk = 64L * 1024 * 1024 * 1024)
    private val noDisk = FakeBudget(availableRam = 8L * 1024 * 1024 * 1024, freeDisk = 100L * 1024 * 1024)

    private fun repo() = (HubRepoId.parse("bartowski/Qwen2.5-3B-Instruct-GGUF") as HubRepoId.Result.Valid).repoId

    private fun file(name: String, bytes: Long, quant: GgufQuant? = GgufQuant.Q4_K_M) = HubGgufFile(
        repo = repo(),
        path = (HubFilePath.parse(name) as HubFilePath.Result.Valid).filePath,
        sizeBytes = bytes,
        sha256 = null,
        quant = quant,
        shardIndex = null,
        shardCount = null,
    )

    // ---- quant selection ----------------------------------------------

    @Test
    fun `picks the largest quant that fits`() {
        val candidates = listOf(
            file("m-Q2_K.gguf", 1_123_021_000L, GgufQuant.Q2_K),
            file("m-Q4_K_M.gguf", 1_928_000_000L, GgufQuant.Q4_K_M),
            file("m-Q8_0.gguf", 3_284_000_000L, GgufQuant.Q8_0),
        )
        // 3.5 GB of usable RAM sits between the Q4_K_M and Q8_0 estimates.
        val midRam = FakeBudget(availableRam = 3_500_000_000L, freeDisk = 64L * 1024 * 1024 * 1024)
        val chosen = HuggingFaceClient(FakeTransport { error("no network in a fit test") })
            .chooseBestFitting(candidates, contextLength = 4_096, budget = midRam)
        assertNotNull(chosen)
        // Q8_0 (3.3 GB of weights plus KV and runtime) does not fit; Q4_K_M
        // does. Picking the *smallest* instead would ship a visibly worse model
        // than the hardware allows.
        assertEquals(GgufQuant.Q4_K_M, chosen!!.quant)

        // The same candidates against a 2 GiB device must fall back to the
        // smallest file, because a 1.9 GB Q4_K_M genuinely does not fit once
        // the KV cache and runtime buffer are counted. That refusal is the RAM
        // gate doing its job, not a bug.
        val smallOnly = HuggingFaceClient(FakeTransport { error("no network") })
            .chooseBestFitting(candidates, contextLength = 4_096, budget = tightRam)
        assertEquals(GgufQuant.Q2_K, smallOnly!!.quant)
    }

    @Test
    fun `picks q8_0 when the device has the headroom`() {
        val candidates = listOf(
            file("m-Q4_K_M.gguf", 1_928_000_000L, GgufQuant.Q4_K_M),
            file("m-Q8_0.gguf", 3_284_000_000L, GgufQuant.Q8_0),
        )
        val chosen = HuggingFaceClient(FakeTransport { error("no network") })
            .chooseBestFitting(candidates, contextLength = 4_096, budget = roomyRam)
        assertEquals(GgufQuant.Q8_0, chosen!!.quant)
    }

    @Test
    fun `returns null when nothing fits, which is the rejection trigger`() {
        val candidates = listOf(file("m-Q8_0.gguf", 3_284_000_000L, GgufQuant.Q8_0))
        val chosen = HuggingFaceClient(FakeTransport { error("no network") })
            .chooseBestFitting(candidates, contextLength = 8_192, budget = tightRam)
        assertNull(chosen)
    }

    @Test
    fun `never picks a sharded file as a single-file model`() {
        val sharded = HubGgufFile(
            repo = repo(),
            path = (HubFilePath.parse("m-00001-of-00004.gguf") as HubFilePath.Result.Valid).filePath,
            sizeBytes = 1_000_000L,
            sha256 = null,
            quant = GgufQuant.Q8_0,
            shardIndex = 1,
            shardCount = 4,
        )
        val client = HuggingFaceClient(FakeTransport { error("no network") })
        assertNull(client.chooseBestFitting(listOf(sharded), 4_096, roomyRam))
        assertTrue(client.loadableFiles(listOf(sharded)).isEmpty())
    }

    @Test
    fun `a file with no parsed quant is still selectable by size`() {
        val opaque = file("model-gguf", 1_000_000L, quant = null)
        val chosen = HuggingFaceClient(FakeTransport { error("no network") })
            .chooseBestFitting(listOf(opaque), 2_048, roomyRam)
        assertNotNull(chosen)
    }

    // ---- the ram verdict ----------------------------------------------

    @Test
    fun `a model that does not fit is rejected with an actionable message`() {
        val plan = HuggingFaceClient(FakeTransport { error("no") })
            .plan(file("m-Q8_0.gguf", 3_284_000_000L, GgufQuant.Q8_0), contextLength = 8_192, budget = tightRam)
        assertFalse(plan.isAllowed)
        assertNotNull(plan.reject)
        val message = plan.reject!!.message
        assertTrue(message.contains("memory"))
        // The message must point at the two levers the user actually has.
        assertTrue("should mention a smaller quant: $message", message.contains("quantization"))
        assertTrue("should mention context: $message", message.contains("context"))
        assertTrue("must be short: $message", message.length < 250)
    }

    @Test
    fun `a model that fits is allowed and reports the estimate`() {
        val plan = HuggingFaceClient(FakeTransport { error("no") })
            .plan(file("m-Q4_K_M.gguf", 1_928_000_000L, GgufQuant.Q4_K_M), contextLength = 4_096, budget = roomyRam)
        assertTrue(plan.isAllowed)
        assertNull(plan.reject)
        assertTrue(plan.ram.fits)
        assertTrue(plan.ram.explanation.contains("available"))
    }

    @Test
    fun `the estimate grows with context length because the kv cache does`() {
        val short = FitGate.ramFit(2_000_000_000L, GgufQuant.Q4_K_M, 1_024, roomyRam).totalBytes
        val long = FitGate.ramFit(2_000_000_000L, GgufQuant.Q4_K_M, 32_768, roomyRam).totalBytes
        assertTrue("kv must scale: $short vs $long", long > short * 2)
    }

    @Test
    fun `a bigger quant is never estimated as smaller than a smaller one`() {
        val q4 = FitGate.ramFit(2_000_000_000L, GgufQuant.Q4_K_M, 4_096, roomyRam).totalBytes
        val q8 = FitGate.ramFit(2_000_000_000L, GgufQuant.Q8_0, 4_096, roomyRam).totalBytes
        assertTrue("q8 must not estimate below q4: $q4 vs $q8", q8 >= q4)
    }

    @Test
    fun `weights dominate so the file size is honoured`() {
        val small = FitGate.ramFit(1_000_000_000L, GgufQuant.Q4_K_M, 4_096, roomyRam).totalBytes
        val large = FitGate.ramFit(4_000_000_000L, GgufQuant.Q4_K_M, 4_096, roomyRam).totalBytes
        // The estimate is never below the file, and a 3 GB larger file produces
        // a decisively larger estimate. An under-estimate is the one error that
        // gets a phone OOM-killed mid-conversation.
        assertTrue(small >= 1_000_000_000L)
        assertTrue(large >= 4_000_000_000L)
        assertTrue("a 3 GB larger file must move the estimate decisively", large - small > 2_500_000_000L)
    }

    @Test
    fun `the decision factor demands headroom over the raw estimate`() {
        val budget = FakeBudget(availableRam = 2_400_000_000L, freeDisk = 64L * 1024 * 1024 * 1024)
        val raw = PreDownloadMemoryModel.estimate(1_900_000_000L, GgufQuant.Q4_K_M, 4_096, 3_000_000_000L)
        val rawFits = raw <= budget.availableRamBytes()
        val gatedFits = FitGate.ramFit(1_900_000_000L, GgufQuant.Q4_K_M, 4_096, budget).fits
        // The gate must be STRICTER than the raw estimate: a device that only
        // just fits the raw number is still refused. If this ever flips, the
        // 1.15 factor is being dropped somewhere.
        assertTrue("fixture must place the device between raw and gated", rawFits)
        assertFalse("the decision factor was not applied", gatedFits)
    }

    // ---- the disk gate -------------------------------------------------

    @Test
    fun `insufficient disk is rejected with how much is needed and available`() {
        val plan = HuggingFaceClient(FakeTransport { error("no") })
            .plan(file("m-Q4_K_M.gguf", 1_928_000_000L), contextLength = 4_096, budget = noDisk)
        assertFalse(plan.isAllowed)
        val error = plan.reject as HubError.InsufficientStorage
        val headroom = maxOf((1_928_000_000L * FitGate.DISK_HEADROOM_RATIO).toLong(), FitGate.DISK_HEADROOM_FLOOR)
        assertEquals(1_928_000_000L + headroom, error.neededBytes)
        assertEquals(100L * 1024 * 1024, error.availableBytes)
        assertTrue(error.message!!.contains("Delete a model"))
    }

    @Test
    fun `disk headroom is required so a transfer cannot hit ENOSPC halfway`() {
        val budget = FakeBudget(availableRam = 8L * 1024 * 1024 * 1024, freeDisk = 2_000_000_000L)
        // The file itself would fit in 1.93 GB, but not with headroom.
        val error = FitGate.diskFit(1_928_000_000L, alreadyOnDisk = 0, budget = budget)
        assertNotNull("headroom must be demanded", error)
        assertTrue(error is HubError.InsufficientStorage)
    }

    @Test
    fun `a resumed download only needs room for the bytes still missing`() {
        val budget = FakeBudget(availableRam = 8L * 1024 * 1024 * 1024, freeDisk = 200_000_000L)
        val error = FitGate.diskFit(1_928_000_000L, alreadyOnDisk = 1_900_000_000L, budget = budget)
        assertNull("1.9 GB of the file is already on disk", error)
    }

    @Test
    fun `ram is checked before disk so the more useful message wins`() {
        val bothBad = FakeBudget(availableRam = 500_000_000L, freeDisk = 1_000L)
        val plan = HuggingFaceClient(FakeTransport { error("no") })
            .plan(file("m-Q8_0.gguf", 3_284_000_000L, GgufQuant.Q8_0), contextLength = 8_192, budget = bothBad)
        assertTrue("must blame RAM first, got ${plan.reject}", plan.reject is HubError.InvalidInput)
    }

    // ---- the memory model itself ---------------------------------------

    @Test
    fun `layer and hidden estimates track the published architectures`() {
        // Real block_count values for the families a phone can actually run.
        assertEquals(36, PreDownloadMemoryModel.layersFor(3_000_000_000L))
        assertEquals(32, PreDownloadMemoryModel.layersFor(7_000_000_000L))
        assertEquals(48, PreDownloadMemoryModel.layersFor(13_000_000_000L))
        assertEquals(24, PreDownloadMemoryModel.layersFor(500_000_000L))
    }

    @Test
    fun `an unknown parameter count is estimated conservatively, not optimistically`() {
        val unknown = PreDownloadMemoryModel.estimate(1_900_000_000L, GgufQuant.Q4_K_M, 4_096, null)
        val known0_5b = PreDownloadMemoryModel.estimate(1_900_000_000L, GgufQuant.Q4_K_M, 4_096, 500_000_000L)
        assertTrue("unknown must not estimate below a small model: $unknown vs $known0_5b", unknown > known0_5b)
    }

    @Test
    fun `the runtime floor is included even for a tiny file`() {
        val estimate = PreDownloadMemoryModel.estimate(1_000_000L, GgufQuant.Q4_K_M, 2_048, 1_000_000_000L)
        assertTrue(estimate > PreDownloadMemoryModel.RUNTIME_FLOOR_BYTES)
    }

    @Test
    fun `a parameter count in a file name is read back`() {
        assertEquals(3_000_000_000L, HuggingFaceClient.parseParameterCount("Qwen2.5-3B-Instruct-Q4_K_M.gguf"))
        assertEquals(500_000_000L, HuggingFaceClient.parseParameterCount("qwen2.5-0.5b-instruct-q4_k_m.gguf"))
        assertEquals(70_000_000_000L, HuggingFaceClient.parseParameterCount("Llama-3.3-70B-Q4_K_M.gguf"))
        assertNull(HuggingFaceClient.parseParameterCount("model.gguf"))
    }

    // ---- formatting ----------------------------------------------------

    @Test
    fun `byte sizes format the way a phone storage screen does`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.0 kB", formatBytes(1_000))
        assertEquals("1.9 GB", formatBytes(1_928_000_000L))
        assertEquals("132 GB", formatBytes(132_000_000_000L))
    }
}
