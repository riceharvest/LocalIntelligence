package dev.localintelligence.app

import android.content.Context
import androidx.annotation.StringRes

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

    /** The plain sentence for a failure text: its kind's sentence, or the generic one. */
    fun failureText(context: Context, raw: String): String =
        kindOf(raw)?.let { context.getString(it.text) } ?: context.getString(R.string.plain_something_went_wrong)

    /**
     * The line the composer shows when no answer is possible, in Simple words. Null when a run is
     * possible. The same cases as [blockingReason] in ModelAvailability, with the Simple sentences.
     */
    fun blocked(context: Context, availability: ModelAvailability): String? = when (availability) {
        is ModelAvailability.Ready -> null
        ModelAvailability.Unloaded -> null
        ModelAvailability.None -> context.getString(R.string.plain_first_run)
        ModelAvailability.Loading -> context.getString(R.string.getting_ready)
        is ModelAvailability.Failed -> failureText(context, availability.reason)
    }
}
