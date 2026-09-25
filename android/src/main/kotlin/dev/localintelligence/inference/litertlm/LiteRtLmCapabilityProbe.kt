package dev.localintelligence.inference.litertlm

import dev.localintelligence.core.model.AcceleratorKind
import dev.localintelligence.core.model.AcceleratorProbe
import dev.localintelligence.core.model.AcceleratorStatus
import java.io.File

/**
 * Answers "can this phone actually run on its NPU / GPU" by looking, not by guessing.
 *
 * ## Why `Build.SOC_MODEL` is not a capability
 *
 * The obvious cheap implementation is a string match on `Build.SOC_MANUFACTURER`
 * — Snapdragon gets NPU, Mali gets GPU, done. It is wrong in both directions and
 * wrong in the direction that hurts:
 *
 * - A Snapdragon whose QNN delegate libraries are not present in the app gets a
 *   runtime that fails to initialise, mid-load, after the user has already waited
 *   for a 2 GB model to be opened.
 * - A Tensor-based Pixel, which does have an NPU, is told it does not.
 *
 * Neither string says whether the *library* is loadable. So this probe checks for
 * the library. That is the only question LiteRT-LM itself asks: `liblitertlm_jni.so`
 * logs `Failed to load OpenCL library with dlopen` and
 * `NPU accelerator could not be loaded and registered` and recovers by running
 * slower, or not at all. A build-tag check would be guessing at the answer the
 * native code already knows.
 *
 * ## What each tier actually needs, from the shipped 0.13.1 libraries
 *
 * Verified against the AAR's own `arm64-v8a` payload, not from documentation:
 *
 * - **CPU** — `liblitertlm_jni.so` alone. Always present. Nothing to probe, which
 *   is exactly why it is the terminal fallback in [dev.localintelligence.core.model.AcceleratorPreference].
 * - **GPU** — LiteRT's GPU accelerator is OpenCL-based. `libLiteRt.so` and
 *   `libLiteRtClGlAccelerator.so` `dlopen` one of `libOpenCL.so`,
 *   `libOpenCL-car.so` (Qualcomm/Adreno) or `libOpenCL-pixel.so` (Google), plus
 *   `libvndksupport.so` for the EGL/CL interop. The first three are vendor driver
 *   libraries, absent on emulators and x86 images — which is why a GPU backend
 *   that does not probe fails on a Pixel emulator and succeeds on a Pixel phone.
 * - **NPU** — LiteRT-LM's NPU backend takes a *dispatch library directory*: the
 *   JNI logs `You should provide the 'DispatchLibraryDir' option to use NPU` and
 *   `Dispatch library directory is not set.` The delegate is loaded as a compiler
 *   plugin from that directory (`Failed to load plugin at: %s`). The AAR ships
 *   **no** NPU library — its entire payload is `liblitertlm_jni.so`,
 *   `libLiteRt.so` and `libLiteRtClGlAccelerator.so`. So NPU is available only
 *   when the integrator has put a vendor delegate into the APK.
 *
 * That last point is the honest headline: **on a stock build, NPU is never
 * available**, and this probe says so on the first run instead of after a failed
 * load. See docs/acceleration.md for which chips can reach which path.
 *
 * ## Caching
 *
 * Results are memoised for the process lifetime. A probe is a directory listing
 * plus at most one `System.loadLibrary` attempt per candidate, and
 * [AcceleratorSelector] may run it for three tiers at once — on a cold start
 * that is real work on the critical path before a model is even opened. Native
 * library load state cannot change while the process lives anyway, so caching is
 * not a staleness risk here, it is only a cost saving. [clearCache] exists for
 * tests and for an app that installs a Play Feature module at runtime.
 */
class LiteRtLmCapabilityProbe(
    /**
     * Where vendor NPU delegate libraries would live, i.e.
     * `context.applicationInfo.nativeLibraryDir`. Null when the caller has no
     * context, which makes NPU unavailable with a reason rather than a crash.
     */
    private val nativeLibraryDir: String?,
    /**
     * The actual "can this be loaded" test.
     *
     * Injected because it is the only part that cannot run on a JVM: on a device
     * it is `System.loadLibrary`, and a fake in tests is a set of names. Note it
     * takes a bare soname (`OpenCL`), not a path — `System.loadLibrary` resolves
     * against the app's own lib dir and the platform namespace, which is exactly
     * the resolution the runtime itself gets.
     */
    private val libraryLoadable: (String) -> Boolean = ::canLoadBySoname,
) : AcceleratorProbe {

    /**
     * Guards [cache]. Per-instance, not global: a global lock would make two
     * unrelated probes contend, and there is nothing to protect across instances.
     */
    private val cacheLock = Any()

    @Volatile
    private var cache: Map<AcceleratorKind, AcceleratorStatus>? = null

    override fun probe(kind: AcceleratorKind): AcceleratorStatus? =
        cached()[kind]

    /** Forgets memoised answers. See the KDoc on why this is safe at all. */
    fun clearCache() {
        synchronized(cacheLock) { cache = null }
    }

    private fun cached(): Map<AcceleratorKind, AcceleratorStatus> {
        cache?.let { return it }
        return synchronized(cacheLock) {
            cache ?: probeAll().also { cache = it }
        }
    }

    /**
     * Probes every tier, so a single call fills the cache for all three and a
     * selection never re-lists the same directory twice.
     */
    private fun probeAll(): Map<AcceleratorKind, AcceleratorStatus> = buildMap {
        put(AcceleratorKind.CPU, cpuStatus())
        put(AcceleratorKind.GPU, gpuStatus())
        put(AcceleratorKind.NPU, npuStatus())
    }

    /**
     * CPU is reported available without touching the filesystem.
     *
     * Not laziness: `liblitertlm_jni.so` is packaged by the AAR for every ABI the
     * app builds for, so its absence means the APK is broken in a way no probe can
     * diagnose and no fallback can rescue. Checking for it would produce a CPU
     * report saying "CPU unavailable", which is a confusing way to say "the app
     * does not work".
     */
    private fun cpuStatus() = AcceleratorStatus(
        kind = AcceleratorKind.CPU,
        available = true,
        detail = "CPU is always available: it needs no vendor library",
    )

    /**
     * GPU is available when a platform OpenCL driver is loadable.
     *
     * The candidate list is the one the native code actually tries, in its own
     * order: `libOpenCL.so`, then the vendor-suffixed `libOpenCL-car.so` and
     * `libOpenCL-pixel.so`. Checking only the first would call an Adreno phone
     * GPU-less, which is precisely backwards.
     *
     * `libvndksupport.so` is required for the OpenGL/CL shared context and is
     * declared in the manifest; a missing one is reported as missing rather than
     * folded into "no OpenCL", because the fix is a manifest entry and not a
     * different phone.
     */
    private fun gpuStatus(): AcceleratorStatus {
        val missing = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val driver = GPU_OPENCL_DRIVERS.firstOrNull { soname ->
            val loaded = tryLoad(System.mapLibraryName(soname), errors)
            loaded
        }
        if (driver == null) {
            missing += GPU_OPENCL_DRIVERS.map(System::mapLibraryName)
        }
        val vndk = System.mapLibraryName(VNSDK_SUPPORT)
        if (tryLoad(vndk, errors) != true) missing += vndk

        return if (missing.isEmpty()) {
            AcceleratorStatus(
                kind = AcceleratorKind.GPU,
                available = true,
                detail = "GPU is available: $driver is loadable and $vndk is present",
            )
        } else {
            AcceleratorStatus(
                kind = AcceleratorKind.GPU,
                available = false,
                detail = "GPU unavailable: no OpenCL driver or vndksupport on this " +
                    "device (looked for ${missing.joinToString()})" +
                    errorsSuffix(errors),
                missingLibraries = missing,
                // A load that threw is "could not tell", not "looked and absent".
                // The distinction decides whether this is a phone limitation worth
                // a bug report or a perfectly normal CPU-only device.
                probeFailed = errors.isNotEmpty(),
            )
        }
    }

    /**
     * Appends loader errors to a probe detail.
     *
     * The errors go in the *status* rather than into logcat, because this string
     * is what the user sees in the fallback reason. "libvndksupport.so:
     * cannot locate symbol" in the UI is worth a bug report; the same line only
     * in logcat is worth nothing to anybody.
     */
    private fun errorsSuffix(errors: List<String>): String =
        if (errors.isEmpty()) "" else "; load errors: ${errors.joinToString("; ")}"

    /**
     * NPU is available only when a delegate library is actually present.
     *
     * The negative result is the common one and it is stated precisely, because
     * "NPU unavailable" on a Snapdragon would otherwise read as a bug. The reason
     * names the directory searched, so the difference between "the integrator has
     * not shipped a delegate" and "the delegate is somewhere else" is legible.
     *
     * The positive result is deliberately *weak*: a file being on disk is
     * necessary but not sufficient, since the delegate must also match the chip.
     * That uncertainty is why selection treats NPU as a candidate to try and the
     * backend's own load path is what finally confirms it — see
     * [LiteRtLmBackend]'s KDoc on why a failed NPU load falls back rather than
     * propagating.
     */
    private fun npuStatus(): AcceleratorStatus {
        val dir = nativeLibraryDir
        if (dir.isNullOrBlank()) {
            return AcceleratorStatus(
                kind = AcceleratorKind.NPU,
                available = false,
                detail = "NPU unavailable: no native library dir was supplied, so no " +
                    "vendor delegate can be searched for",
                missingLibraries = listOf("<nativeLibraryDir not supplied>"),
            )
        }
        val root = File(dir)
        if (!root.isDirectory) {
            return AcceleratorStatus(
                kind = AcceleratorKind.NPU,
                available = false,
                detail = "NPU unavailable: the native library dir $dir does not exist",
                missingLibraries = listOf(dir),
            )
        }
        // The AAR's own payload is a known, fixed set. Anything else .so in the
        // dir is something the integrator added, and for this runtime the only
        // thing an integrator adds is an NPU delegate.
        val delegates = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SO_SUFFIX) }
            ?.map { it.name }
            ?.filterNot { it in LITERTLM_SHIPPED_LIBRARIES }
            ?.sorted()
            .orEmpty()

        return if (delegates.isNotEmpty()) {
            AcceleratorStatus(
                kind = AcceleratorKind.NPU,
                available = true,
                detail = "NPU delegate libraries found in $dir: " +
                    delegates.joinToString(),
                missingLibraries = emptyList(),
            )
        } else {
            AcceleratorStatus(
                kind = AcceleratorKind.NPU,
                available = false,
                detail = "NPU unavailable: no NPU delegate library in $dir. " +
                    "litertlm-android ships no NPU library, so the chip vendor's " +
                    "delegate (Qualcomm AI Engine Direct, Intel OpenVINO) has to be " +
                    "packaged into the app for this to ever succeed",
                missingLibraries = listOf("$dir/<vendor NPU delegate>.so"),
            )
        }
    }

    /**
     * A load attempt must not be able to take the process down.
     *
     * `System.loadLibrary` throws `UnsatisfiedLinkError` — an `Error`, not an
     * `Exception` — for a missing library, and a malformed one throws from the
     * vendor driver. Both are caught here, because a probe that crashes is
     * indistinguishable from a crash the user caused.
     *
     * The message is recorded in [errors] rather than logged: this is text that
     * ends up in the user-visible fallback reason, and a line that only exists in
     * logcat helps nobody debugging a phone they are holding.
     */
    private fun tryLoad(soname: String, errors: MutableList<String>): Boolean = try {
        libraryLoadable(soname)
    } catch (t: Throwable) {
        errors += "$soname: ${t.message ?: t::class.java.simpleName}"
        false
    }

    private companion object {
        const val SO_SUFFIX = ".so"

        /**
         * Platform OpenCL sonames, in the order the runtime tries them.
         *
         * Read out of `libLiteRt.so` / `libLiteRtClGlAccelerator.so` in the
         * shipped 0.13.1 AAR, not guessed. The vendor-suffixed variants exist
         * because Android 12+ linker namespaces hide the generic name on some
         * devices while exposing the specific one.
         */
        val GPU_OPENCL_DRIVERS = listOf("OpenCL", "OpenCL-car", "OpenCL-pixel")

        /** Needed for the OpenGL/OpenCL shared context the GPU path builds. */
        const val VNSDK_SUPPORT = "vndksupport"

        /**
         * Everything the LiteRT-LM AAR puts in the APK's native library dir.
         *
         * Excluded from the NPU delegate search so that LiteRT-LM's own payload
         * cannot be mistaken for a vendor delegate — otherwise NPU would report
         * available on every install, which is the failure this whole file exists
         * to prevent.
         */
        val LITERTLM_SHIPPED_LIBRARIES = setOf(
            "liblitertlm_jni.so",
            "libLiteRt.so",
            "libLiteRtClGlAccelerator.so",
            // Present in 0.13.1's own strings but not shipped in the AAR. Listed
            // anyway so that an integrator who adds one for its own reasons does
            // not accidentally flip the NPU probe on.
            "libLiteRtGpuAccelerator.so",
            "libLiteRtOpenClAccelerator.so",
            "libLiteRtVulkanAccelerator.so",
            "libLiteRtWebGpuAccelerator.so",
        )
    }
}

/**
 * The production library check.
 *
 * `System.loadLibrary` rather than a `File` existence test because a file being
 * present says nothing about whether the dynamic linker will resolve it — the
 * Android linker namespaces since API 24 will happily refuse a vendor library the
 * app has not declared in its manifest, and that is exactly the failure the
 * `uses-native-library` entries in the manifest exist to prevent. Attempting the
 * load is the same question the runtime asks.
 *
 * A second call for an already-loaded library is a no-op returning true, so this
 * is safe to repeat.
 */
private fun canLoadBySoname(soname: String): Boolean {
    System.loadLibrary(soname)
    return true
}

/**
 * A probe built from a set of sonames, for tests and for previews.
 *
 * Exists so the JVM suite can exercise "GPU device" and "no-GPU device" without
 * a device. Deliberately dumb: it answers from the set it was handed, so a test
 * that puts `libOpenCL.so` in the set is asserting about the *selection* rules,
 * not about whether a real phone has OpenCL.
 */
class FakeAcceleratorProbe(
    /** Sonames considered loadable. Anything else is "missing". */
    val loadableLibraries: Set<String> = emptySet(),
    /** Sonames present in the fake native library dir. */
    val delegateLibraries: Set<String> = emptySet(),
    /** When set, NPU reports this failure instead of consulting [delegateLibraries]. */
    var npuFailure: String? = null,
    /** Tiers forced unavailable regardless of libraries, for a targeted test. */
    val forcedUnavailable: Set<AcceleratorKind> = emptySet(),
) : AcceleratorProbe {

    /** Every tier asked for, in order. Proves the selector consults the ladder. */
    val probed = mutableListOf<AcceleratorKind>()

    override fun probe(kind: AcceleratorKind): AcceleratorStatus? {
        probed += kind
        if (kind in forcedUnavailable) {
            return AcceleratorStatus(
                kind = kind,
                available = false,
                detail = "${kind.name} unavailable: forced off by the test",
            )
        }
        return when (kind) {
            AcceleratorKind.CPU -> AcceleratorStatus(
                kind = kind,
                available = true,
                detail = "CPU is always available: it needs no vendor library",
            )
            AcceleratorKind.GPU -> fakeGpu()
            AcceleratorKind.NPU -> fakeNpu()
        }
    }

    private fun fakeGpu(): AcceleratorStatus {
        val missing = mutableListOf<String>()
        if (GPU_OPENCL_DRIVERS.none { System.mapLibraryName(it) in loadableLibraries }) {
            missing += GPU_OPENCL_DRIVERS.map(System::mapLibraryName)
        }
        val vndk = System.mapLibraryName(VNSDK_SUPPORT)
        if (vndk !in loadableLibraries) missing += vndk
        return if (missing.isEmpty()) {
            AcceleratorStatus(
                kind = AcceleratorKind.GPU,
                available = true,
                detail = "GPU is available: an OpenCL driver and $vndk are loadable",
            )
        } else {
            AcceleratorStatus(
                kind = AcceleratorKind.GPU,
                available = false,
                detail = "GPU unavailable: no OpenCL driver or vndksupport on this " +
                    "device (looked for ${missing.joinToString()})",
                missingLibraries = missing,
            )
        }
    }

    private fun fakeNpu(): AcceleratorStatus {
        npuFailure?.let {
            return AcceleratorStatus(
                kind = AcceleratorKind.NPU,
                available = false,
                detail = "NPU unavailable: $it",
                probeFailed = true,
            )
        }
        return if (delegateLibraries.isNotEmpty()) {
            AcceleratorStatus(
                kind = AcceleratorKind.NPU,
                available = true,
                detail = "NPU delegate libraries found: ${delegateLibraries.sorted()}",
            )
        } else {
            AcceleratorStatus(
                kind = AcceleratorKind.NPU,
                available = false,
                detail = "NPU unavailable: no NPU delegate library. " +
                    "litertlm-android ships no NPU library, so the chip vendor's " +
                    "delegate (Qualcomm AI Engine Direct, Intel OpenVINO) has to be " +
                    "packaged into the app for this to ever succeed",
                missingLibraries = listOf("<vendor NPU delegate>.so"),
            )
        }
    }

    private companion object {
        val GPU_OPENCL_DRIVERS = listOf("OpenCL", "OpenCL-car", "OpenCL-pixel")
        const val VNSDK_SUPPORT = "vndksupport"
    }
}

/**
 * A probe that never fails and never claims an NPU.
 *
 * The default when no context is available — a headless JVM harness, a unit
 * test, a future desktop target. It is honest rather than permissive: everything
 * degrades to CPU, and the reason says so, instead of a fabricated "NPU
 * available" that would make a tok/s figure a lie.
 */
object CpuOnlyAcceleratorProbe : AcceleratorProbe {
    override fun probe(kind: AcceleratorKind): AcceleratorStatus? =
        AcceleratorStatus(
            kind = kind,
            available = kind == AcceleratorKind.CPU,
            detail = if (kind == AcceleratorKind.CPU) {
                "CPU is always available: it needs no vendor library"
            } else {
                "${kind.name} unavailable: no device probe was supplied, so no " +
                    "accelerator can be claimed"
            },
        )
}
