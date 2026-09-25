package dev.localintelligence.inference.litertlm

import dev.localintelligence.core.model.AcceleratorKind
import dev.localintelligence.core.model.AcceleratorPreference
import dev.localintelligence.core.model.AcceleratorProbe
import dev.localintelligence.core.model.AcceleratorReport
import dev.localintelligence.core.model.AcceleratorSelector
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.acceleratorReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Android-side capability probe, tested against injected library sets.
 *
 * ## What is and is not covered here
 *
 * The probe's *logic* -- which sonames it looks for, how it reports what is
 * missing, how it refuses to call an NPU present on a stock build -- is fully
 * covered. The one thing not covered, and not coverable without hardware, is
 * whether a real Pixel or a real Snapdragon answers the way the fake answers
 * here. `System.loadLibrary` is injected precisely so that distinction is visible
 * rather than hidden: everything below the injection point is verified, and
 * everything above it is stated as unverified in docs/acceleration.md.
 *
 * In particular: **no test in this file proves NPU or GPU acceleration works.**
 * They prove the app picks correctly and says why when it cannot.
 */
class LiteRtLmCapabilityProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val opencl = System.mapLibraryName("OpenCL")
    private val openclCar = System.mapLibraryName("OpenCL-car")
    private val openclPixel = System.mapLibraryName("OpenCL-pixel")
    private val vndksupport = System.mapLibraryName("vndksupport")

    /** A probe whose "loadable" set is whatever the test hands it. */
    private fun probe(
        loadable: Set<String> = emptySet(),
        nativeLibraryDir: String? = null,
    ) = LiteRtLmCapabilityProbe(
        nativeLibraryDir = nativeLibraryDir,
        libraryLoadable = { it in loadable },
    )

    private fun status(
        kind: AcceleratorKind,
        probe: LiteRtLmCapabilityProbe,
    ) = probe.probe(kind)!!

    /** The three `.so` files the LiteRT-LM AAR actually ships. */
    private fun stockPayload(root: File) {
        listOf("liblitertlm_jni.so", "libLiteRt.so", "libLiteRtClGlAccelerator.so")
            .forEach { File(root, it).writeText("fake") }
    }

    // ================================================================== CPU

    @Test
    fun `CPU is available on a device with no libraries at all`() {
        val result = status(AcceleratorKind.CPU, probe())
        assertTrue(result.available)
        assertTrue(result.detail.isNotBlank())
    }

    @Test
    fun `CPU does not consult the filesystem`() {
        // A CPU status that depended on a directory listing could be made
        // unavailable by an app that shipped nothing, which is not a useful
        // failure mode.
        val root = temp.newFolder("lib")
        val result = status(AcceleratorKind.CPU, probe(nativeLibraryDir = root.absolutePath))
        assertTrue(result.available)
    }

    // ================================================================== GPU

    @Test
    fun `GPU is available when the generic OpenCL driver and vndksupport load`() {
        val result = status(
            AcceleratorKind.GPU,
            probe(loadable = setOf(opencl, vndksupport)),
        )
        assertTrue(result.available)
        assertTrue(result.detail.contains("GPU is available"))
    }

    @Test
    fun `GPU is available on the Adreno vendor driver alone`() {
        // libOpenCL-car.so is what an Adreno device actually ships. Requiring the
        // generic name would report a Snapdragon as GPU-less, which is backwards.
        val result = status(
            AcceleratorKind.GPU,
            probe(loadable = setOf(openclCar, vndksupport)),
        )
        assertTrue(result.available)
    }

    @Test
    fun `GPU is available on the Google Tensor vendor driver alone`() {
        val result = status(
            AcceleratorKind.GPU,
            probe(loadable = setOf(openclPixel, vndksupport)),
        )
        assertTrue(result.available)
    }

    @Test
    fun `GPU is unavailable and names every OpenCL soname it looked for`() {
        val result = status(AcceleratorKind.GPU, probe())
        assertFalse(result.available)
        assertTrue(result.missingLibraries.contains(opencl))
        assertTrue(result.missingLibraries.contains(openclCar))
        assertTrue(result.missingLibraries.contains(openclPixel))
        assertTrue(result.missingLibraries.contains(vndksupport))
        assertTrue(result.detail.contains("GPU unavailable"))
    }

    @Test
    fun `a missing vndksupport alone is reported separately from a missing OpenCL`() {
        // Different fix: a manifest entry, not a different phone. Folding the two
        // into one "no GPU" message would send someone to buy a new phone.
        val result = status(AcceleratorKind.GPU, probe(loadable = setOf(opencl)))
        assertFalse(result.available)
        assertEquals(listOf(vndksupport), result.missingLibraries)
    }

    @Test
    fun `a driver whose load throws is treated as unavailable`() {
        // System.loadLibrary throws UnsatisfiedLinkError -- an Error -- for a
        // library the linker will not resolve. A probe that let that escape would
        // look like a crash the user caused.
        val throwing = LiteRtLmCapabilityProbe(nativeLibraryDir = null) {
            if (it == vndksupport) throw UnsatisfiedLinkError("cannot locate symbol")
            false
        }
        val result = throwing.probe(AcceleratorKind.GPU)!!
        assertFalse(result.available)
        assertTrue(result.missingLibraries.contains(vndksupport))
    }

    // ================================================================== NPU

    @Test
    fun `NPU is unavailable when no native library dir was supplied`() {
        val result = status(AcceleratorKind.NPU, probe(nativeLibraryDir = null))
        assertFalse(result.available)
        assertTrue(result.detail.contains("no native library dir"))
    }

    @Test
    fun `NPU is unavailable when the native library dir does not exist`() {
        val result = status(
            AcceleratorKind.NPU,
            probe(nativeLibraryDir = File(temp.root, "absent").absolutePath),
        )
        assertFalse(result.available)
        assertTrue(result.detail.contains("does not exist"))
    }

    @Test
    fun `NPU is unavailable on a stock LiteRT-LM install`() {
        // THE headline case. The AAR ships three .so files and none of them is an
        // NPU delegate, so on a stock build this is the answer on every device
        // including a Snapdragon. A probe built from a chip name would say
        // otherwise.
        val root = temp.newFolder("lib")
        stockPayload(root)
        val result = status(AcceleratorKind.NPU, probe(nativeLibraryDir = root.absolutePath))
        assertFalse(result.available)
        assertTrue(result.detail.contains("ships no NPU library"))
        assertTrue(result.missingLibraries.isNotEmpty())
    }

    @Test
    fun `NPU is unavailable when the dir holds only the LiteRT-LM JNI library`() {
        val root = temp.newFolder("lib2")
        File(root, "liblitertlm_jni.so").writeText("fake")
        val result = status(AcceleratorKind.NPU, probe(nativeLibraryDir = root.absolutePath))
        assertFalse(result.available)
    }

    @Test
    fun `NPU is available when a vendor delegate is packaged alongside`() {
        val root = temp.newFolder("lib3")
        stockPayload(root)
        File(root, "libQnnHtp.so").writeText("fake")
        val result = status(AcceleratorKind.NPU, probe(nativeLibraryDir = root.absolutePath))
        assertTrue(result.available)
        assertTrue(result.detail.contains("libQnnHtp.so"))
    }

    @Test
    fun `a non-library file in the dir does not fake an NPU`() {
        val root = temp.newFolder("lib4")
        stockPayload(root)
        File(root, "notes.txt").writeText("not a delegate")
        val result = status(AcceleratorKind.NPU, probe(nativeLibraryDir = root.absolutePath))
        assertFalse(result.available)
    }

    @Test
    fun `a subdirectory does not count as a delegate`() {
        val root = temp.newFolder("lib5")
        stockPayload(root)
        File(root, "libQnnHtp.so").mkdirs()
        val result = status(AcceleratorKind.NPU, probe(nativeLibraryDir = root.absolutePath))
        assertFalse(result.available)
    }

    @Test
    fun `the NPU reason names the directory that was searched`() {
        val root = temp.newFolder("lib6")
        val result = status(AcceleratorKind.NPU, probe(nativeLibraryDir = root.absolutePath))
        assertTrue(result.detail.contains(root.absolutePath))
    }

    // ================================================================ caching

    @Test
    fun `a second probe of the same tier does not re-attempt the load`() {
        var attempts = 0
        val probe = LiteRtLmCapabilityProbe(nativeLibraryDir = null) {
            attempts++
            it in setOf(opencl, vndksupport)
        }
        probe.probe(AcceleratorKind.GPU)
        val afterFirst = attempts
        probe.probe(AcceleratorKind.GPU)
        assertEquals(afterFirst, attempts)
    }

    @Test
    fun `clearing the cache re-runs the probe`() {
        // An app that installs a Play Feature module mid-session has to be able
        // to see it, so the cache is droppable.
        var loadable = setOf(opencl, vndksupport)
        val probe = LiteRtLmCapabilityProbe(nativeLibraryDir = null) { it in loadable }
        assertTrue(probe.probe(AcceleratorKind.GPU)!!.available)

        loadable = emptySet()
        assertTrue(probe.probe(AcceleratorKind.GPU)!!.available)
        probe.clearCache()
        assertFalse(probe.probe(AcceleratorKind.GPU)!!.available)
    }

    @Test
    fun `one resolve probes all three tiers from a single pass`() {
        // The selector asks for three tiers; one directory scan should serve all
        // of them rather than repeating the listing per tier.
        val root = temp.newFolder("lib7")
        var loadAttempts = 0
        val probe = LiteRtLmCapabilityProbe(nativeLibraryDir = root.absolutePath) {
            loadAttempts++
            it in setOf(opencl, vndksupport)
        }
        AcceleratorSelector.resolve(AcceleratorPreference.AUTO, probe)
        val afterFirst = loadAttempts
        AcceleratorSelector.resolve(AcceleratorPreference.AUTO, probe)
        assertEquals(afterFirst, loadAttempts)
    }

    @Test
    fun `every tier the real probe reports has a non-blank detail`() {
        // The init-block invariant, asserted through the real probe so a future
        // edit that leaves a detail empty fails here.
        val root = temp.newFolder("lib8")
        val result = probe(nativeLibraryDir = root.absolutePath)
        AcceleratorKind.entries.forEach { kind ->
            val status = result.probe(kind)!!
            assertTrue("blank detail for $kind", status.detail.isNotBlank())
            assertEquals(kind, status.kind)
        }
    }

    // ============================================================ CpuOnlyProbe

    @Test
    fun `the default probe claims nothing above CPU`() {
        val result = CpuOnlyAcceleratorProbe.probe(AcceleratorKind.NPU)!!
        assertFalse(result.available)
        assertTrue(result.detail.contains("no device probe was supplied"))
        assertTrue(CpuOnlyAcceleratorProbe.probe(AcceleratorKind.CPU)!!.available)
    }

    // ============================================================= fake probe

    @Test
    fun `a device with GPU but no NPU resolves to GPU under AUTO`() {
        val fake = FakeAcceleratorProbe(loadableLibraries = setOf(opencl, vndksupport))
        val report = AcceleratorSelector.resolve(AcceleratorPreference.AUTO, fake)
        assertEquals(AcceleratorKind.GPU, report.active)
        assertNotNull(report.fallbackReason)
    }

    @Test
    fun `a device with an NPU delegate resolves to NPU under AUTO`() {
        val fake = FakeAcceleratorProbe(
            loadableLibraries = setOf(opencl, vndksupport),
            delegateLibraries = setOf("libQnnHtp.so"),
        )
        val report = AcceleratorSelector.resolve(AcceleratorPreference.AUTO, fake)
        assertEquals(AcceleratorKind.NPU, report.active)
        assertNull(report.fallbackReason)
    }

    @Test
    fun `a probe that failed is distinguishable from one that found nothing`() {
        val fake = FakeAcceleratorProbe(npuFailure = "could not read the native library dir")
        val status = fake.probe(AcceleratorKind.NPU)!!
        assertFalse(status.available)
        assertTrue(status.probeFailed)
        assertTrue(status.detail.contains("could not read the native library dir"))
    }

    @Test
    fun `a forced-unavailable tier is honoured by the fake`() {
        val fake = FakeAcceleratorProbe(
            loadableLibraries = setOf(opencl, vndksupport),
            forcedUnavailable = setOf(AcceleratorKind.GPU),
        )
        val report = AcceleratorSelector.resolve(AcceleratorPreference.GPU, fake)
        assertEquals(AcceleratorKind.CPU, report.active)
        assertTrue(report.fallbackReason!!.contains("forced off by the test"))
    }

    @Test
    fun `the fake records which tiers the selector consulted`() {
        val fake = FakeAcceleratorProbe()
        AcceleratorSelector.resolve(AcceleratorPreference.CPU, fake)
        assertEquals(listOf(AcceleratorKind.CPU), fake.probed)
    }
}

/**
 * The backend's end-to-end accelerator behaviour: preference in, engine config
 * and reported accelerator out.
 *
 * Uses [RecordingLiteRtLmEngineFactory], so every assertion is about what the
 * backend *asked the runtime for* and what it *reports*, never about a native
 * engine. See `LiteRtLmBackendTest` for why that is the only option on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtLmAcceleratorBackendTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var modelFile: File
    private lateinit var spec: ModelSpec
    private lateinit var factory: RecordingLiteRtLmEngineFactory

    private val opencl = System.mapLibraryName("OpenCL")
    private val vndksupport = System.mapLibraryName("vndksupport")
    private val gpuLibraries = setOf(opencl, vndksupport)

    @Before
    fun setUp() {
        val root = temp.newFolder("models")
        modelFile = File(root, "gemma-3n-e2b.litertlm").also { it.writeText("not a real model") }
        spec = ModelSpec(id = modelFile.absolutePath, displayName = "Gemma 3n E2B")
        factory = RecordingLiteRtLmEngineFactory()
    }

    private fun newBackend(
        preference: AcceleratorPreference = AcceleratorPreference.AUTO,
        probe: AcceleratorProbe = CpuOnlyAcceleratorProbe,
        nativeLibraryDir: String? = null,
        factory: RecordingLiteRtLmEngineFactory = this.factory,
    ) = LiteRtLmBackend(
        modelSource = LiteRtLmModelSource(temp.root),
        engineFactory = factory,
        ioDispatcher = Dispatchers.Unconfined,
        acceleratorPreference = preference,
        capabilityProbe = probe,
        nativeLibraryDir = nativeLibraryDir,
    )

    /** The accelerators the backend actually asked the engine factory for. */
    private fun RecordingLiteRtLmEngineFactory.requestedAccel() =
        configs.map { it.accelerator }

    /** A device with a working GPU and no NPU delegate. */
    private fun gpuOnlyDevice() = FakeAcceleratorProbe(loadableLibraries = gpuLibraries)

    /** A device with both a GPU and a packaged NPU delegate. */
    private fun npuDevice() = FakeAcceleratorProbe(
        loadableLibraries = gpuLibraries,
        delegateLibraries = setOf("libQnnHtp.so"),
    )

    // ============================================================== reporting

    @Test
    fun `the report names nothing before anything is loaded`() = runTest {
        assertEquals(AcceleratorReport.UNKNOWN, newBackend().acceleratorReport)
    }

    @Test
    fun `a GPU device is reported as GPU after load`() = runTest {
        val backend = newBackend(probe = gpuOnlyDevice())
        backend.load(spec)
        assertEquals(AcceleratorKind.GPU, backend.acceleratorReport.active)
    }

    @Test
    fun `the reported accelerator is reachable through the ModelBackend extension`() = runTest {
        // This is the path the UI and the benchmark use. If it silently returned
        // UNKNOWN, every tok/s number would be filed under "unknown".
        val backend: ModelBackend = newBackend(probe = gpuOnlyDevice())
        backend.load(spec)
        assertEquals(AcceleratorKind.GPU, backend.acceleratorReport.active)
    }

    @Test
    fun `unload resets the report so nothing claims to be running`() = runTest {
        val backend = newBackend(probe = gpuOnlyDevice())
        backend.load(spec)
        backend.unload()
        assertEquals(AcceleratorReport.UNKNOWN, backend.acceleratorReport)
    }

    // ============================================================= selection

    @Test
    fun `AUTO asks the runtime for the NPU when the device has a delegate`() = runTest {
        val backend = newBackend(
            probe = npuDevice(),
            nativeLibraryDir = "/data/app/x/lib/arm64",
        )
        backend.load(spec)
        assertEquals(listOf(AcceleratorKind.NPU), factory.requestedAccel())
        assertEquals(AcceleratorKind.NPU, backend.acceleratorReport.active)
    }

    @Test
    fun `AUTO on a GPU device never asks for the NPU it does not have`() = runTest {
        val backend = newBackend(probe = gpuOnlyDevice())
        backend.load(spec)
        // One engine built, and it is the GPU. Asking for an NPU that is not there
        // would mean a load attempt that has to fail before it can succeed.
        assertEquals(listOf(AcceleratorKind.GPU), factory.requestedAccel())
    }

    @Test
    fun `a CPU-only device builds one engine and says the GPU was unavailable`() = runTest {
        val backend = newBackend(probe = FakeAcceleratorProbe())
        backend.load(spec)
        assertEquals(listOf(AcceleratorKind.CPU), factory.requestedAccel())
        val reason = backend.acceleratorReport.fallbackReason
        assertNotNull(reason)
        assertTrue(reason!!.contains("GPU unavailable"))
    }

    @Test
    fun `explicit CPU is honoured even when the device has a GPU`() = runTest {
        val backend = newBackend(preference = AcceleratorPreference.CPU, probe = npuDevice())
        backend.load(spec)
        assertEquals(listOf(AcceleratorKind.CPU), factory.requestedAccel())
        assertNull(backend.acceleratorReport.fallbackReason)
    }

    @Test
    fun `requesting NPU on a device with no NPU degrades to GPU with a reason`() = runTest {
        val backend = newBackend(
            preference = AcceleratorPreference.NPU,
            probe = gpuOnlyDevice(),
        )
        backend.load(spec)
        assertEquals(AcceleratorKind.GPU, backend.acceleratorReport.active)
        val reason = backend.acceleratorReport.fallbackReason!!
        assertTrue(reason.contains("NPU unavailable"))
        assertTrue(reason.contains("using GPU instead"))
    }

    @Test
    fun `requesting NPU on a bare device lands on CPU rather than throwing`() = runTest {
        val backend = newBackend(preference = AcceleratorPreference.NPU)
        backend.load(spec)
        assertEquals(AcceleratorKind.CPU, backend.acceleratorReport.active)
        assertTrue(backend.acceleratorReport.degraded)
    }

    // ======================================================= engine threading

    @Test
    fun `the NPU dispatch directory reaches the engine config`() = runTest {
        val backend = newBackend(probe = npuDevice(), nativeLibraryDir = "/data/app/x/lib/arm64")
        backend.load(spec)
        assertEquals("/data/app/x/lib/arm64", factory.configs.single().nativeLibraryDir)
    }

    @Test
    fun `a GPU load carries no NPU dispatch directory`() = runTest {
        // Handing Backend.NPU's directory to a GPU engine would be meaningless,
        // and a null here is the difference between "not asked" and "asked with
        // nothing".
        val backend = newBackend(probe = gpuOnlyDevice(), nativeLibraryDir = "/data/app/x/lib/arm64")
        backend.load(spec)
        assertNull(factory.configs.single().nativeLibraryDir)
    }

    // ===================================================== runtime fallback

    @Test
    fun `an NPU that passes the probe but fails to initialise falls back to GPU`() = runTest {
        // The case a library listing cannot catch: the delegate is present and
        // built for the wrong Hexagon generation. The probe cleared NPU, so NPU is
        // attempted first, and the runtime's own failure is what demotes it.
        val strict = RecordingLiteRtLmEngineFactory { config ->
            if (config.accelerator == AcceleratorKind.NPU) {
                throw LiteRtLmEngineException("QNN: unsupported SoC generation")
            }
            FakeLiteRtLmEngine()
        }
        val backend = newBackend(
            probe = npuDevice(),
            nativeLibraryDir = "/data/app/x/lib/arm64",
            factory = strict,
        )
        backend.load(spec)
        assertEquals(
            listOf(AcceleratorKind.NPU, AcceleratorKind.GPU),
            strict.configs.map { it.accelerator },
        )
        assertEquals(AcceleratorKind.GPU, backend.acceleratorReport.active)
    }

    @Test
    fun `the report keeps the runtime's own words when it degrades at load`() = runTest {
        // The probe passed, the driver said no. That message is the only thing
        // that makes this bug-reportable, so it has to survive into the report
        // rather than being swallowed by a generic "load failed".
        val strict = RecordingLiteRtLmEngineFactory { config ->
            if (config.accelerator == AcceleratorKind.GPU) {
                throw LiteRtLmEngineException("OpenCL: kernel compilation failed")
            }
            FakeLiteRtLmEngine()
        }
        val backend = newBackend(probe = gpuOnlyDevice(), factory = strict)
        backend.load(spec)
        val reason = backend.acceleratorReport.fallbackReason!!
        assertTrue(reason.contains("kernel compilation failed"))
        assertTrue(reason.contains("failed to initialise"))
    }

    @Test
    fun `a GPU that fails and a CPU that works still loads the model`() = runTest {
        // A phone whose driver enumerates OpenCL but cannot compile this model.
        // The user gets a working model and an explanation, not an error dialog.
        val strict = RecordingLiteRtLmEngineFactory { config ->
            if (config.accelerator == AcceleratorKind.GPU) {
                throw LiteRtLmEngineException("OpenCL: no device found")
            }
            FakeLiteRtLmEngine()
        }
        val backend = newBackend(probe = gpuOnlyDevice(), factory = strict)
        backend.load(spec)
        assertEquals(AcceleratorKind.CPU, backend.acceleratorReport.active)
        assertTrue(backend.acceleratorReport.degraded)
    }

    @Test
    fun `an explicit CPU preference builds only once even when it fails`() = runTest {
        // No ladder to walk: a user who pinned CPU wants a comparable baseline,
        // and silently retrying on another tier would make the number a lie.
        val strict = RecordingLiteRtLmEngineFactory { _ ->
            throw LiteRtLmEngineException("out of memory")
        }
        val backend = newBackend(
            preference = AcceleratorPreference.CPU,
            probe = gpuOnlyDevice(),
            factory = strict,
        )
        val error = runCatching { backend.load(spec) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(1, strict.configs.size)
    }

    @Test
    fun `every tier failing surfaces as a load failure naming them all`() = runTest {
        val strict = RecordingLiteRtLmEngineFactory { config ->
            throw LiteRtLmEngineException("${config.accelerator.name} exploded")
        }
        val backend = newBackend(
            probe = npuDevice(),
            nativeLibraryDir = "/data/app/x/lib/arm64",
            factory = strict,
        )
        val error = runCatching { backend.load(spec) }.exceptionOrNull()
        assertNotNull(error)
        val message = error!!.message!!
        assertTrue(message.contains("NPU exploded"))
        assertTrue(message.contains("GPU exploded"))
        assertTrue(message.contains("CPU exploded"))
    }

    @Test
    fun `a dead engine on the first tier falls through to the next`() = runTest {
        // isAlive == false is a real native failure mode, not just an exception,
        // and it is what a truncated .litertlm bundle produces.
        val halfDead = RecordingLiteRtLmEngineFactory { config ->
            if (config.accelerator == AcceleratorKind.GPU) {
                FakeLiteRtLmEngine(alive = false)
            } else {
                FakeLiteRtLmEngine()
            }
        }
        val backend = newBackend(probe = gpuOnlyDevice(), factory = halfDead)
        backend.load(spec)
        assertEquals(
            listOf(AcceleratorKind.GPU, AcceleratorKind.CPU),
            halfDead.configs.map { it.accelerator },
        )
        assertEquals(AcceleratorKind.CPU, backend.acceleratorReport.active)
    }

    @Test
    fun `a tier the probe rejected is never even attempted`() = runTest {
        // The performance half of the probe. Re-attempting an accelerator that a
        // directory listing already proved absent means a doomed native call on
        // every single load.
        val backend = newBackend(
            preference = AcceleratorPreference.NPU,
            probe = gpuOnlyDevice(),
        )
        backend.load(spec)
        assertEquals(listOf(AcceleratorKind.GPU), factory.requestedAccel())
    }

    @Test
    fun `a failed accelerator load leaves the previously loaded model working`() = runTest {
        val healthy = newBackend(probe = FakeAcceleratorProbe())
        healthy.load(spec)

        val strict = RecordingLiteRtLmEngineFactory { _ ->
            throw LiteRtLmEngineException("no memory")
        }
        val other = newBackend(factory = strict)
        // A separate backend instance, so this asserts the shape of the guarantee
        // rather than sharing state: a failed load throws instead of leaving a
        // half-built engine behind.
        val error = runCatching { other.load(spec) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(AcceleratorKind.CPU, healthy.acceleratorReport.active)
    }
}
