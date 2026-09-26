package dev.localintelligence.app.ui.trace

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.localintelligence.android.inference.DeviceBudgetBasis
import dev.localintelligence.android.inference.LlamaBridge
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.android.inference.RamEstimate
import dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
import dev.localintelligence.app.AppContainer
import dev.localintelligence.app.data.TaskAction
import dev.localintelligence.app.data.intentForTaskAction
import dev.localintelligence.app.execution.TaskAlarmScheduler
import dev.localintelligence.core.metrics.CheckOutcome
import dev.localintelligence.core.metrics.DiagnosticAction
import dev.localintelligence.core.metrics.DiagnosticCheck
import dev.localintelligence.core.metrics.DiagnosticReport
import dev.localintelligence.core.metrics.Finding
import dev.localintelligence.core.metrics.RamGateCrossCheck
import dev.localintelligence.core.metrics.RunMetricsCheck
import dev.localintelligence.core.metrics.RunMetricsJournal
import dev.localintelligence.core.metrics.RunPerformanceCheck
import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.GgufParseException
import dev.localintelligence.core.model.gguf.GgufParser
import dev.localintelligence.core.model.gguf.GgufWarning
import dev.localintelligence.core.model.gguf.MemoryEstimate
import dev.localintelligence.core.model.gguf.ModelMemoryEstimator
import dev.localintelligence.inference.litertlm.BackendRoutingException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Runs every local self-check and returns a report the screen can render.
 *
 * ## Why this is a plain class and not a framework
 *
 * There is no registry, no plugin discovery, no check ordering engine and no
 * lifecycle abstraction. There is a list of named functions, each of which
 * builds one [DiagnosticCheck] and returns. A framework here would be a
 * parallel vocabulary to the checks, and the last thing this project needs is a
 * second way to say "cannot tell".
 *
 * ## Why every check takes no arguments and is a pure read
 *
 * A self-check that mutated state could not be run twice to see whether the
 * state changed, which is the only way a user can tell "it is still broken"
 * from "I fixed it". Each of these reads device state, parses a header, or asks
 * an existing component what it would do. None of them loads a model, starts a
 * run, or writes anything.
 *
 * ## Why nothing here touches the network
 *
 * There is no network code in this file, and no repository that could fetch
 * something. A self-check that needed a server could not diagnose a phone with
 * no signal, which is exactly when a user most needs it.
 */
class DeviceSelfCheck(
    private val context: Context,
    private val container: AppContainer,
) {

    /** Runs every check. Cheap by construction: reads, header parses, one dlopen. */
    fun run(): DiagnosticReport = DiagnosticReport(
        checks = listOf(
            modelFileCheck(),
            magicRoutingCheck(),
            headerCheck(),
            ramGateCheck(),
            nativeLibraryCheck(),
            abiCheck(),
            permissionCheck(),
            deviceMemoryCheck(),
            toolRegistryCheck(),
            RunMetricsCheck.run(RunMetricsJournal.latest()),
            RunPerformanceCheck.unknown(),
        ),
        ranAtMillis = System.currentTimeMillis(),
    )

    // ------------------------------------------------------------------ model file

    /**
     * Is there a model file on this phone, and is it readable?
     *
     * WHY the largest file rather than "the active model": there is no
     * persisted "active" pointer to read, and the failure this catches is a
     * download that left a truncated or zero-byte file. The largest file in
     * the models directory is the one a user means when they say "my model".
     */
    private fun modelFileCheck(): DiagnosticCheck {
        val dir = container.modelsDir
        if (!dir.isDirectory) {
            return DiagnosticCheck(
                id = ID_MODEL_FILE,
                title = "Is there a model file to check?",
                outcome = CheckOutcome.FAIL,
                verified = "Listed ${dir.absolutePath} for model files.",
                finding = "The models directory does not exist. It is created on first " +
                    "import or download, so this means nothing has ever been imported.",
                cannotTell = "Why nothing was imported — nothing has been attempted yet. " +
                    "This is the expected state of a fresh install, not a fault.",
                nextAction = DiagnosticAction.OpenModels,
            )
        }

        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) {
            return DiagnosticCheck(
                id = ID_MODEL_FILE,
                title = "Is there a model file to check?",
                outcome = CheckOutcome.FAIL,
                verified = "Listed ${dir.absolutePath} for model files.",
                finding = "The directory is empty. No model has been imported or " +
                    "downloaded on this phone, so every model-dependent check below " +
                    "has nothing to run against.",
                cannotTell = "Whether a download was attempted and failed, or never " +
                    "started. This check only sees the end state of the directory.",
                nextAction = DiagnosticAction.OpenModels,
            )
        }

        val model = files.maxByOrNull { it.length() }!!
        val readable = model.canRead()
        val bytes = model.length()
        val findings = files.sortedByDescending { it.length() }.map { file ->
            Finding.measured(
                file.name,
                MemoryEstimate.formatBytes(file.length()) +
                    if (file.canRead()) "" else "  (not readable)",
                "File.length() on this device",
            )
        }

        return DiagnosticCheck(
            id = ID_MODEL_FILE,
            title = "Is there a model file, and can this app read it?",
            outcome = when {
                !readable -> CheckOutcome.FAIL
                bytes == 0L -> CheckOutcome.FAIL
                else -> CheckOutcome.PASS
            },
            verified = "Listed ${dir.absolutePath} and stat'd each file: existence, " +
                "readability and byte length, straight from the filesystem.",
            finding = when {
                !readable -> "The largest file, ${model.name}, exists but this process " +
                    "cannot read it. Its length is reported as " +
                    "${MemoryEstimate.formatBytes(bytes)}."
                bytes == 0L -> "${model.name} is present and readable but is 0 bytes. " +
                    "That is a download or copy that did not finish, and nothing that " +
                    "loads it can succeed."
                else -> "${files.size} file(s) present. The largest, ${model.name}, is " +
                    "${MemoryEstimate.formatBytes(bytes)} and this process can read it."
            },
            cannotTell = "Whether the bytes are a model at all. Length and readability " +
                "say nothing about format or integrity — a file full of zeroes passes " +
                "this check. The routing and header checks below read the actual " +
                "contents for that reason.",
            findings = findings,
            nextAction = when {
                !readable || bytes == 0L -> DiagnosticAction.OpenModels
                else -> null
            },
        )
    }

    /** The largest model file, or null. Shared by the checks that need one. */
    private fun largestModel(): File? =
        container.modelsDir.listFiles()?.filter { it.isFile && it.length() > 0L }
            ?.maxByOrNull { it.length() }

    // ------------------------------------------------------------------ magic routing

    /**
     * Which backend would this file's first bytes route to?
     *
     * WHY this asks the router rather than reimplementing the rule: the
     * `.litertlm` magic window was an 8-byte window for an 8-byte magic and
     * could never match at offset 4, and the comment claimed otherwise. A
     * second copy of the sniffing rule in a diagnostics screen is a third place
     * for it to be wrong. This calls [dev.localintelligence.inference.litertlm.ModelBackendRouter.route]
     * and reports its actual answer, including its actual refusal.
     */
    private fun magicRoutingCheck(): DiagnosticCheck {
        val file = largestModel()
            ?: return cannotRun(ID_MAGIC_ROUTING, "Which backend would this file route to?",
                "Read the first 16 bytes of the largest model file.",
                "There is no readable, non-empty model file, so there are no bytes to " +
                    "route. The model-file check has the reason.")

        val head = try {
            RandomAccessFile(file, "r").use { raf ->
                ByteArray(MAGIC_WINDOW).also { raf.readFully(it) }
            }
        } catch (e: IOException) {
            return DiagnosticCheck(
                id = ID_MAGIC_ROUTING,
                title = "Which backend would this file route to?",
                outcome = CheckOutcome.CANNOT_TELL,
                verified = "Tried to read the first $MAGIC_WINDOW bytes of " +
                    "${file.name}.",
                finding = "The read failed: ${e.javaClass.simpleName}: ${e.message}.",
                cannotTell = "Which backend this file would route to. The router needs " +
                    "the first bytes and the read that produces them did not succeed, " +
                    "so any answer here would be a guess.",
                nextAction = DiagnosticAction.OpenModels,
            )
        }

        val hex = head.joinToString(" ") { "%02X".format(it) }
        val route = try {
            val backend = container.backendRouter.route(file)
            "routes to \"${backend.id}\""
        } catch (e: BackendRoutingException) {
            "refused: ${e.message}"
        } catch (e: Throwable) {
            "the router threw ${e.javaClass.simpleName}: ${e.message}"
        }
        val refused = route.startsWith("refused") || route.startsWith("the router threw")

        return DiagnosticCheck(
            id = ID_MAGIC_ROUTING,
            title = "Which backend would this file route to?",
            outcome = if (refused) CheckOutcome.FAIL else CheckOutcome.PASS,
            verified = "Read the first $MAGIC_WINDOW bytes of ${file.name} and asked " +
                "the app's own ModelBackendRouter to route the file, rather than " +
                "re-deciding the format here.",
            finding = "${file.name} $route. Its first $MAGIC_WINDOW bytes are $hex.",
            cannotTell = "Whether the chosen backend can then load it. Routing is a " +
                "format decision made from 16 bytes; a GGUF can have a correct magic " +
                "and be truncated, wrong-versioned or built for an architecture this " +
                "build has no kernels for. The header check below reads further in.",
            findings = listOf(
                Finding.measured("First $MAGIC_WINDOW bytes", hex,
                    "read straight off the file on this device"),
                Finding.computed("Backend", route,
                    "ModelBackendRouter.route on those bytes"),
            ),
            nextAction = if (refused) DiagnosticAction.OpenModels else null,
        )
    }

    // ------------------------------------------------------------------ header

    /**
     * Does the GGUF header parse, and does it say anything alarming?
     *
     * WHY warnings are shown rather than swallowed: two parsers in this project
     * disagreed about the same file, and the truncated one reported a confident
     * number. The warnings are the parser telling us where its own confidence
     * ends, and they are the only thing on this screen that says so.
     */
    private fun headerCheck(): DiagnosticCheck {
        val file = largestModel()
            ?: return cannotRun(ID_HEADER, "Does the model header parse?",
                "Parsed the GGUF header of the largest model file.",
                "There is no readable, non-empty model file to parse.")

        val header = try {
            GgufParser.parse(file)
        } catch (e: GgufParseException) {
            return DiagnosticCheck(
                id = ID_HEADER,
                title = "Does the model header parse?",
                outcome = CheckOutcome.FAIL,
                verified = "Ran GgufParser over ${file.name} with the default limits, " +
                    "in partial mode, the same call the model manager makes.",
                finding = "The parse failed: ${e.message}",
                cannotTell = "How far into the file the parser got before stopping. " +
                    "GgufParseException reports the position it reached, not the " +
                    "structure it expected there.",
                findings = listOf(Finding.measured("File", file.name, "File.name on this device")),
                nextAction = DiagnosticAction.OpenModels,
            )
        } catch (e: Throwable) {
            return DiagnosticCheck(
                id = ID_HEADER,
                title = "Does the model header parse?",
                outcome = CheckOutcome.FAIL,
                verified = "Ran GgufParser over ${file.name}.",
                finding = "The parse threw ${e.javaClass.simpleName}: ${e.message}",
                cannotTell = "Whether this is a malformed file or a parser defect. The " +
                    "exception type is not one this screen knows how to interpret.",
                nextAction = DiagnosticAction.OpenModels,
            )
        }
        return headerReport(file, header)
    }

    /** The shared half of [headerCheck], also used by [ramGateCheck]'s inputs. */
    private fun headerReport(file: File, header: GgufHeader): DiagnosticCheck {
        val architecture = header.metadata.architecture
        val contextLength = header.metadata.contextLength
        val estimate = ModelMemoryEstimator().estimate(
            header = header,
            contextLengthOverride = ModelImporter.DEFAULT_CONTEXT_LENGTH.toLong(),
        )
        val rows = buildList {
            add(Finding.measured("GGUF version", header.version.toString(),
                "read from the file's version field"))
            add(Finding.measured("Tensors declared / parsed",
                "${header.declaredTensorCount} / ${header.tensors.size}",
                "the count the header declares against the count the tensor table " +
                    "actually yielded; a gap means the table was truncated"))
            add(Finding.computed("Weights", MemoryEstimate.formatBytes(estimate.weightsBytes),
                "from the tensor table, which is exact for a complete header"))
            add(Finding.computed("KV cache at ${ModelImporter.DEFAULT_CONTEXT_LENGTH} ctx",
                MemoryEstimate.formatBytes(estimate.kvCacheBytes),
                "2 tensors x context x head count x head dim x 2 bytes x bytes-per-element"))
        }
        val fields = listOfNotNull(
            architecture?.let { "architecture $it" },
            contextLength?.let { "context $it" },
            header.metadata.fileType?.let { "file type ${it.label}" },
        )

        return DiagnosticCheck(
            id = ID_HEADER,
            title = "Does the model header parse?",
            outcome = when {
                header.warnings.isNotEmpty() -> CheckOutcome.CANNOT_TELL
                !header.isComplete -> CheckOutcome.FAIL
                else -> CheckOutcome.PASS
            },
            verified = "Ran GgufParser over ${file.name} and read the version, the " +
                "declared tensor count, the tensor table and the metadata, then ran " +
                "ModelMemoryEstimator at the app's own context of " +
                "${ModelImporter.DEFAULT_CONTEXT_LENGTH}.",
            finding = buildString {
                if (fields.isNotEmpty()) append(fields.joinToString(", ")).append(". ")
                append(
                    if (header.isComplete) "The header is complete."
                    else "The header is INCOMPLETE — parsing stopped early.",
                )
                if (header.warnings.isEmpty()) {
                    append(" The parser raised no warnings.")
                } else {
                    append(" The parser raised ${header.warnings.size} warning(s): ")
                    append(header.warnings.joinToString("; ") { describeWarning(it) })
                    append(
                        ". Each one is a place where the parser stopped trusting the " +
                            "file, and the numbers above are only as good as the last " +
                            "one.",
                    )
                }
            },
            cannotTell = "Whether the weights are intact. The parser reads the header " +
                "and the tensor table and stops; it does not verify that the tensor " +
                "data that follows matches what the table describes, so a file that " +
                "parses perfectly can still be a truncated download. The one number " +
                "that would settle it is the file's length against the sum of the " +
                "tensors' declared extents, and this check does not compute that.",
            findings = rows,
            nextAction = if (header.isComplete) null else DiagnosticAction.OpenModels,
        )
    }

    // ------------------------------------------------------------------ ram gate

    /**
     * Does the RAM gate agree with what the loader would allocate?
     *
     * The check itself is [RamGateCrossCheck] in `:core`, so it can be reasoned
     * about without an Android device. This only gathers the two inputs that
     * are genuinely device-specific — the two *different* available-RAM
     * functions — and hands them over separately rather than picking one, which
     * is the whole point of the comparison.
     */
    private fun ramGateCheck(): DiagnosticCheck {
        val file = largestModel()
            ?: return cannotRun(ID_RAM_GATE, "Does the RAM gate agree with the loader?",
                "Compared the download-time gate against the loader's own gate.",
                "There is no readable, non-empty model file, so neither gate has a " +
                    "file to price.")

        val header = try {
            GgufParser.parse(file)
        } catch (_: Throwable) {
            null // RamGateCrossCheck renders the parse reason in its cannot-tell branch.
        }

        return RamGateCrossCheck.run(
            fileName = file.name,
            fileBytes = file.length(),
            header = header,
            contextLength = ModelImporter.DEFAULT_CONTEXT_LENGTH,
            budget = container.hubBudget,
            // The loader's own budget function, passed as itself and not as the
            // gate's. Substituting one for the other here would recreate the
            // disagreement this check exists to catch.
            loaderAvailableBytes = RamEstimate.usableDeviceBytes(),
        )
    }

    // ------------------------------------------------------------------ native

    /**
     * Can the native library actually be loaded, and what did the loader say?
     *
     * WHY the raw `System.loadLibrary` error is shown: "native library failed
     * to load" is the least actionable sentence in software. The loader's own
     * message names the soname, the ABI it searched and often the symbol it
     * wanted, which is the difference between a bug report and a shrug.
     *
     * WHY it is safe to call here: [LlamaBridge.isAvailable] already calls
     * `System.loadLibrary` in a `try`, so the library is either loaded (this
     * call is a cheap no-op returning the cached handle) or it threw (and
     * throwing again is free and gives the same message). No second load path
     * is being invented.
     */
    private fun nativeLibraryCheck(): DiagnosticCheck {
        val name = LlamaBridge.LIBRARY_NAME
        val soname = System.mapLibraryName(name)
        val loadError = try {
            System.loadLibrary(name)
            null
        } catch (e: UnsatisfiedLinkError) {
            "${e.message}"
        } catch (e: Throwable) {
            "${e.javaClass.simpleName}: ${e.message}"
        }

        val libDir = context.applicationInfo.nativeLibraryDir
        val onDisk = File(libDir).listFiles()
            ?.filter { it.name.endsWith(".so") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

        val buildInfo = LlamaBridge.systemInfo()

        return DiagnosticCheck(
            id = ID_NATIVE,
            title = "Can the native inference library be loaded?",
            outcome = when {
                loadError != null -> CheckOutcome.FAIL
                buildInfo == null -> CheckOutcome.CANNOT_TELL
                else -> CheckOutcome.PASS
            },
            verified = "Called System.loadLibrary(\"$name\") in this process and read " +
                "the loader's own exception if it threw. Read the soname " +
                "($soname) and the ABI directories the package manager extracted " +
                "this APK's native code into, then asked llama.cpp for its build " +
                "string.",
            finding = when {
                loadError != null -> "The loader refused $soname. Its own message: " +
                    "$loadError. The library is not in " + (libDir ?: "an unknown directory") +
                    " for this process's ABI."
                buildInfo == null -> "The library loaded, but llama.cpp reported no " +
                    "build string, so this check cannot confirm the native side is " +
                    "the build this app expects."
                else -> "The library loaded and llama.cpp reports: $buildInfo"
            },
            cannotTell = "Whether the native code is *correct*, only that it mapped. " +
                "A library can load cleanly and still fail on the first model. It " +
                "also cannot tell you how much native memory a loaded model will " +
                "actually occupy — that is `docs/measure/measure_ram.sh`, and no " +
                "figure from it exists yet.",
            findings = buildList {
                add(Finding.measured("Native library dir",
                    libDir ?: "not set by the package manager",
                    "context.applicationInfo.nativeLibraryDir on this device"))
                add(Finding.measured("Shared objects extracted there",
                    if (onDisk.isEmpty()) "none listed" else onDisk.joinToString(", "),
                    "directory listing of nativeLibraryDir"))
                add(
                    if (loadError != null) Finding.measured("Loader error", loadError,
                        "the UnsatisfiedLinkError the dynamic linker itself raised")
                    else Finding.measured("Loader result", "loaded",
                        "System.loadLibrary returned without throwing"),
                )
                if (buildInfo != null) {
                    add(Finding.measured("llama.cpp build", buildInfo,
                        "reported by the native library itself"))
                }
            },
            nextAction = if (loadError != null) DiagnosticAction.NONE else null,
        )
    }

    /**
     * Is the ABI this APK was built for actually present on this phone?
     *
     * WHY this is a separate check from the library load: "the library loaded"
     * and "the right library was shipped" are different facts, and the case
     * that bites is a phone whose primary ABI is not one this APK packaged —
     * the library then loads from a *different* directory or not at all, with an
     * error that blames the wrong thing.
     */
    private fun abiCheck(): DiagnosticCheck {
        val supported = Build.SUPPORTED_ABIS.toList()
        val packaged = context.applicationInfo.nativeLibraryDir
            ?.let { File(it).parentFile }
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        val primary = supported.firstOrNull()
        val primaryPackaged = primary != null && packaged.contains(primary)

        return DiagnosticCheck(
            id = ID_ABI,
            title = "Was a native library built for this phone's ABI?",
            outcome = when {
                packaged.isEmpty() -> CheckOutcome.CANNOT_TELL
                primaryPackaged -> CheckOutcome.PASS
                else -> CheckOutcome.FAIL
            },
            verified = "Read Build.SUPPORTED_ABIS for this device and listed the ABI " +
                "subdirectories of the app's native library directory, which is the " +
                "set the package manager actually extracted for this install.",
            finding = when {
                packaged.isEmpty() -> "This install has no per-ABI library directories " +
                    "to inspect, so what was packaged cannot be determined from here."
                primaryPackaged -> "This phone's primary ABI is $primary and the APK " +
                    "has a $primary directory. The phone can use this build."
                else -> "This phone's primary ABI is $primary, which this APK does " +
                    "not contain. It packaged: ${packaged.joinToString(", ")}. The " +
                    "phone will have to run a 32-bit slice or nothing."
            },
            cannotTell = "Whether the native code in that directory is *correct* for " +
                "the CPU. ABI is a name; an arm64-v8a directory is present on a phone " +
                "whose SoC lacks the instructions the build used is possible, and " +
                "only executing it would show that. It also cannot say why the ABI " +
                "is missing — that is in the build configuration, not on the device.",
            findings = listOf(
                Finding.measured("Device ABIs", supported.joinToString(", "),
                    "Build.SUPPORTED_ABIS on this device, best first"),
                Finding.measured("ABIs packaged in this APK",
                    if (packaged.isEmpty()) "none listed" else packaged.joinToString(", "),
                    "subdirectories of the extracted native library dir"),
            ),
            nextAction = if (!primaryPackaged && packaged.isNotEmpty()) {
                DiagnosticAction.NONE
            } else null,
        )
    }

    // ------------------------------------------------------------------ permissions

    /**
     * Are the app's own permissions actually granted, right now?
     *
     * WHY this reads the platform rather than a stored flag: a permission flag
     * in this project was false forever because nothing ever set it, and the
     * code that read it was correct and the state that fed it was not. Asking
     * `Context.checkSelfPermission` cannot have that failure mode.
     */
    private fun permissionCheck(): DiagnosticCheck {
        val rows = mutableListOf<Finding>()
        var denied = 0
        val sdk = Build.VERSION.SDK_INT

        for (permission in RUNTIME_PERMISSIONS) {
            val applicable = sdk >= permission.sinceApi
            val granted = context.checkSelfPermission(permission.name) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            // Only count a real denial. A permission that does not exist on this
            // API level is not granted and not denied; it is not a question.
            if (applicable && !granted) denied++
            val state = when {
                !applicable -> "not applicable below API ${permission.sinceApi}"
                granted -> "granted"
                else -> "NOT granted"
            }
            rows += Finding.measured(
                permission.name,
                state,
                "Context.checkSelfPermission, asked of the platform on this device " +
                    "(API $sdk)",
            )
        }

        val listener = LocalNotificationListenerService.isGranted(context)
        if (!listener) denied++
        rows += Finding.measured(
            "Notification access",
            if (listener) "granted" else "NOT granted",
            "NotificationManager.isNotificationListenerAccessGranted for this app's " +
                "own listener service",
        )

        val exactAlarms = TaskAlarmScheduler.canScheduleExactAlarms(context)
        if (!exactAlarms) denied++
        rows += Finding.measured(
            "Exact alarms",
            if (exactAlarms) "allowed" else "NOT allowed",
            "AlarmManager.canScheduleExactAlarms(); always allowed before API 31",
        )

        rows += Finding.measured(
            "Battery optimisation",
            if (isIgnoringBatteryOptimisations(context)) "exempt" else "NOT exempt",
            "PowerManager.isIgnoringBatteryOptimizations for this package",
        )

        return DiagnosticCheck(
            id = ID_PERMISSIONS,
            title = "Are this app's permissions actually granted?",
            outcome = if (denied == 0) CheckOutcome.PASS else CheckOutcome.FAIL,
            verified = "Asked the platform directly for each of " +
                "${RUNTIME_PERMISSIONS.size} declared runtime permission(s), plus " +
                "notification access, exact-alarm permission and battery-optimisation " +
                "exemption, on API $sdk. No stored flag was read, because a stored " +
                "flag is exactly what was wrong before.",
            finding = if (denied == 0) {
                "Nothing the app needs is being withheld. All ${rows.size} permission " +
                    "states are shown below, including the ones that are irrelevant " +
                    "on this API level."
            } else {
                "$denied of the permissions this app needs are not granted. Each is " +
                    "listed below with its real state."
            },
            cannotTell = "Why a permission is denied, and whether the user intends to " +
                "grant it. Android does not expose a denial reason, and a denied " +
                "permission is not always a bug — a user who does not want " +
                "notifications has not broken anything. This check reports state, " +
                "not intent. It also cannot tell whether a granted permission will " +
                "actually be honoured: an OEM can restrict background work " +
                "regardless of what the permission says.",
            findings = rows,
            nextAction = when {
                !listener -> DiagnosticAction.OpenNotificationAccess
                !exactAlarms -> DiagnosticAction.OpenExactAlarmSettings
                denied > 0 -> DiagnosticAction.OpenAppSettings
                else -> null
            },
        )
    }

    // ------------------------------------------------------------------ device memory

    /**
     * How much memory does this device actually have available right now?
     *
     * WHY two "available" numbers and not one: this project has two functions
     * that answer "how much memory can this phone afford" — the hub's
     * `AndroidDeviceBudget` and the loader's `RamEstimate.usableDeviceBytes` —
     * and they are *different functions*. One is 55% of `ActivityManager`
     * total memory; the other is 55% of `/proc/meminfo` `MemTotal` capped at
     * six times the JVM heap ceiling. On a device where those disagree, a model
     * can be accepted by the download screen and refused by the loader. Showing
     * one of them would hide that by construction.
     */
    private fun deviceMemoryCheck(): DiagnosticCheck {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        val budget = container.hubBudget
        val hubAvailable = budget.availableRamBytes()
        val loaderAvailable = RamEstimate.usableDeviceBytes()
        val total = info.totalMem
        val rows = buildList {
            add(Finding.measured("Physical RAM", MemoryEstimate.formatBytes(total),
                "ActivityManager.getMemoryInfo().totalMem, read now"))
            add(Finding.measured("Available right now", MemoryEstimate.formatBytes(info.availMem),
                "ActivityManager.getMemoryInfo().availMem, read now"))
            add(Finding.measured("Low-memory threshold",
                MemoryEstimate.formatBytes(info.threshold),
                "the level at which this device reports memory pressure"))
            add(Finding.measured("System reports low memory",
                if (info.lowMemory) "yes" else "no",
                "ActivityManager.getMemoryInfo().lowMemory, read now"))
            add(Finding.computed("What the download gate allows",
                MemoryEstimate.formatBytes(hubAvailable),
                // WHY THE FALLBACK IS NAMED RATHER THAN ASSUMED AWAY: when
                // every route to the real figure failed,
                // `AndroidDeviceBudget` returns a hardcoded 4 GiB. The
                // derivation used to say "55% of ActivityManager totalMem"
                // unconditionally, so on exactly the device where nothing
                // could be read the screen asserted a platform reading it
                // never got. `totalRamBytes() <= 0` is the condition the
                // fallback fires on.
                if (budget.totalRamBytes() > 0L) {
                    "AndroidDeviceBudget: " +
                        "${(dev.localintelligence.android.hub.AndroidDeviceBudget.USABLE_FRACTION * 100).toInt()}% " +
                        "of ActivityManager totalMem — a fraction of a platform " +
                        "reading, not a measurement of free memory"
                } else {
                    "HARDCODED FALLBACK: AndroidDeviceBudget could not read this " +
                        "device's RAM, so it used its built-in 4 GiB constant. " +
                        "This is a guess, not a fact about this phone."
                }))
            // WHY THIS ONE CARRIES THE BRANCH AND NOT A FIXED FORMULA: the
            // derivation the other finding shares is only true when the
            // /proc/meminfo read succeeds. When it fails,
            // `usableDeviceBasis()` returns 6x the JVM heap ceiling alone —
            // a figure derived from the app's own heap that says nothing
            // about the device, and is wrong by more than an order of
            // magnitude on a large phone. Claiming 55% of MemTotal there
            // asserts a reading that was never taken.
            val loaderBasis = RamEstimate.usableDeviceBasis()
            add(Finding.computed("What the loader allows",
                MemoryEstimate.formatBytes(loaderAvailable),
                when (loaderBasis.source) {
                    DeviceBudgetBasis.Source.PHYSICAL_FRACTION ->
                        "RamEstimate.usableDeviceBytes(): 55% of /proc/meminfo " +
                            "MemTotal read on this device, capped at 6x the JVM " +
                            "heap ceiling — a derived budget, not a measurement " +
                            "of free memory"
                    DeviceBudgetBasis.Source.JVM_HEAP_ONLY ->
                        if (loaderBasis.physicalTotalBytes <= 0L) {
                            "GUESS: /proc/meminfo could not be read, so this is " +
                                "6x the JVM heap ceiling alone — a number about " +
                                "this app's heap, not this phone's RAM."
                        } else {
                            "RamEstimate.usableDeviceBytes(): 55% of " +
                                "/proc/meminfo MemTotal came to more than 6x the " +
                                "JVM heap ceiling, so the heap ceiling is the " +
                                "binding term and is what is reported."
                        }
                }))
            if (total > 0L) {
                add(Finding.computed("Free fraction of physical",
                    "%.1f%%".format(info.availMem * 100.0 / total),
                    "availMem over totalMem, both read now"))
            }
        }

        val disagree = hubAvailable != loaderAvailable
        return DiagnosticCheck(
            id = ID_DEVICE_MEMORY,
            title = "How much memory does this phone have available?",
            outcome = if (disagree) CheckOutcome.CANNOT_TELL else CheckOutcome.PASS,
            verified = "Read ActivityManager.getMemoryInfo() for live total, " +
                "available, threshold and pressure flag, then asked both of this " +
                "project's available-RAM functions what they would allow and compared " +
                "the two answers.",
            finding = buildString {
                append("This device has ")
                append(MemoryEstimate.formatBytes(total))
                append(" of RAM and ")
                append(MemoryEstimate.formatBytes(info.availMem))
                append(" free at the moment of this check. ")
                if (disagree) {
                    append("The two functions that decide what this phone can afford do ")
                    append("NOT agree: the download gate allows ")
                    append(MemoryEstimate.formatBytes(hubAvailable))
                    append(" and the loader allows ")
                    append(MemoryEstimate.formatBytes(loaderAvailable))
                    append(
                        ". A model priced against one of them can be accepted and then " +
                            "refused by the other.",
                    )
                } else {
                    append("The download gate and the loader agree on what is available.")
                }
            },
            cannotTell = "What a *loaded model* will actually occupy, which is the only " +
                "number anyone wants. That is a live measurement of a live process, " +
                "and this project has never taken one on a phone — the emulator on " +
                "the development host SIGSEGVs at boot, so it cannot be taken here " +
                "either. `docs/measure/measure_ram.sh` is the procedure and it " +
                "prints '?' rather than inventing a value. Everything above is what " +
                "the device reports about itself, which is not the same thing.",
            findings = rows,
        )
    }

    // ------------------------------------------------------------------ tools

    /**
     * Is the tool registry actually populated?
     *
     * WHY this is a check at all: the tool registry was empty once, and nothing
     * in review could see it. The count here is read from the live registry the
     * agent loop will use, not from a list declared somewhere else.
     */
    private fun toolRegistryCheck(): DiagnosticCheck {
        val tools = container.tools.all()
        val byCategory = tools.groupBy { it.definition.category }.mapValues { it.value.size }
        return DiagnosticCheck(
            id = ID_TOOLS,
            title = "Is the tool registry populated?",
            outcome = if (tools.isEmpty()) CheckOutcome.FAIL else CheckOutcome.PASS,
            verified = "Asked the live ToolRegistry the agent loop will use for its " +
                "full contents, and grouped them by category. This is the registry " +
                "object itself, not a declaration of what it should contain.",
            finding = if (tools.isEmpty()) {
                "The registry is empty. The agent has no tools to call, so a run " +
                    "cannot do anything beyond text."
            } else {
                "${tools.size} tools registered across " +
                    "${byCategory.size} categories: " +
                    byCategory.entries.joinToString(", ") { "${it.key} (${it.value})" } +
                    "."
            },
            cannotTell = "Whether any of these tools *work*. Registration means the " +
                "loop can route a call to the name; it says nothing about whether " +
                "calling it succeeds, which depends on the permissions the previous " +
                "check reports and on the OS underneath.",
            findings = tools.map {
                Finding.measured(
                    it.definition.name,
                    it.definition.category,
                    "read from the live ToolRegistry the agent loop will use",
                )
            },
            nextAction = if (tools.isEmpty()) DiagnosticAction.NONE else null,
        )
    }

    // ------------------------------------------------------------------ shared

    /** A check that could not run, and says what it would have needed. */
    private fun cannotRun(
        id: String,
        title: String,
        verified: String,
        finding: String,
    ): DiagnosticCheck = DiagnosticCheck(
        id = id,
        title = title,
        outcome = CheckOutcome.CANNOT_TELL,
        verified = verified,
        finding = finding,
        cannotTell = "Everything this check would have measured. It needs a model " +
            "file, and this device does not have a readable one. The model-file " +
            "check at the top of this list is where that starts.",
        nextAction = DiagnosticAction.OpenModels,
    )

    private fun describeWarning(warning: GgufWarning): String =
        when (warning) {
            is GgufWarning.KeyValueListTruncated ->
                "key/value list truncated (${warning.parsed} of ${warning.declared})"
            is GgufWarning.TensorTableTruncated ->
                "tensor table truncated (${warning.parsed} of ${warning.declared})"
            is GgufWarning.DeclaredDataExceedsFile ->
                "declares ${warning.declaredEndBytes} bytes but the file is " +
                    "${warning.fileBytes} — truncated download"
            is GgufWarning.ShardedModel ->
                "sharded model, shard ${warning.shardNumber} of ${warning.shardCount} — " +
                    "this app loads single files"
            is GgufWarning.ContextLengthMissing ->
                "no context length in the header; ${warning.assumed} assumed"
            is GgufWarning.ContextLengthClamped ->
                "context ${warning.declared} clamped to ${warning.clampedTo}"
            is GgufWarning.FileTypeDisagreesWithTensors ->
                "file type and tensor quantisation disagree"
            is GgufWarning.ArchitectureInferred ->
                "architecture inferred as ${warning.inferred}"
            is GgufWarning.ImplausibleDimensions ->
                "implausible dimensions for ${warning.tensor}: ${warning.dimensions}"
            is GgufWarning.ImplausibleValue ->
                "implausible value for ${warning.key}: ${warning.raw}"
            is GgufWarning.DuplicateKey ->
                "duplicate metadata key ${warning.key}"
            is GgufWarning.UnknownValueType ->
                "unknown value type ${warning.typeId} for key ${warning.key}"
        }

    private companion object {
        /**
         * The router's own window, not a shorter one.
         *
         * WHY THIS IS NOT A CONSTANT I CHOSE: the magic match once used an
         * 8-byte window for an 8-byte magic and could only ever match at offset
         * 0. This screen reads 16 so its evidence covers both the 0 and 4
         * placements. It still asks the router for the verdict — this window is
         * for the *evidence line*, not for the decision.
         */
        const val MAGIC_WINDOW = 16

        const val ID_MODEL_FILE = "model-file"
        const val ID_MAGIC_ROUTING = "magic-routing"
        const val ID_HEADER = "gguf-header"
        const val ID_RAM_GATE = "ram-gate-vs-loader"
        const val ID_NATIVE = "native-library"
        const val ID_ABI = "abi-present"
        const val ID_PERMISSIONS = "permissions"
        const val ID_DEVICE_MEMORY = "device-memory"
        const val ID_TOOLS = "tool-registry"

        /**
         * The runtime permissions this app's manifest declares.
         *
         * Listed explicitly rather than read from the manifest, because a
         * manifest-parsing failure would then silently reduce this check to
         * "no permissions to check" and it would pass — the same false-clean
         * shape as an empty tool registry. An explicit list cannot be emptied
         * by accident.
         *
         * The second element of each pair is the API level at which the
         * permission exists. Below it, `checkSelfPermission` reports GRANTED
         * for a permission that has no meaning, and showing that as "granted"
         * would be a second kind of lie. Each row is annotated instead.
         */
        val RUNTIME_PERMISSIONS = listOf(
            RuntimePermission("android.permission.POST_NOTIFICATIONS", 33),
            RuntimePermission("android.permission.READ_CONTACTS", 1),
            RuntimePermission("android.permission.READ_CALENDAR", 1),
            RuntimePermission("android.permission.WRITE_CALENDAR", 1),
            RuntimePermission("android.permission.READ_MEDIA_IMAGES", 33),
            RuntimePermission("android.permission.READ_MEDIA_VIDEO", 33),
            RuntimePermission("android.permission.READ_MEDIA_AUDIO", 33),
        )
    }

    /**
     * A declared runtime permission and the API level it appeared at.
     *
     * WHY IT IS NOT A NAMED CONSTANT PER PERMISSION: the screen renders the
     * raw manifest string, so a user comparing this against their own Settings
     * screen is looking at the same identifier rather than a friendly
     * abbreviation the platform does not use.
     */
    data class RuntimePermission(val name: String, val sinceApi: Int)
}

/**
 * Builds the [Intent] for a [DiagnosticAction], or null when there is none.
 *
 * WHY THIS IS A SEPARATE FUNCTION AND NOT A LAMBDA IN THE CHECK: a failure has
 * to be able to *say where it goes* before the user presses anything, so the
 * screen renders the target as text. A callback cannot be rendered.
 *
 * The four actions that coincide with a [TaskAction] are routed through
 * [TaskAction.intentFor] so a scheduled task and a diagnostic cannot disagree
 * about where "fix this" goes.
 */
fun DiagnosticAction.intentFor(context: Context): Intent? = when (this) {
    DiagnosticAction.OpenModels -> null // an in-app route, not a system screen

    // The three that coincide with a TaskAction are resolved by that enum's own
    // function. Duplicating the system actions here would mean a scheduled task
    // and a diagnostic could send a user to two different Settings screens for
    // the same underlying permission, and neither would be wrong enough to be
    // caught in review.
    DiagnosticAction.OpenNotificationAccess -> intentForTaskAction(
        context, TaskAction.OpenNotificationAccess,
    )
    DiagnosticAction.OpenBatterySettings -> intentForTaskAction(
        context, TaskAction.OpenBatterySettings,
    )
    DiagnosticAction.OpenExactAlarmSettings -> intentForTaskAction(
        context, TaskAction.OpenExactAlarmSettings,
    )

    DiagnosticAction.OpenAppSettings -> Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    DiagnosticAction.NONE -> null
}

/**
 * The one line of user-facing text that says where a button goes.
 *
 * WHY THE TEXT AND NOT JUST THE INTENT: a failure has to be able to say where
 * it goes *before* the user presses anything, so the card can render the target
 * next to the button. An Intent is not printable and neither is a lambda.
 */
fun DiagnosticAction.describe(): String = when (this) {
    DiagnosticAction.OpenModels -> "Open the model manager"
    DiagnosticAction.OpenNotificationAccess -> "Open notification access settings"
    DiagnosticAction.OpenBatterySettings -> "Open battery optimisation settings"
    DiagnosticAction.OpenExactAlarmSettings -> "Open exact alarm settings"
    DiagnosticAction.OpenAppSettings -> "Open this app's settings"
    DiagnosticAction.NONE -> "No user-side fix exists for this one"
}

/**
 * Whether this app is exempt from battery optimisation.
 *
 * Not one of the named checks — a scheduled task that never fires because the
 * phone is asleep is a real user outcome, and this is the fact that explains
 * it. Exposed here rather than in the check so the screen can mention it in
 * the permissions summary without a fourteenth card.
 */
fun isIgnoringBatteryOptimisations(context: Context): Boolean = try {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(context.packageName)
} catch (_: Throwable) {
    false
}
