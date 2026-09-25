package dev.localintelligence.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backend selection, exercised entirely on the JVM.
 *
 * The device this logic exists for cannot be produced from a test — a phone with
 * a working NPU delegate, and a phone without one, are the two cases that matter
 * and neither is reachable without hardware. So the probe is injected as a set
 * of [AcceleratorStatus] values and the selection rules are tested directly. What
 * is *not* covered here, and cannot be, is whether the real probe on the real
 * device answers correctly: that is stated in docs/acceleration.md rather than
 * implied by a green test run.
 */
class AcceleratorSelectorTest {

    private fun status(
        kind: AcceleratorKind,
        available: Boolean,
        detail: String = if (available) {
            "${kind.name} is usable"
        } else {
            "${kind.name} unavailable: no delegate library on this device"
        },
    ) = AcceleratorStatus(kind = kind, available = available, detail = detail)

    private fun probeOf(vararg available: AcceleratorKind) = AcceleratorProbe { kind ->
        status(kind, kind in available)
    }

    private fun resolve(
        preference: AcceleratorPreference,
        vararg available: AcceleratorKind,
    ) = AcceleratorSelector.resolve(preference, probeOf(*available))

    // ================================================================== AUTO

    @Test
    fun `AUTO on a device with everything picks NPU`() {
        val report = resolve(AcceleratorPreference.AUTO, AcceleratorKind.CPU, AcceleratorKind.GPU, AcceleratorKind.NPU)
        assertEquals(AcceleratorKind.NPU, report.active)
    }

    @Test
    fun `AUTO on a GPU-only device picks GPU and says the NPU was unavailable`() {
        val report = resolve(AcceleratorPreference.AUTO, AcceleratorKind.CPU, AcceleratorKind.GPU)
        assertEquals(AcceleratorKind.GPU, report.active)
        // A degrade from the ideal: NPU was the target, so a reason is owed even
        // though GPU is genuinely the best hardware present.
        assertNotNull(report.fallbackReason)
        assertTrue(report.fallbackReason!!.contains("NPU"))
        assertTrue(report.fallbackReason!!.contains("using GPU instead"))
    }

    @Test
    fun `AUTO on a CPU-only device picks CPU rather than failing`() {
        val report = resolve(AcceleratorPreference.AUTO, AcceleratorKind.CPU)
        assertEquals(AcceleratorKind.CPU, report.active)
        assertNotNull(report.fallbackReason)
        assertTrue(report.fallbackReason!!.contains("using CPU instead"))
    }

    @Test
    fun `AUTO with no accelerator at all still resolves`() {
        // The pathological probe: knows nothing. Selection must be total.
        val report = AcceleratorSelector.resolve(AcceleratorPreference.AUTO, AcceleratorProbe { null })
        assertEquals(AcceleratorKind.CPU, report.active)
        assertNotNull(report.fallbackReason)
    }

    @Test
    fun `AUTO delivers NPU with no fallback reason when the device has one`() {
        // The one case where "degraded" would be a lie: nothing was given up.
        val report = resolve(AcceleratorPreference.AUTO, AcceleratorKind.CPU, AcceleratorKind.GPU, AcceleratorKind.NPU)
        assertNull(report.fallbackReason)
        assertFalse(report.degraded)
    }

    // =========================================================== explicit CPU

    @Test
    fun `explicit CPU is honoured even when GPU is available`() {
        // A pinned CPU is a baseline for a tok/s comparison. Quietly promoting it
        // to GPU would make the number meaningless.
        val report = resolve(AcceleratorPreference.CPU, AcceleratorKind.CPU, AcceleratorKind.GPU, AcceleratorKind.NPU)
        assertEquals(AcceleratorKind.CPU, report.active)
        assertNull(report.fallbackReason)
    }

    @Test
    fun `explicit CPU never claims to be a degrade`() {
        val report = resolve(AcceleratorPreference.CPU, AcceleratorKind.CPU)
        assertFalse(report.degraded)
    }

    // =========================================================== explicit GPU

    @Test
    fun `explicit GPU is honoured when available`() {
        val report = resolve(AcceleratorPreference.GPU, AcceleratorKind.CPU, AcceleratorKind.GPU)
        assertEquals(AcceleratorKind.GPU, report.active)
        assertNull(report.fallbackReason)
    }

    @Test
    fun `explicit GPU on a device with no GPU falls back to CPU and explains`() {
        val report = resolve(AcceleratorPreference.GPU, AcceleratorKind.CPU)
        assertEquals(AcceleratorKind.CPU, report.active)
        assertTrue(report.degraded)
        assertNotNull(report.fallbackReason)
        assertTrue(report.fallbackReason!!.contains("GPU"))
        assertTrue(report.fallbackReason!!.contains("using CPU instead"))
    }

    @Test
    fun `explicit GPU does not consult the NPU`() {
        // GPU was asked for. An NPU is faster, but it is not what was asked for.
        val probeCalls = mutableListOf<AcceleratorKind>()
        AcceleratorSelector.resolve(
            AcceleratorPreference.GPU,
            AcceleratorProbe { kind ->
                probeCalls += kind
                status(kind, kind in setOf(AcceleratorKind.CPU, AcceleratorKind.GPU, AcceleratorKind.NPU))
            },
        )
        assertEquals(listOf(AcceleratorKind.GPU, AcceleratorKind.CPU), probeCalls)
    }

    // =========================================================== explicit NPU

    @Test
    fun `explicit NPU on a device with no NPU falls back to GPU instead of crashing`() {
        val report = resolve(AcceleratorPreference.NPU, AcceleratorKind.CPU, AcceleratorKind.GPU)
        assertEquals(AcceleratorKind.GPU, report.active)
        assertNotNull(report.fallbackReason)
        assertTrue(report.fallbackReason!!.contains("NPU unavailable"))
        assertTrue(report.fallbackReason!!.contains("using GPU instead"))
    }

    @Test
    fun `explicit NPU on a device with neither NPU nor GPU lands on CPU`() {
        val report = resolve(AcceleratorPreference.NPU, AcceleratorKind.CPU)
        assertEquals(AcceleratorKind.CPU, report.active)
        assertTrue(report.fallbackReason!!.contains("using CPU instead"))
    }

    @Test
    fun `a failed NPU probe is treated as unavailable, not as available`() {
        // A probe that could not answer must not be read as permission.
        val report = AcceleratorSelector.resolve(
            AcceleratorPreference.NPU,
            AcceleratorProbe { kind ->
                if (kind == AcceleratorKind.NPU) {
                    AcceleratorStatus(
                        kind = kind,
                        available = false,
                        detail = "NPU unavailable: the probe could not read the native library dir",
                        probeFailed = true,
                    )
                } else {
                    status(kind, kind == AcceleratorKind.CPU)
                }
            },
        )
        assertEquals(AcceleratorKind.CPU, report.active)
        assertTrue(report.fallbackReason!!.contains("could not read the native library dir"))
    }

    @Test
    fun `the fallback reason names every tier that was passed over`() {
        // Both NPU and GPU were declined. Reporting only the first would leave the
        // user thinking the GPU had worked.
        val report = AcceleratorSelector.resolve(
            AcceleratorPreference.NPU,
            AcceleratorProbe { kind ->
                if (kind == AcceleratorKind.CPU) {
                    status(kind, true)
                } else {
                    AcceleratorStatus(
                        kind = kind,
                        available = false,
                        detail = "${kind.name} unavailable: missing lib${kind.name.lowercase()}.so",
                    )
                }
            },
        )
        assertEquals(AcceleratorKind.CPU, report.active)
        assertTrue(report.fallbackReason!!.contains("NPU unavailable: missing libnpu.so"))
        assertTrue(report.fallbackReason!!.contains("GPU unavailable: missing libgpu.so"))
    }

    @Test
    fun `a missing library is quoted verbatim in the reason`() {
        // GPU is available here, so the only thing quoted back is the NPU's
        // evidence -- which is the point: the user is told which file to look for.
        val report = AcceleratorSelector.resolve(
            AcceleratorPreference.NPU,
            AcceleratorProbe { kind ->
                if (kind == AcceleratorKind.NPU) {
                    AcceleratorStatus(
                        kind = kind,
                        available = false,
                        detail = "NPU unavailable: missing libqnn.so, no delegate in " +
                            "/data/app/~~a==/lib/arm64",
                    )
                } else {
                    status(kind, kind == AcceleratorKind.GPU)
                }
            },
        )
        assertEquals(AcceleratorKind.GPU, report.active)
        assertEquals("NPU unavailable: missing libqnn.so, no delegate in " +
            "/data/app/~~a==/lib/arm64; using GPU instead", report.fallbackReason)
    }

    // ============================================================== reporting

    @Test
    fun `the chosen backend is reported so a benchmark can label the number`() {
        // This is the field the tok/s comparison reads. If it is wrong, a GPU
        // number gets filed under CPU and the whole exercise is worthless.
        val report = resolve(AcceleratorPreference.AUTO, AcceleratorKind.CPU, AcceleratorKind.GPU)
        assertEquals(AcceleratorKind.GPU, report.active)
        assertEquals(AcceleratorPreference.AUTO, report.requested)
        assertEquals(AcceleratorKind.GPU, report.statuses.getValue(AcceleratorKind.GPU).kind)
    }

    @Test
    fun `the report carries per-tier evidence for every candidate`() {
        val report = resolve(AcceleratorPreference.AUTO, AcceleratorKind.CPU)
        // A support question about "why was my NPU not used" is answered from here
        // without a logcat read.
        assertTrue(report.statuses.containsKey(AcceleratorKind.NPU))
        assertTrue(report.statuses.containsKey(AcceleratorKind.GPU))
        assertTrue(report.statuses.containsKey(AcceleratorKind.CPU))
    }

    @Test
    fun `describe names the active hardware and the reason`() {
        val report = resolve(AcceleratorPreference.NPU, AcceleratorKind.CPU, AcceleratorKind.GPU)
        val text = report.describe()
        assertTrue(text.startsWith("GPU"))
        assertTrue(text.contains("npu"))
    }

    @Test
    fun `describe of a clean run still says what was requested`() {
        val report = resolve(AcceleratorPreference.CPU, AcceleratorKind.CPU, AcceleratorKind.GPU)
        assertEquals("CPU (requested cpu)", report.describe())
    }

    // ============================================================ edge cases

    @Test
    fun `a backend that reports nothing degrades to UNKNOWN without throwing`() {
        val backend: ModelBackend = NoopModelBackend()
        assertEquals(AcceleratorReport.UNKNOWN, backend.acceleratorReport)
    }

    @Test
    fun `acceleratorReport reads through the interface for an aware backend`() {
        val expected = resolve(AcceleratorPreference.AUTO, AcceleratorKind.CPU, AcceleratorKind.GPU)
        val backend: ModelBackend = FixedAcceleratorBackend(expected)
        assertEquals(expected, backend.acceleratorReport)
    }

    @Test
    fun `UNKNOWN describes itself as CPU without claiming a degrade`() {
        assertEquals(AcceleratorKind.CPU, AcceleratorReport.UNKNOWN.active)
        assertNull(AcceleratorReport.UNKNOWN.fallbackReason)
        assertFalse(AcceleratorReport.UNKNOWN.degraded)
    }

    @Test
    fun `a probe reporting a failed CPU probe still resolves to CPU`() {
        // Safety net: a broken probe must not become an unhandled error at load.
        val report = AcceleratorSelector.resolve(
            AcceleratorPreference.AUTO,
            AcceleratorProbe { kind ->
                AcceleratorStatus(
                    kind = kind,
                    available = false,
                    detail = "${kind.name} unavailable: probe failed",
                    probeFailed = true,
                )
            },
        )
        assertEquals(AcceleratorKind.CPU, report.active)
        assertNotNull(report.fallbackReason)
    }

    @Test
    fun `a status with a blank detail is rejected at construction`() {
        // A blank detail renders as an empty row in the UI, which is how a
        // fallback ends up looking like a crash.
        val error = runCatching {
            AcceleratorStatus(AcceleratorKind.NPU, available = false, detail = "   ")
        }.exceptionOrNull()
        assertNotNull(error)
    }
}

/** The test matrix's "garbage backend string" cases. */
class AcceleratorParsingTest {

    @Test
    fun `every accelerator id round-trips`() {
        AcceleratorKind.entries.forEach {
            assertEquals(it, AcceleratorKind.fromId(it.id))
        }
    }

    @Test
    fun `every preference id round-trips`() {
        AcceleratorPreference.entries.forEach {
            assertEquals(it, AcceleratorPreference.fromId(it.id))
        }
    }

    @Test
    fun `ids parse case-insensitively and tolerate surrounding whitespace`() {
        // These come out of a config file or a CLI flag, not a sealed set.
        assertEquals(AcceleratorKind.GPU, AcceleratorKind.fromId("  GPU "))
        assertEquals(AcceleratorKind.NPU, AcceleratorKind.fromId("Npu"))
        assertEquals(AcceleratorPreference.AUTO, AcceleratorPreference.fromId(" Auto"))
    }

    @Test
    fun `an unrecognised accelerator id returns null rather than guessing`() {
        // Guessing here would silently turn a typo into CPU.
        assertNull(AcceleratorKind.fromId("tpu"))
        assertNull(AcceleratorKind.fromId(""))
        assertNull(AcceleratorKind.fromId("   "))
        assertNull(AcceleratorKind.fromId(null))
        assertNull(AcceleratorKind.fromId("gpu2"))
    }

    @Test
    fun `an unrecognised preference falls back to AUTO so a stale setting cannot brick load`() {
        // A renamed or downgraded preference must not stop a model loading.
        assertEquals(AcceleratorPreference.AUTO, AcceleratorPreference.parseOr("npu2"))
        assertEquals(AcceleratorPreference.AUTO, AcceleratorPreference.parseOr(null))
        assertEquals(AcceleratorPreference.AUTO, AcceleratorPreference.parseOr(""))
        assertEquals(AcceleratorPreference.AUTO, AcceleratorPreference.DEFAULT)
    }

    @Test
    fun `a non-parseable kind falls back to the caller's default`() {
        assertEquals(AcceleratorKind.CPU, AcceleratorKind.parseOr("tpu"))
        assertEquals(AcceleratorKind.GPU, AcceleratorKind.parseOr("tpu", fallback = AcceleratorKind.GPU))
        assertEquals(AcceleratorKind.NPU, AcceleratorKind.parseOr("npu"))
    }

    @Test
    fun `candidate orders always end at CPU`() {
        // The invariant that makes selection total.
        AcceleratorPreference.entries.forEach {
            assertEquals(AcceleratorKind.CPU, it.candidates().last())
        }
    }

    @Test
    fun `AUTO and NPU have the same candidate order but different intent`() {
        // Same ladder, different label: AUTO is "best present", NPU is "I want
        // NPU". They must not be collapsed, or the UI cannot tell a downgrade
        // from a default.
        assertEquals(
            AcceleratorPreference.NPU.candidates(),
            AcceleratorPreference.AUTO.candidates(),
        )
        assertEquals(AcceleratorKind.NPU, AcceleratorPreference.AUTO.idealKind)
        assertEquals(AcceleratorKind.NPU, AcceleratorPreference.NPU.idealKind)
    }

    @Test
    fun `ranking orders the tiers best first`() {
        assertTrue(AcceleratorKind.NPU.isFasterThan(AcceleratorKind.GPU))
        assertTrue(AcceleratorKind.GPU.isFasterThan(AcceleratorKind.CPU))
        assertFalse(AcceleratorKind.CPU.isFasterThan(AcceleratorKind.CPU))
    }
}

/** A backend that answers [ModelBackend.acceleratorReport] with a fixed value. */
private class FixedAcceleratorBackend(
    private val report: AcceleratorReport,
) : ModelBackend, AcceleratorAware {
    override val id: String = "fixed"
    override val capabilities: ModelCapabilities = ModelCapabilities.UNKNOWN
    override val acceleratorReport: AcceleratorReport get() = report
    override suspend fun load(model: ModelSpec) = Unit
    override suspend fun generate(request: GenerationRequest) =
        GenerationResult(text = "", stopReason = StopReason.COMPLETED)
    override suspend fun unload() = Unit
    override fun countTokens(text: String): Int = 0
    override fun cancel() = Unit
}
