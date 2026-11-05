package dev.localintelligence.app

import android.content.Context
import androidx.annotation.StringRes
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.app.ui.components.donePhrase

/**
 * Plain sentences for the failures a Simple user can meet: no internet, low memory, a model that
 * will not start, a download that cannot finish, a full phone. Technical mode keeps the raw text.
 *
 * WHY MATCH TEXT: the failures come from several layers (the native engine, the Hub, the tools,
 * the network stack) and most arrive as a message string, not a type the chat can switch on. So
 * the match is by the words those messages carry, in a fixed order, and anything that matches
 * no kind is not guessed at: the caller falls back to the generic sentence.
 */
object SimpleWording {
    enum class Kind(@StringRes val text: Int) {
        NO_NETWORK(R.string.plain_no_network),
        STORAGE_FULL(R.string.plain_storage_full),
        LOW_MEMORY(R.string.plain_low_memory),
        LOAD_FAILED(R.string.plain_load_failed),
        CANT_RUN(R.string.plain_cant_run),
        DOWNLOAD_FAILED(R.string.plain_download_failed),
        NOT_READY(R.string.plain_not_ready),
        TOO_LONG(R.string.plain_too_long),
        CANT_HEAR(R.string.plain_cant_hear),
        TRANSCRIBE_FAILED(R.string.plain_transcribe_failed),

        // How a run ended without an answer (see core's StopKind, which uses these names).
        LOOP_DETECTED(R.string.plain_stop_loop),
        NO_PROGRESS(R.string.plain_stop_no_progress),
        NO_VALID_ACTION(R.string.plain_stop_no_action),
        REPEATED(R.string.chat_repetition_stopped),
        EMPTY_REPLY(R.string.plain_stop_empty),
        MODEL_FAILED(R.string.plain_stop_assistant_failed),
        NOTHING_PENDING(R.string.plain_stop_nothing_pending),
        CANT_SEE(R.string.plain_stop_cant_see),
        INTERRUPTED(R.string.plain_stop_interrupted),
        STEP_LIMIT(R.string.plain_stop_steps),
        INTERNAL(R.string.plain_something_went_wrong),
    }

    /**
     * The kind named by a core stop's key (see `StopKind`), or null when the key is not one the app
     * knows. A key the app does not know is shown as the generic sentence, never a crash.
     */
    fun named(key: String?): Kind? = key?.let { k -> Kind.entries.firstOrNull { it.name == k } }

    /**
     * What the run got done before it stopped, as one plain sentence ("Done so far: Set timer."), or null
     * when no tool that has a Simple name succeeded. Only the tools that worked: a failed call is not claimed.
     */
    fun doneSummary(context: Context, trace: List<StepTrace>): String? {
        val phrases = trace
            .filter { it.kind == StepTrace.Kind.TOOL_CALL && it.success }
            .mapNotNull { it.toolName }
            .distinct()
            .mapNotNull { donePhrase(context, it) }
        if (phrases.isEmpty()) return null
        return context.getString(R.string.plain_done_so_far, phrases.joinToString(", "))
    }

    /** The kind of failure [raw] describes, or null when its words name none. Order matters: the first match wins. */
    fun kindOf(raw: String): Kind? {
        val t = raw.lowercase()
        fun any(vararg words: String) = words.any { t.contains(it) }
        return when {
            any("context window too small", "too long") -> Kind.TOO_LONG
            any("cannot hear audio") -> Kind.CANT_HEAR
            any("transcribe") -> Kind.TRANSCRIBE_FAILED
            any("internet", "unable to resolve", "unknownhost", "no network", "network", "connection",
                "timed out", "timeout", "offline", "unreachable", "no route") -> Kind.NO_NETWORK
            any("storage", "no space", "enospc", "free up") -> Kind.STORAGE_FULL
            any("out of memory", "outofmemory", "memory", "oom") -> Kind.LOW_MEMORY
            any("couldn't be loaded", "could not be loaded", "would not start", "failed to load", "load failed") -> Kind.LOAD_FAILED
            any("can't run", "cannot run", "will not load on this phone") -> Kind.CANT_RUN
            any("download", "couldn't find") -> Kind.DOWNLOAD_FAILED
            any("getting the model ready", "not loaded", "still loading") -> Kind.NOT_READY
            else -> null
        }
    }

    /**
     * The plain sentence for a failure of [kind], or the generic one when the kind is unknown.
     *
     * WHY NO TEXT MATCH HERE: the kind is set where the failure is born, before its text is
     * localized. Matching a localized sentence would fall to the generic one on every
     * non-English phone. [kindOf] is for English text that nothing localized, such as a tool's
     * observation or a runtime reason from the core module.
     */
    fun failureText(context: Context, kind: Kind?): String =
        context.getString(kind?.text ?: R.string.plain_something_went_wrong)

    /**
     * The line the composer shows when no answer is possible, in Simple words. Null when a run is
     * possible. The same cases as [blockingReason] in ModelAvailability, with the Simple sentences.
     */
    fun blocked(context: Context, availability: ModelAvailability): String? = when (availability) {
        is ModelAvailability.Ready -> null
        ModelAvailability.Unloaded -> null
        ModelAvailability.None -> context.getString(R.string.plain_first_run)
        ModelAvailability.Loading -> context.getString(R.string.getting_ready)
        is ModelAvailability.Failed -> failureText(context, availability.kind)
    }
}
