package dev.localintelligence.core.metrics

import dev.localintelligence.core.execution.NoProgressWatchdog
import dev.localintelligence.core.hub.DeviceBudget
import dev.localintelligence.core.hub.FitGate
import dev.localintelligence.core.hub.GgufQuant
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.core.hub.PreDownloadMemoryModel
import dev.localintelligence.core.model.gguf.EstimateBasis
import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.MemoryEstimate
import dev.localintelligence.core.model.gguf.ModelMemoryEstimator

/**
 * Where a number on the self-check screen came from.
 *
 * ## This is `docs/memory-model.md`'s vocabulary, extended — not replaced
 *
 * That document already separates **MEASURED** from **ESTIMATED**, and this enum
 * is the same distinction with the middle case made explicit:
 *
 * | here | there | meaning |
 * |---|---|---|
 * | [MEASURED] | MEASURED | read off *this* device, right now, by a platform API |
 * | [COMPUTED] | ESTIMATED | derived, with the inputs shown next to it |
 * | [UNKNOWN] | the "not measured" section | nothing to derive from; we cannot tell |
 *
 * The split that matters is [COMPUTED] versus [UNKNOWN], and `ESTIMATED` alone
 * cannot express it. "Estimated from the tensor table" and "assumed 7B
 * parameters because the file name was silent" are both ESTIMATED, and they are
 * not remotely the same claim — the first is right for nearly every real file
 * and the second is 636% out on the one case that mattered ([Phi-3-mini], see
 * `PreDownloadMemoryModel.resolveParameterCount`). A screen that labelled both
 * "estimated" is the screen this project is trying not to build.
 *
 * ## Why it is an enum and not a boolean
 *
 * A boolean is `isMeasured`, and a UI that renders it as a green tick next to a
 * number cannot help but imply the number is trustworthy. Three states force the
 * third one to be drawn.
 *
 * [UNKNOWN] is not a failure. It is the honest answer to a question this build
 * cannot answer, and it is the state [docs/measure/measure_ram.sh] exists to
 * move.
 */
enum class Provenance(
    /** One line a user reads when they ask "where did this come from?". */
    val label: String,
) {
    /** Read off this device, now, by a platform API. There is no model in it. */
    MEASURED("measured"),

    /** Derived. The inputs are carried in [Finding.derivation] and shown. */
    COMPUTED("computed"),

    /** There is nothing to derive from. This project does not know. */
    UNKNOWN("unknown"),
}

/**
 * One number, with the provenance and the arithmetic that produced it.
 *
 * WHY THIS IS A TYPE AND NOT A STRING: a screen that renders
 * `"${formatBytes(bytes)}"` has already thrown away the only thing that makes
 * the number checkable. [derivation] is mandatory, so a caller cannot show a
 * computed figure without also saying what it was computed from — and for
 * [Provenance.UNKNOWN] it is the sentence explaining *why* nothing is known,
 * which is the sentence that tells the user what to go and do.
 */
data class Finding(
    val label: String,
    val value: String,
    val provenance: Provenance,
    /**
     * The inputs, the formula, or the reason there are none.
     *
     * Never blank. A finding with nothing to say about its own provenance is a
     * finding that should have been [Provenance.UNKNOWN] with an explanation.
     */
    val derivation: String,
) {
    init {
        require(label.isNotBlank()) { "a finding needs a label" }
        require(derivation.isNotBlank()) {
            "finding '$label' has no derivation; that is the one thing it must carry"
        }
    }

    companion object {
        /** A value read off this device by a platform API, right now. */
        fun measured(label: String, value: String, how: String): Finding =
            Finding(label, value, Provenance.MEASURED, how)

        /** A derived value. [from] names the inputs; it must be specific. */
        fun computed(label: String, value: String, from: String): Finding =
            Finding(label, value, Provenance.COMPUTED, from)

        /** Something this build cannot determine, and says why. */
        fun unknown(label: String, why: String): Finding =
            Finding(label, "not measured", Provenance.UNKNOWN, why)
    }
}

/**
 * The three answers a check can give.
 *
 * [CANNOT_TELL] is a first-class outcome, not a soft [PASS] and not a [FAIL].
 * Every bug in this project's recent history was invisible to review and obvious
 * on one run: an empty tool registry, models scanned but never loaded, two GGUF
 * parsers that disagreed by 3.4x, a permission flag that was false forever, an
 * 8-byte magic window for an 8-byte magic. A screen with only pass/fail has to
 * render "I could not determine this" as one of those two, and both are lies.
 */
enum class CheckOutcome {
    /** Verified, and the thing checked is in the state it should be in. */
    PASS,

    /** Verified, and the thing checked is broken. [DiagnosticCheck.nextAction] is set. */
    FAIL,

    /** The check could not form an answer. [DiagnosticCheck.cannotTell] says why. */
    CANNOT_TELL,
}

/**
 * Where a failed check sends the user.
 *
 * A closed set rather than a lambda, because the screen has to be able to say
 * *before* the user presses anything where the button goes, and a callback
 * cannot be described in a string. [NONE] exists for the real case of a failure
 * with no user-side fix — a wrong APK, a missing ABI for this device — where the
 * button would be a lie.
 *
 * The four values that coincide with
 * [dev.localintelligence.app.data.TaskAction] are resolved through that enum's
 * own `intentForTaskAction`, so the Intent and the wording stay single-sourced
 * and a scheduled task and a diagnostic cannot disagree about where "Fix" goes.
 */
enum class DiagnosticAction {
    /** The in-app model manager. */
    OpenModels,

    /** The system notification-access screen for this app. */
    OpenNotificationAccess,

    /** The system's list of apps that may ignore battery optimisation. */
    OpenBatterySettings,

    /** The system screen for "Alarms and reminders" app access. */
    OpenExactAlarmSettings,

    /** This app's own entry in system Settings. */
    OpenAppSettings,

    /** No user-side fix exists. The check says what a developer would have to do. */
    NONE,
}

/**
 * One check, reported so a user can act on it without a developer.
 *
 * The three prose fields are mandatory and are the whole design:
 *
 *  - [verified] — what was actually done, in enough detail that "it says fine"
 *    is checkable. "Read the file header" is verifiable; "checked the model" is
 *    not.
 *  - [finding] — what came back, in the same terms.
 *  - [cannotTell] — what this check *cannot* establish, always non-empty. A
 *    check that can tell you everything has not been written yet, and a blank
 *    here would be the screen quietly implying more than it knows.
 */
data class DiagnosticCheck(
    /** Stable, kebab-case. For a bug report. */
    val id: String,
    /** What the user asked, in their words. */
    val title: String,
    val outcome: CheckOutcome,
    /** What was actually done. */
    val verified: String,
    /** What came back. */
    val finding: String,
    /** What this cannot establish. Never blank. */
    val cannotTell: String,
    val findings: List<Finding> = emptyList(),
    /**
     * Where to go next, or null when there is nothing to press.
     *
     * Non-null on [CheckOutcome.FAIL] unless [DiagnosticAction.NONE] applies,
     * in which case it is [DiagnosticAction.NONE] and the text says what would
     * have to change.
     */
    val nextAction: DiagnosticAction? = null,
) {
    init {
        require(id.isNotBlank()) { "a check needs a stable id" }
        require(title.isNotBlank()) { "check '$id' needs a title" }
        require(verified.isNotBlank()) { "check '$id' must say what it verified" }
        require(finding.isNotBlank()) { "check '$id' must say what it found" }
        require(cannotTell.isNotBlank()) {
            "check '$id' cannot state what it cannot tell; a check that can tell " +
                "everything has not been written yet, and a blank here is the " +
                "screen implying more than it knows"
        }
    }

    /** A [CheckOutcome.FAIL] with nothing to send the user to. */
    val isUnfixableHere: Boolean
        get() = outcome == CheckOutcome.FAIL && nextAction == DiagnosticAction.NONE
}

/**
 * A whole run of checks, and the counts a header needs.
 *
 * [ranAtMillis] is wall-clock and is the one number here that is not a
 * measurement of the device's capability: it is a timestamp, labelled as such
 * by the screen. The run itself is measured by the monotonic clock inside
 * [RunMetrics]; nothing in this report is.
 */
data class DiagnosticReport(
    val checks: List<DiagnosticCheck>,
    val ranAtMillis: Long,
) {
    val failures: List<DiagnosticCheck> get() = checks.filter { it.outcome == CheckOutcome.FAIL }
    val unknowns: List<DiagnosticCheck> get() = checks.filter { it.outcome == CheckOutcome.CANNOT_TELL }
    val passes: List<DiagnosticCheck> get() = checks.filter { it.outcome == CheckOutcome.PASS }

    /**
     * Whether anything needs a human.
     *
     * [CANNOT_TELL] counts. A screen whose header says "all good" while three
     * checks could not form an answer is the exact failure this project keeps
     * producing, and the header is where a user decides whether to keep digging.
     */
    val needsAttention: Boolean get() = failures.isNotEmpty() || unknowns.isNotEmpty()

    /** One sentence for the screen header. Names the counts, claims nothing. */
    fun headline(): String = buildString {
        append("${checks.size} checks: ")
        append("${passes.size} verified, ")
        append("${failures.size} broken")
        if (unknowns.isNotEmpty()) append(", ${unknowns.size} cannot be determined")
        append('.')
    }

    fun byId(id: String): DiagnosticCheck? = checks.firstOrNull { it.id == id }
}

/**
 * The RAM gate's verdict against the loader's own gate, on one real file.
 *
 * ## Why this check is worth more than a framework
 *
 * These two numbers disagreed by 8x until recently and the disagreement was
 * invisible to review in both directions. `LlamaCppBackend` used to allocate
 * `meta.contextLength` — a model's *trained* length — while the gate priced
 * `DEFAULT_CONTEXT_LENGTH` = 4096. A model trained at 32K therefore got a 32K KV
 * cache against a gate that had agreed to 4K, and the gate's promise that the
 * model fits was false before the first token. In the other direction
 * `PreDownloadMemoryModel.DEFAULT_CONTEXT_LENGTH` was 2048, so the *download*
 * screen priced half the cache the app goes on to allocate.
 *
 * Both are now the same constant, and both bugs are the kind that survive review
 * because each file's comment is individually correct. So the check exists to
 * make the agreement observable at run time, on the user's actual file, rather
 * than to be re-derived by the next person who changes one of them.
 *
 * ## What it actually compares
 *
 * - **the gate**: [FitGate.ramFit] with [PreDownloadMemoryModel] — the
 *   name-plus-size bound the hub and the download screen showed, including
 *   `FitGate.DECISION_FACTOR`.
 * - **the loader**: [ModelMemoryEstimator] on the real header at
 *   [contextLength] — the figure `AppContainer.loadModel` gates on through
 *   `ImportedModel.fitsOnDevice`.
 *
 * And it compares each against a *different* available-RAM figure, because they
 * genuinely are different functions:
 * `AndroidDeviceBudget.availableRamBytes()` is 55% of `ActivityManager.totalMem`
 * with a 4 GB fallback, and `RamEstimate.usableDeviceBytes()` is 55% of
 * `/proc/meminfo` `MemTotal` capped at 6x the JVM heap ceiling. Two code paths,
 * two definitions of "what this device can afford" — which is the same class of
 * defect as two parsers, and is checked here for the same reason.
 *
 * ## What it cannot tell
 *
 * Whether the model will actually load. That is a native call, it takes tens of
 * seconds and gigabytes, and this check must stay cheap enough to run on every
 * screen open. The number that would settle it is `dumpsys meminfo` with the
 * model resident, and `docs/measure/measure_ram.sh` is the procedure.
 */
object RamGateCrossCheck {

    /**
     * @param fileName the file's name. Only its *name* is read, for the quant
     *   label and the parameter count — which is the point: the gate sees nothing
     *   else, so the check must not either.
     * @param fileBytes the real byte length.
     * @param header the same file's parsed header, or null when it could not be
     *   parsed. Null is a legitimate state and downgrades the check to
     *   [CheckOutcome.CANNOT_TELL] with the parse reason attached, because the
     *   loader's own gate cannot be evaluated without it.
     * @param contextLength the context the app will allocate. Pass the loader's
     *   constant, not the file's declared one — that substitution is the 8x bug.
     * @param budget the device budget the *gate* uses.
     * @param loaderAvailableBytes the budget the *loader* uses. A separate
     *   parameter because it is a separate function in a separate module, and a
     *   check that quietly substituted one for the other would be the defect it
     *   exists to catch.
     */
    fun run(
        fileName: String,
        fileBytes: Long,
        header: GgufHeader?,
        contextLength: Int,
        budget: DeviceBudget,
        loaderAvailableBytes: Long,
    ): DiagnosticCheck {
        val quant = GgufQuant.fromFileName(fileName)
        val parameters = HuggingFaceClient.parseParameterCount(fileName)

        val gate = FitGate.ramFit(
            fileBytes = fileBytes,
            quant = quant,
            contextLength = contextLength,
            budget = budget,
            model = PreDownloadMemoryModel,
            parameterCount = parameters,
        )

        if (header == null) {
            return DiagnosticCheck(
                id = ID,
                title = "Does the RAM gate agree with what the loader will allocate?",
                outcome = CheckOutcome.CANNOT_TELL,
                verified = "Ran the download-time RAM gate on the file name and size " +
                    "($fileName, $fileBytes bytes, context $contextLength), then tried to " +
                    "read the GGUF header the loader's own gate reads.",
                finding = "The gate says ${gate.fits}, but the header would not parse, so " +
                    "there is no loader-side figure to compare it against.",
                cannotTell = "The loader's gate is `estimate.totalBytes(4096) <= " +
                    "usableDeviceBytes()`. Without a parsed header that expression cannot " +
                    "be evaluated at all, so this check reports the gate's opinion and " +
                    "nothing about whether it is right. The header parse failure is on the " +
                    "model-file check, with the parser's own reason.",
                findings = listOf(
                    Finding.computed(
                        "RAM gate verdict",
                        if (gate.fits) "fits" else "does not fit",
                        "FitGate.ramFit on ${fileBytes} bytes, context $contextLength, " +
                            "quant ${quant?.label ?: "not in the file name"}, " +
                            "parameters ${parameters ?: "not in the file name"}; " +
                            "x${FitGate.DECISION_FACTOR} decision factor applied",
                    ),
                ),
            )
        }

        val estimate = ModelMemoryEstimator().estimate(
            header = header,
            contextLengthOverride = contextLength.toLong(),
        )
        val loaderFits = estimate.totalBytes <= loaderAvailableBytes
        val gateTotal = gate.totalBytes

        // The direction that matters: a gate that says yes and a loader that says
        // no is an OOM the user pays for after the download. The reverse is an
        // annoyance. Both are reported; the first is why this is a FAIL.
        val disagreement = when {
            gate.fits && !loaderFits -> "the gate says this model fits and the loader " +
                "would refuse it. The user pays for the download and is then OOM-killed."
            !gate.fits && loaderFits -> "the gate refuses a model the loader would accept. " +
                "Annoying rather than fatal, and still a disagreement."
            else -> null
        }
        val ratio = if (estimate.totalBytes > 0L) gateTotal.toDouble() / estimate.totalBytes else 0.0
        val basisText = when (estimate.basis) {
            EstimateBasis.TENSOR_TABLE -> "the tensor table (exact)"
            EstimateBasis.PARAMETER_COUNT -> "a declared parameter count x bits per weight"
            EstimateBasis.FILE_SIZE -> "the file size; the tensor table was not readable"
            EstimateBasis.UNKNOWN -> "nothing — no tensor table, no parameter count, no size"
        }

        val findings = buildList {
            add(
                Finding.computed(
                    "RAM gate says",
                    "${MemoryEstimate.formatBytes(gateTotal)} (low " +
                        "${MemoryEstimate.formatBytes(gate.lowBytes)}, high " +
                        "${MemoryEstimate.formatBytes(gate.highBytes)})",
                    "PreDownloadMemoryModel from the file name, size and context only, " +
                        "x${FitGate.DECISION_FACTOR} decision factor",
                ),
            )
            add(
                Finding.computed(
                    "Loader's own gate says",
                    "${MemoryEstimate.formatBytes(estimate.totalBytes)} " +
                        "(${if (loaderFits) "fits" else "does not fit"})",
                    "ModelMemoryEstimator on the parsed header at context $contextLength, " +
                    "weights from $basisText",
                ),
            )
            add(
                Finding.measured(
                    "Available to the gate",
                    MemoryEstimate.formatBytes(gate.availableBytes),
                    "AndroidDeviceBudget: 55% of ActivityManager.totalMem on this device",
                ),
            )
            add(
                Finding.measured(
                    "Available to the loader",
                    MemoryEstimate.formatBytes(loaderAvailableBytes),
                    "RamEstimate.usableDeviceBytes(): 55% of /proc/meminfo MemTotal, " +
                        "capped at 6x the JVM heap ceiling",
                ),
            )
            if (disagreement != null) {
                add(Finding.computed("Ratio, gate over loader", "%.2fx".format(ratio), "the two totals above"))
            }
        }

        // The question this check asks is "do the two agree", not "does the model
        // fit". A model both of them refuse is agreement, and reporting that as
        // a pass would be wrong in the other direction — it would look like the
        // screen had checked fitness. So: disagreement is the failure, and
        // agreeing-that-it-does-not-fit is a pass whose next action is still to
        // go and choose a smaller model.
        val outcome = if (disagreement != null) CheckOutcome.FAIL else CheckOutcome.PASS
        val needsSmallerModel = !gate.fits && !loaderFits

        return DiagnosticCheck(
            id = ID,
            title = "Does the RAM gate agree with what the loader will allocate?",
            outcome = outcome,
            verified = "Ran FitGate.ramFit on the file name, size and context " +
                "($contextLength) — the bound the download screen showed — and " +
                "ModelMemoryEstimator on the same file's parsed header at the same " +
                "context, which is the figure AppContainer.loadModel gates on. Compared " +
                "each against its own module's available-RAM function.",
            finding = disagreement ?: buildString {
                append("Both say ")
                append(if (gate.fits) "it fits" else "it does not fit")
                append(". The gate's figure is ")
                append("%.2fx".format(ratio))
                append(" the loader's (")
                append(MemoryEstimate.formatBytes(gateTotal))
                append(" against ")
                append(MemoryEstimate.formatBytes(estimate.totalBytes))
                append("). ")
                append(
                    "Both include an unmeasured runtime allowance of " +
                        MemoryEstimate.formatBytes(estimate.overheadBytes) +
                        " — max(64 MiB, 2% of weights) — which has never been measured " +
                        "on a device, so the agreement is between two estimates and not " +
                        "between an estimate and reality.",
                )
            },
            cannotTell = "Whether the model will actually load. That is a native call " +
                "worth tens of seconds and gigabytes of RAM, and this check runs on " +
                "every screen open, so it does not attempt it. Nor can it tell you the " +
                "process's real resident cost: no `dumpsys meminfo` number has ever been " +
                "taken on a phone in this project, because the emulator on the " +
                "development host SIGSEGVs at boot. `docs/measure/measure_ram.sh` is the " +
                "procedure, and it prints '?' rather than inventing a value.",
            findings = findings,
            nextAction = when {
                disagreement != null || needsSmallerModel -> DiagnosticAction.OpenModels
                else -> null
            },
        )
    }

    const val ID = "ram-gate-vs-loader"
}

/**
 * The run's own numbers, and the standing statement about what is not measured.
 *
 * ## Why the performance figures are UNKNOWN and not zero
 *
 * This project contains no measured RAM, latency or token-rate figure for a
 * phone. The only decode number that exists anywhere in it is ~0.66 tok/s, and
 * that was taken on an **x86_64 Android emulator** whose CPU is emulated. It is
 * not a phone number and presenting it as one would be the single worst thing
 * this screen could do.
 *
 * So a diagnostics screen that renders performance figures has exactly two
 * honest options: show nothing, or show the absence. This shows the absence, at
 * [Provenance.UNKNOWN], with the procedure next to it — which is what makes the
 * gap visible instead of implied. A screen with a "0.0 tok/s" row is a screen
 * claiming it measured zero.
 */
object RunPerformanceCheck {

    const val ID = "measured-performance-figures"

    /**
     * Always [CheckOutcome.CANNOT_TELL].
     *
     * Not a parameter and not a computation: no input can make this project
     * have measured a phone's decode rate, so a function of its inputs would be
     * a lie with extra steps.
     */
    fun unknown(): DiagnosticCheck = DiagnosticCheck(
        id = ID,
        title = "How fast does this phone generate?",
        outcome = CheckOutcome.CANNOT_TELL,
        verified = "Looked for a measured throughput figure in this project: a decode " +
            "rate, a first-token latency, or a `dumpsys meminfo` resident cost taken on " +
            "physical hardware.",
        finding = "There are none. Zero measured performance figures exist for a real " +
            "device. The only decode number anywhere in this repository is about 0.66 " +
            "tok/s, and it was measured on an x86_64 Android emulator with an emulated " +
            "CPU. It is not a phone number and it is not shown as one.",
        cannotTell = "Everything about this phone's speed. Decode rate, time to first " +
            "token, peak resident memory during a run — all unknown, and no amount of " +
            "reading this app will produce them. What this screen can show you instead " +
            "is what a *run* on this phone actually did: the token counts and per-phase " +
            "durations the loop measured during that one run, which is a real " +
            "measurement of that run and not a specification of the hardware.",
        findings = listOf(
            Finding.unknown(
                "Decode throughput",
                "Never measured on a phone. The ~0.66 tok/s figure in this repository " +
                    "is an x86_64 emulator artefact and must not be quoted as a device " +
                    "number.",
            ),
            Finding.unknown(
                "Resident cost of a loaded model",
                "Never measured on a phone. `docs/measure/measure_ram.sh` takes it: " +
                    "dumpsys meminfo plus smaps_rollup, idle and loaded, and it prints " +
                    "'?' for anything the device would not report.",
            ),
            Finding.unknown(
                "Time to first token",
                "Not instrumented. `RunMetrics.prefillMs` and `decodeMs` are summed " +
                    "over a whole run rather than sampled per token, so a first-token " +
                    "latency cannot be recovered from them.",
            ),
        ),
    )
}

/**
 * The last recorded run, and an honest account of the watchdog.
 *
 * ## Why the watchdog is described here at all
 *
 * [NoProgressWatchdog] has two rules. The counting rule — N consecutive steps
 * whose observation text is byte-identical to the last one — terminates a run,
 * and it is the one production uses. The wall-clock rule fires after 60s with no
 * change, and it is **deliberately disabled**: `AgentController` constructs
 * `NoProgressWatchdog(stallTimeoutMs = null)`, because between two observations
 * sits a model generation, and on a phone-sized CPU a small model can spend
 * longer than 60s inside a single decode. A wall-clock rule there kills a healthy
 * run mid-answer and reports it as "no progress".
 *
 * So a slow phase in the numbers below is **not** evidence of a stall and must
 * not be rendered as one. This check says so in the same breath as it shows the
 * duration, because the alternative is a user reading "90 seconds" and concluding
 * the app hung, when what actually happened is that it was working.
 *
 * ## Why [latest] may be null
 *
 * [RunMetricsJournal] is populated by [RunRecorder.finish]. On this build
 * `ExecutionService` builds the agent controller with `metrics = null` — no
 * [RunRecorder] is ever constructed — so the journal is empty and this check
 * reports that. That is a wiring gap in a file this change does not own, and the
 * exact one-line fix is in the PR description. Reporting an empty journal as
 * "0 steps, 0 tokens" would be the false-clean failure mode this project
 * produces most often.
 */
object RunMetricsCheck {

    const val ID = "last-run-metrics"

    fun run(latest: RunMetrics?): DiagnosticCheck {
        if (latest == null) {
            return DiagnosticCheck(
                id = ID,
                title = "What did the last run actually do?",
                outcome = CheckOutcome.CANNOT_TELL,
                verified = "Asked RunMetricsJournal for the most recent RunMetrics " +
                    "published by a RunRecorder in this process.",
                finding = "The journal is empty. No run in this process has recorded " +
                    "metrics, because no RunRecorder has been attached to the agent " +
                    "controller — ExecutionService builds it with `metrics = null`. The " +
                    "run metrics this project has always recorded were never recorded " +
                    "on this path.",
                cannotTell = "Steps, tokens, tool latencies and the outcome of the last " +
                    "run. Those are measured by RunRecorder while a run happens and " +
                    "published to the journal when it ends; with no recorder attached, " +
                    "nothing is measured and this screen has no numbers to show. The " +
                    "tool latencies already visible in the chat transcript come from " +
                    "StepTrace, which is recorded on every path and is a different " +
                    "measurement from a different place — it is not a substitute here.",
                nextAction = DiagnosticAction.NONE,
            )
        }

        val tokenRows = buildList {
            add(
                Finding.measured(
                    "Steps / tool calls / rejected calls",
                    "${latest.steps} / ${latest.toolCalls} / ${latest.invalidToolCalls}",
                    "counted by RunRecorder during the run; duplicate attempts: " +
                        "${latest.duplicateCalls}",
                ),
            )
            add(
                Finding.measured(
                    "Tokens generated",
                    "${latest.outputTokens}",
                    "reported by the backend, not estimated: only the model knows how " +
                        "many tokens it emitted",
                ),
            )
            add(
                Finding.measured(
                    "Run wall time",
                    "${latest.totalMs} ms",
                    "monotonic clock, System.nanoTime, anchored at recorder construction",
                ),
            )
            if (latest.decodeMs > 0L && latest.outputTokens > 0) {
                add(
                    Finding.computed(
                        "This run's decode rate",
                        "%.2f tok/s".format(latest.decodeTokensPerSecond),
                        "${latest.outputTokens} generated tokens over ${latest.decodeMs} ms " +
                            "of decode time, summed from the backend's own per-call figures. " +
                            "This is what one run on this phone did. It is not a " +
                            "specification of the phone and not comparable across devices.",
                    ),
                )
            }
        }

        val slowest = latest.stepTimings.maxByOrNull { it.durationMs }
        val slowestNote = if (slowest != null && slowest.durationMs > STALL_RULE_MS) {
            " The slowest phase was ${slowest.phase} at ${slowest.durationMs} ms, which " +
                "is past the watchdog's ${STALL_RULE_MS} ms wall-clock threshold. That " +
                "threshold is DISABLED in production — AgentController passes " +
                "`stallTimeoutMs = null` — because on a phone-sized CPU a small model " +
                "spends longer than that inside one decode. A slow phase here is the " +
                "app working, not stalling, and is not reported as a failure."
        } else {
            ""
        }

        return DiagnosticCheck(
            id = ID,
            title = "What did the last run actually do?",
            outcome = CheckOutcome.PASS,
            verified = "Read the most recent RunMetrics the loop published: one pass over " +
                "${latest.steps} steps, ${latest.stepTimings.size} timed phases and " +
                "${latest.toolCalls} executed tool calls.",
            finding = (if (latest.success) "The run succeeded" else "The run did not succeed") +
                ". ${latest.steps} steps, ${latest.toolCalls} tool calls executed, " +
                "${latest.outputTokens} tokens generated, ${latest.totalMs} ms total." +
                slowestNote,
            cannotTell = "Whether this run is typical. One run is a single sample: it " +
                "depends on the task, the context length, the model's quantisation and " +
                "whether the phone was busy, and no p95 or mean can be computed from n=1. " +
                "It is also not comparable with any other device — a decode rate is a " +
                "statement about a run, not about hardware.",
            findings = tokenRows,
        )
    }

    /**
     * The watchdog's wall-clock threshold, read from the class rather than
     * repeated here.
     *
     * WHY THIS IS A CONSTANT AND NOT A RULE: the value is quoted so a user can
     * see that the disabled rule exists and why. Nothing in this file acts on it.
     * Re-enabling it would kill valid generation on a slow phone, which is the
     * bug it was turned off for.
     */
    private const val STALL_RULE_MS = NoProgressWatchdog.DEFAULT_STALL_TIMEOUT_MS
}

/**
 * The most recent [RunMetrics] in this process, in memory, bounded.
 *
 * ## What this is not
 *
 * It is not telemetry, not a log file, not an event bus and not a framework.
 * There is no subscriber, no callback, no queue, no thread, no serialisation and
 * no I/O of any kind: [record] appends to a fixed-size list under a lock and
 * [recent] reads it. It exists so one screen can show a run's own numbers
 * without a second recording path, and it dies with the process.
 *
 * ## Why it is here at all
 *
 * [RunRecorder] already produces exactly the right record. What was missing is
 * anywhere for it to *go* that a screen can read — and the result was the worst
 * kind of dead code: a complete, tested, correct metrics layer that no
 * production path ever constructed. `AgentController` holds
 * `lastRunMetrics`, the controller is created per run inside `ExecutionService`,
 * and the controller is then discarded. Nothing survived it.
 *
 * This is that somewhere. [RunRecorder.finish] publishes here, so the one-line
 * change that attaches a recorder to the controller makes the numbers appear
 * here with no further edit. Until then [recent] is empty, and
 * [RunMetricsCheck] says so rather than rendering zeros.
 *
 * ## Bounded on purpose
 *
 * A ring of [CAPACITY]. A phone that has run a hundred agent tasks must not be
 * holding a hundred metric records for a screen to page through, and the
 * alternative — keeping everything — is a memory leak dressed as a feature.
 */
object RunMetricsJournal {

    /** How many finished runs are kept. Oldest are dropped first. */
    const val CAPACITY = 16

    private val lock = Any()
    private val entries = ArrayDeque<RunMetrics>(CAPACITY)

    /**
     * Publishes one finished run, dropping the oldest when full.
     *
     * WHY IT NEVER THROWS: this is called from [RunRecorder.finish], which
     * [dev.localintelligence.core.agent.AgentController] calls on the path that
     * returns a run's result. In a process that has just allocated gigabytes of
     * native memory for a model, an allocation failure while appending to a
     * metrics list is not hypothetical, and losing a diagnostic record is
     * strictly better than losing the user's run. Hence the blanket catch: this
     * function must not be able to fail a run it is only observing.
     */
    fun record(metrics: RunMetrics) {
        try {
            synchronized(lock) {
                if (entries.size >= CAPACITY) entries.removeFirst()
                entries.addLast(metrics)
            }
        } catch (_: Throwable) {
            // Deliberately swallowed. See the KDoc.
        }
    }

    /** Newest first. A copy: the caller cannot mutate the journal. */
    fun recent(): List<RunMetrics> = synchronized(lock) { entries.toList().asReversed() }

    /** The most recent run, or null when nothing has been recorded. */
    fun latest(): RunMetrics? = synchronized(lock) { entries.lastOrNull() }

    /** How many runs are held. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** Empties the journal. For a test or a deliberate reset; nothing calls it in production. */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
