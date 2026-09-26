package dev.localintelligence.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.localintelligence.android.inference.LlamaCppBackend

/**
 * What the UI is allowed to claim about the model, derived from what actually
 * happened when it was loaded.
 *
 * ## Why this type exists
 *
 * The chat screen has to answer one question honestly: *can this app produce an
 * answer right now?* Before this existed, the answer arrived only as a
 * `RunOutcome.Failed` string produced deep inside the inference backend — and
 * because the backend reports a failed load as `GenerationResult(text = "",
 * stopReason = ERROR)`, the user was shown the literal notice `model failed: `,
 * with nothing after the colon. That is the worst class of bug in an agent app:
 * a screen that reports a failure without saying what failed.
 *
 * So the answer is computed once, at the point where loading actually happens,
 * and published as state the screens can render. A screen never has to infer
 * readiness from an error string.
 *
 * ## Why it is not an enum of booleans
 *
 * `Ready` alone would force every caller to invent its own "why not" text, and
 * they would disagree — which is how a user ends up with two different
 * explanations for the same broken state on two different screens. The three
 * cases are genuinely different facts, not flags.
 */
sealed interface ModelAvailability {

    /**
     * Nothing has been imported yet.
     *
     * The true state of a fresh install, and the one the first-run screen has
     * to handle. It is *not* an error: there is nothing to recover from, only
     * something to do.
     */
    data object None : ModelAvailability

    /**
     * A model was chosen but could not be loaded — corrupt file, revoked SAF
     * grant, not enough RAM, no native library.
     *
     * [reason] is user-facing and already safe to display: it is written by the
     * loader, never a raw stack trace.
     */
    data class Failed(val reason: String) : ModelAvailability

    /**
     * A model is resident and the backend will answer — and this is which one.
     *
     * ## WHY READY CARRIES A NAME
     *
     * `Ready` used to be a `data object`, so "a model is loaded" was the only
     * fact it could express. A user of an on-device agent therefore had no way
     * to learn which of the models they had imported was answering them, which
     * is a trust problem and not a cosmetic one: a 1.1B model is wrong often
     * enough that "which one said this" is the first question a person asks.
     * There was also nowhere for the name to live. The app has exactly one
     * place that knows what was actually opened — the loader — and it is not
     * the composable that renders the answer.
     *
     * ## WHAT THE TWO FIELDS ACTUALLY ARE
     *
     * Both come from the GGUF header of the file that was handed to the
     * backend, read by `ModelImporter` before the load. They are not values
     * the runtime echoed back: `LlamaBridge.nativeLoadModel` returns only an
     * error string or success, and the JNI layer keeps no reference to
     * `general.architecture` or `general.name` once the handle is made. So
     * this identifies *the file that was opened*, which is the strongest claim
     * available today, and not more than that.
     *
     * @param displayName the GGUF `general.name` metadata value when the header
     *   carries one, and the imported file's name when it does not. Always
     *   non-blank — `ModelImporter` falls back to a name derived from the URI
     *   — but it is a *filename* for a large share of real GGUFs, and a
     *   filename is not proof of what is inside the file.
     * @param quantType the header's dominant tensor quantisation, falling back
     *   to the declared file type. Null when the header carries neither.
     *   Present because two imports can differ only here, and "which model"
     *   is a question about the quant as much as about the name.
     */
    data class Ready(
        val displayName: String,
        val quantType: String? = null,
    ) : ModelAvailability

    /** True only when a run can actually produce tokens. */
    val canRun: Boolean get() = this is Ready
}

/**
 * The process-wide model readiness signal, shared between the service that
 * loads and the screens that render.
 *
 * A `MutableStateFlow` rather than a field on the Activity because loading
 * happens in [ExecutionService] and rendering happens in an Activity that can be
 * destroyed and recreated underneath a run in flight. A plain field would leave
 * the chat claiming "no model" while the decode that is already running is
 * producing the answer.
 */
class ModelAvailabilityHolder(
    initial: ModelAvailability = ModelAvailability.None,
) {
    private val _state = MutableStateFlow(initial)

    val state: StateFlow<ModelAvailability> = _state.asStateFlow()

    val current: ModelAvailability get() = _state.value

    fun set(value: ModelAvailability) {
        _state.value = value
    }
}

/**
 * Turns a failed load into user-facing text.
 *
 * Exists as a function rather than an inline string so the mapping is
 * unit-testable, and so there is exactly one wording for "the model would not
 * load" in the app. A screen that invents its own phrasing is how a user ends up
 * being told two different things about the same failure.
 *
 * The raw exception is deliberately reduced to its type: a stack trace on a
 * phone screen is noise at best and a leak of internal paths at worst. The
 * developer-facing detail belongs in the trace view.
 */
internal fun describeLoadFailure(cause: Throwable?): String = when (cause) {
    null -> "The model could not be loaded."

    is java.io.FileNotFoundException ->
        "That file is no longer readable. Import it again from your files app."

    is OutOfMemoryError ->
        "There is not enough memory to load that model. Pick a smaller one. " +
            "Context length is fixed at ${LlamaCppBackend.DEFAULT_CONTEXT_LENGTH} " +
            "tokens and there is no control for it yet, so a smaller model is " +
            "the only lever."

    else -> {
        // The raw message is NOT shown. It is a native or JNI string and can be
        // a file path, a pointer-ish token or a stack-shaped fragment, none of
        // which means anything to a person holding a phone - and a path is a
        // small information leak about where the app stores things. The known
        // failures above are matched by type and carry real advice; anything
        // unrecognized is honestly unnamed rather than falsely specific.
        //
        // The cause is not swallowed: it is logged, so a bug report has it.
        android.util.Log.w("ModelAvailability", "model load failed", cause)
        "That model could not be loaded. It may be a format this app cannot " +
            "read, or it may be damaged - try importing it again."
    }
}

/**
 * The one-line explanation the chat screen shows when no run is possible.
 *
 * Returns null when a run *is* possible, so a caller cannot accidentally render
 * an error banner over a working screen — the check and the message cannot
 * drift apart because they are the same expression.
 */
internal fun ModelAvailability.blockingReason(): String? = when (this) {
    // `is`, not equality: Ready is a data class, so `ModelAvailability.Ready`
    // in this position is a classifier and not a value. Every other case is
    // matched by shape too, which is what makes the exhaustiveness of this
    // `when` a compile-time property of the sealed type rather than of the
    // day it was written.
    is ModelAvailability.Ready -> null
    ModelAvailability.None ->
        "Import a model before sending a message. Nothing is sent anywhere — " +
            "the agent runs entirely on this phone."

    is ModelAvailability.Failed -> reason
}
