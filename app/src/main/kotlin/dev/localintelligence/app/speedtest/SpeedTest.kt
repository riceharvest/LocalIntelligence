package dev.localintelligence.app.speedtest

import dev.localintelligence.android.inference.LlamaCompute
import dev.localintelligence.android.inference.LlamaCppBackend
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.app.settings.ComputeChoice
import dev.localintelligence.core.execution.RunGate
import dev.localintelligence.core.model.AcceleratorAware
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The loaded model a speed test measures: the backend holding it, its URI as the
 * app knows it, and the file name shown to the user.
 */
class SpeedTestTarget(
    val backend: ModelBackend,
    val modelUri: String,
    val modelFile: String,
    /** Set when Compute is Auto: the test then also measures the model's other places to run. */
    val comparison: ComputeComparison? = null,
    /** Why nothing was compared although Compute is Auto, shown with the result. */
    val note: String? = null,
)

/**
 * A llama.cpp model that can be moved between the CPU and the phone's GPUs, and
 * the choice that remembers which was fastest.
 */
class ComputeComparison(
    val backend: LlamaCppBackend,
    val choice: ComputeChoice,
)

/** One place the model was measured: its rates, or why it could not run there. */
data class ComputeRun(
    val label: String,
    val prefillTokensPerSecond: Double = 0.0,
    val decodeTokensPerSecond: Double = 0.0,
    val error: String? = null,
)

/**
 * One finished speed test. Kept in memory only, for the life of the process.
 *
 * [countsExact] is false when the backend's token counts are estimates. LiteRT-LM
 * reports its prompt and completion counts with an approximation, so its tok/s is
 * a rate over an estimated count and the screen says so.
 */
data class SpeedTestResult(
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val backendId: String,
    /** "CPU", "GPU" or "NPU", or a plain phrase when the backend does not say. */
    val accelerator: String,
    val countsExact: Boolean,
    val modelFile: String,
    val modelUri: String,
    val measuredAtMillis: Long,
    /** Every place Auto compared, the winner included. Empty when nothing was compared. */
    val compared: List<ComputeRun> = emptyList(),
    /** [SpeedTestTarget.note], carried to the screen. */
    val note: String? = null,
    /** The prompt's length in this model's tokens; it differs between tokenizers. */
    val promptTokens: Int = 0,
)

/** Where the speed test is right now. */
sealed interface SpeedTestStatus {
    data object Idle : SpeedTestStatus

    /**
     * Pass [pass] of [passes] is running, on [on] when the test compares places to
     * run. Pass 0 is the model being loaded there.
     */
    data class Running(val pass: Int, val passes: Int, val on: String? = null) : SpeedTestStatus

    /** The test did not start, and [reason] says what to do about it. */
    data class Refused(val reason: String) : SpeedTestStatus

    /** The test started and the backend could not give a usable measurement. */
    data class Failed(val reason: String, val detail: String? = null) : SpeedTestStatus
}

/**
 * Measures the loaded model's prefill and decode speed on this phone.
 *
 * ## What one run does
 *
 * Two passes. Each pass sends the same fixed prompt (about 160 to 260 tokens, by
 * tokenizer; the result records the exact count) and asks for
 * 64 generated tokens. Prefill and decode come from the backend's own timings when it
 * reports them (llama.cpp does, from its native clock), and from wall-clock time when
 * it does not. The reported figure is the median of the passes. With two passes the
 * median is their mean, which is stated in the UI rather than hidden.
 *
 * ## Why it does not touch the conversation
 *
 * It calls the backend directly. It never builds a [dev.localintelligence.core.agent.Session]
 * or a controller, so nothing it generates reaches the transcript or the memory. The
 * native side clears its KV cache at the start of every generate call
 * (`llama_memory_clear` in `llama_jni.cpp`), so the next chat turn starts from the
 * same empty state it would have anyway.
 *
 * ## Why it holds the run gate
 *
 * A chat run and a speed test both drive the one native context. [RunGate] is the
 * process's single answer to "is something decoding?", so the test claims it for its
 * whole duration: a chat message sent meanwhile gets the same refusal a scheduled run
 * gets, and the test refuses to start while a run holds it.
 *
 * ## Cancellation
 *
 * Cancelling the coroutine is not enough. The llama.cpp generate call is a blocking
 * JNI call that only returns when native code notices its cancel flag, so [cancel]
 * also calls [ModelBackend.cancel]. The backend reference is forwarded only while this
 * test holds the gate, so a late tap cannot cancel a chat run that started after it.
 */
class SpeedTestRunner(
    private val scope: CoroutineScope,
    private val runGate: RunGate,
    /** Called after the gate is claimed, so the model cannot change under the test. */
    private val resolveTarget: () -> SpeedTestTarget?,
) {
    private val _status = MutableStateFlow<SpeedTestStatus>(SpeedTestStatus.Idle)
    val status: StateFlow<SpeedTestStatus> = _status.asStateFlow()

    private val _last = MutableStateFlow<SpeedTestResult?>(null)
    val last: StateFlow<SpeedTestResult?> = _last.asStateFlow()

    /** Guards [job] and [activeBackend], so a cancel and the end of a run cannot interleave. */
    private val lock = Any()

    private var job: Job? = null

    /** Set only while this test holds the gate. See the class KDoc. */
    private var activeBackend: ModelBackend? = null

    /** Starts a run unless one is already going. Returns at once; progress arrives on [status]. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch { run() }
        }
    }

    /** Stops the run at the next token. Safe when nothing is running. */
    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            activeBackend?.cancel()
        }
    }

    private suspend fun run() {
        val claim = runGate.tryClaim()
        if (claim == null) {
            _status.value = SpeedTestStatus.Refused(
                "An agent run is active. The speed test can run once it finishes.",
            )
            return
        }
        claim.use {
            try {
                // Resolved after the claim: a model swap goes through loadModel, which
                // does not take the gate, so reading the target earlier could measure
                // a model that is replaced a moment later.
                val target = resolveTarget()
                if (target == null) {
                    _status.value = SpeedTestStatus.Refused("Load a model first. The speed test measures the loaded model.")
                    return
                }
                synchronized(lock) { activeBackend = target.backend }

                val comparison = target.comparison
                val here = measure(target.backend, on = comparison?.let { acceleratorLabel(it.backend) })
                val compared = ArrayList<ComputeRun>()
                val headline = if (comparison != null) compare(comparison, here, compared) else here
                _last.value = SpeedTestResult(
                    prefillTokensPerSecond = headline.prefill,
                    decodeTokensPerSecond = headline.decode,
                    backendId = target.backend.id,
                    accelerator = acceleratorLabel(target.backend),
                    countsExact = target.backend.id == ModelImporter.BACKEND_LLAMA_CPP,
                    modelFile = target.modelFile,
                    modelUri = target.modelUri,
                    measuredAtMillis = System.currentTimeMillis(),
                    compared = compared,
                    note = target.note,
                    promptTokens = headline.promptTokens,
                )
                _status.value = SpeedTestStatus.Idle
            } catch (e: CancellationException) {
                _status.value = SpeedTestStatus.Idle
                throw e
            } catch (e: SpeedTestFailure) {
                _status.value = SpeedTestStatus.Failed(e.message.orEmpty(), e.detail)
            } catch (t: Throwable) {
                _status.value = SpeedTestStatus.Failed(
                    "The model stopped with an error.",
                    t.message ?: t::class.java.simpleName,
                )
            } finally {
                // Cleared before the claim is released (the `use` block ends after this),
                // so a late cancel can never reach a run that claims the gate next.
                synchronized(lock) { activeBackend = null }
            }
        }
    }

    /** [text] is the reply, kept so a GPU's output can be checked against the CPU's. */
    private class PassRates(val prefill: Double, val decode: Double, val text: String = "", val promptTokens: Int = 0)

    /** [PASSES] passes on [backend], their medians, and the first pass's reply. */
    private suspend fun measure(backend: ModelBackend, on: String?): PassRates {
        val prefill = ArrayList<Double>(PASSES)
        val decode = ArrayList<Double>(PASSES)
        var first: PassRates? = null
        for (pass in 1..PASSES) {
            coroutineContext.ensureActive()
            _status.value = SpeedTestStatus.Running(pass, PASSES, on)
            val rates = measurePass(backend)
            prefill += rates.prefill
            decode += rates.decode
            if (first == null) first = rates
        }
        return PassRates(median(prefill), median(decode), first?.text.orEmpty(), first?.promptTokens ?: 0)
    }

    /**
     * Measures the model on every other place Auto can run it, leaves it on the
     * fastest and stores that for Auto. [here] is the measurement where it already
     * was. A place that cannot load the model, or falls over running it, is listed
     * with its reason and left out. A cancel puts the model back where it started.
     *
     * Fastest means the shortest typical agent step, 512 prompt tokens read and 128
     * written, so neither a fast prefill nor a fast decode wins on its own.
     *
     * A GPU whose kernels compute wrong answers can still be fast, and Simple mode
     * would then pick it for everyone. Every pass decodes greedily from the same
     * prompt, so a correct GPU starts its reply exactly like the CPU; one that does
     * not is listed as failed. Only the start is compared, because f16 rounding can
     * legitimately make a long reply drift.
     */
    private suspend fun compare(cmp: ComputeComparison, here: PassRates, runs: MutableList<ComputeRun>): PassRates {
        val origin = cmp.backend.activeCompute ?: LlamaCompute.CPU
        val measured = linkedMapOf(origin to here)
        runs += ComputeRun(origin.label, here.prefill, here.decode)
        try {
            for (candidate in cmp.choice.candidates()) {
                if (candidate == origin) continue
                coroutineContext.ensureActive()
                _status.value = SpeedTestStatus.Running(0, PASSES, candidate.label)
                val error = cmp.backend.reloadOn(candidate)
                if (error != null) {
                    runs += ComputeRun(candidate.label, error = error)
                    continue
                }
                try {
                    val rates = measure(cmp.backend, candidate.label)
                    measured[candidate] = rates
                    runs += ComputeRun(candidate.label, rates.prefill, rates.decode)
                } catch (e: SpeedTestFailure) {
                    runs += ComputeRun(candidate.label, error = e.message)
                }
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                if (cmp.backend.activeCompute != origin) cmp.backend.reloadOn(origin)
            }
            throw t
        }
        val reference = measured[LlamaCompute.CPU]?.text?.let(::replyStart)
        if (reference != null) {
            for ((compute, rates) in measured.entries.toList()) {
                if (!compute.isGpu || replyStart(rates.text) == reference) continue
                measured.remove(compute)
                val i = runs.indexOfFirst { it.label == compute.label }
                if (i >= 0) runs[i] = runs[i].copy(error = "its reply did not match the CPU's")
            }
        }
        val best = measured.minBy { (_, r) -> STEP_PROMPT_TOKENS / r.prefill + STEP_OUTPUT_TOKENS / r.decode }
        if (cmp.backend.activeCompute != best.key) {
            _status.value = SpeedTestStatus.Running(0, PASSES, best.key.label)
            withContext(NonCancellable) { cmp.backend.reloadOn(best.key) }
        }
        cmp.choice.setAutoWinner(best.key)
        return best.value
    }

    /**
     * One pass: the fixed prompt with 64 generated tokens.
     *
     * When the backend reports both phases, its clock is used and nothing else runs.
     * When it does not, a second 1-token run gives the prefill wall-clock, and the
     * extra time of the 64-token run gives the decode rate. That path costs one more
     * generate call and is only taken for a backend that reports no timings.
     */
    private suspend fun measurePass(backend: ModelBackend): PassRates {
        val full = timed(backend, DECODE_TOKENS)
        requireUsable(full.result, minTokens = DECODE_TOKENS / 2)
        val promptTokens = promptTokens(full.result, backend)

        if (full.result.prefillMs > 0 && full.result.decodeMs > 0) {
            return PassRates(
                prefill = promptTokens * 1000.0 / full.result.prefillMs,
                decode = full.result.completionTokens * 1000.0 / full.result.decodeMs,
                text = full.result.text,
                promptTokens = promptTokens,
            )
        }

        val one = timed(backend, 1)
        requireUsable(one.result, minTokens = 1)
        val decodeTokens = full.result.completionTokens - one.result.completionTokens
        val decodeMs = full.wallMs - one.wallMs
        if (decodeTokens <= 0 || decodeMs <= 0.0) {
            throw SpeedTestFailure("the backend returned too few tokens to time the decode.")
        }
        return PassRates(
            prefill = promptTokens * 1000.0 / one.wallMs,
            decode = decodeTokens * 1000.0 / decodeMs,
            text = full.result.text,
            promptTokens = promptTokens,
        )
    }

    private class Timed(val result: GenerationResult, val wallMs: Double)

    private suspend fun timed(backend: ModelBackend, maxTokens: Int): Timed {
        // A cancel between passes is seen here. generateInternal resets the native
        // cancel flag at the start of each call, so the coroutine check is what
        // keeps a tap that landed between two passes from being lost.
        coroutineContext.ensureActive()
        val started = System.nanoTime()
        val result = backend.generate(
            GenerationRequest(
                messages = listOf(ChatMessage.User(PROMPT)),
                // Greedy decoding (temperature 0 selects the argmax sampler in
                // llama_jni.cpp), so both passes ask the model for the same tokens.
                params = SamplingParams(temperature = 0f, maxOutputTokens = maxTokens),
            ),
        )
        val wallMs = (System.nanoTime() - started) / 1_000_000.0
        return Timed(result, wallMs)
    }

    private fun requireUsable(result: GenerationResult, minTokens: Int) {
        when {
            result.stopReason == StopReason.CANCELLED -> throw CancellationException("speed test cancelled")
            result.stopReason == StopReason.ERROR ->
                throw SpeedTestFailure("The model stopped with an error.", result.text.ifBlank { null })
            // A short answer is not a decode rate: the figure would describe a few
            // tokens, not 64. Refuse rather than report it.
            result.completionTokens < minTokens ->
                throw SpeedTestFailure("only ${result.completionTokens} tokens came back, too few to time.")
        }
    }

    /** The backend's own prompt count, or the backend's estimate when it reports zero. */
    private fun promptTokens(result: GenerationResult, backend: ModelBackend): Int =
        result.promptTokens.takeIf { it > 0 } ?: backend.countTokens(PROMPT)

    private class SpeedTestFailure(message: String, val detail: String? = null) : Exception(message)

    companion object {
        /** Passes per run. The median of two is their mean. */
        const val PASSES = 2

        /** Tokens generated in each pass. */
        const val DECODE_TOKENS = 64

        /** The typical agent step the comparison scores: tokens read, tokens written. */
        private const val STEP_PROMPT_TOKENS = 512.0
        private const val STEP_OUTPUT_TOKENS = 128.0

        /**
         * About 160 to 260 tokens of prompt, by tokenizer, ending in a request that keeps the model writing.
         * A numbered list is used on purpose: it does not end early, so the 64-token
         * decode is measured in full rather than cut short by an end-of-text token.
         * The exact prompt token count is taken from the backend on each pass.
         */
        private val PROMPT = buildString {
            append(
                "A phone that runs a language model on its own has to balance three " +
                    "things: how quickly it answers, how much memory the model takes, and " +
                    "how long the battery lasts while it works. The processor decides the " +
                    "first, the model file decides the second, and sustained load decides " +
                    "the third. A small quantised model can answer in a few hundred " +
                    "milliseconds on a recent phone, while a larger one may need several " +
                    "seconds for the same question. Measuring both phases, the time spent " +
                    "reading the prompt and the time spent writing the answer, shows which " +
                    "one is the bottleneck for a given device and model. ",
            )
            append(
                "Write a numbered list of everyday objects found in a kitchen, one per line, " +
                    "with a few words about each. Keep going until the list is long.\n1.",
            )
        }
    }
}

/**
 * The accelerator the model ran on: "CPU", "GPU (Vulkan)", "NPU"...
 *
 * llama.cpp says where its resident model is ([LlamaCppBackend.activeCompute]). Any
 * other backend says what it used through [AcceleratorAware], and one that says
 * nothing is reported as such, not guessed.
 */
internal fun acceleratorLabel(backend: ModelBackend): String {
    (backend as? LlamaCppBackend)?.let { return (it.activeCompute ?: LlamaCompute.CPU).label }
    if (backend.id == ModelImporter.BACKEND_LLAMA_CPP) return "CPU"
    val report = (backend as? AcceleratorAware)?.acceleratorReport ?: return "unknown chip"
    return report.active.name
}

/** The first characters of a reply, whitespace collapsed: what two correct backends agree on. */
internal fun replyStart(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(32)

/** Median of a non-empty list. For two values this is their mean. */
internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/** The line the Models screen and the Settings screen both show. */
fun SpeedTestResult.summary(): String {
    val backend = if (backendId == ModelImporter.BACKEND_LLAMA_CPP) "llama.cpp" else backendId
    return "Prefill ${rate(prefillTokensPerSecond)} tok/s · Decode ${rate(decodeTokensPerSecond)} tok/s · " +
        "$backend on $accelerator · $modelFile"
}

/** "CPU 143.3 / 14.3 · GPU (Vulkan) 80.1 / 20.4 tok/s", or null when nothing was compared. */
fun SpeedTestResult.comparison(): String? {
    if (compared.size < 2) return null
    return compared.joinToString(" · ", prefix = "Compared (prefill / decode): ") { run ->
        run.error?.let { "${run.label} failed: $it" }
            ?: "${run.label} ${rate(run.prefillTokensPerSecond)} / ${rate(run.decodeTokensPerSecond)} tok/s"
    }
}

/** The date line under a result, in the phone's own locale. */
fun SpeedTestResult.measuredOn(): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(measuredAtMillis))
    return "Measured on this phone, $date"
}

private fun rate(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
