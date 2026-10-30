package dev.localintelligence.core.tool

import java.util.Locale

/**
 * Keeps the full text of a tool result that was too long for the model, so the
 * model can read the rest of it later with `observations.read`.
 *
 * ## WHY THIS EXISTS
 *
 * A tool result longer than the observation budget used to be cut to its head by
 * [ObservationTruncator.truncate], and the rest was gone. The model could not ask
 * for page 2, and the part that answers the question is often the tail: the
 * price under the product text, the last rows of a list, the error at the end of
 * a log. With a store the loop shows a head-and-tail preview
 * ([ObservationTruncator.preview]) whose footer names a handle (`obs#41`), and
 * the text stays reachable instead of being lost.
 *
 * ## WHY BLOCKING AND NOT SUSPEND
 *
 * Both calls are small single-row reads and writes, made from the agent loop and
 * from a tool, and neither runs on the main thread. A plain function keeps the
 * interface usable from code that is not a coroutine, and the Room implementation
 * in `:android` uses blocking DAO calls for the same reason. A caller on a
 * coroutine that must not block wraps the call in its IO dispatcher.
 *
 * ## FAILURE
 *
 * Neither call throws. A store that cannot write returns null from [put], and the
 * loop then shows the preview without a handle, which is the old behaviour. A
 * store that cannot read returns null from [read], which the tool reports as "not
 * available". Losing the rest of one long result is not a reason to end a run.
 */
interface ObservationStore {

    /**
     * Saves [text], the full output of [toolName], and returns its id, or null when
     * nothing was saved. Text longer than [MAX_STORED_CHARS] is saved up to that
     * length; the original length is kept so a reader can say what is missing.
     * Only the newest [MAX_KEPT] results are kept.
     */
    fun put(toolName: String, text: String): Long?

    /**
     * The model-facing text for one read of observation [id], at most [maxChars]
     * characters including the lines that say which part it is and how to read
     * the next one. With a non-blank [query] it is the passages that match best;
     * without, the text from character [offset]. Null when [id] is not in the
     * store (never saved, or pruned).
     */
    fun read(id: Long, query: String?, offset: Int, maxChars: Int): String?

    companion object {
        /** The tool that reads a stored result. Named here so the preview footer and the tool agree. */
        const val READ_TOOL: String = "observations.read"

        /**
         * Longest text kept per result, in characters: a page's text from
         * web.fetch (at most 60,000) with its header. With [MAX_KEPT] it bounds
         * the table at about 13 M characters, which matters on a phone because
         * SQLite does not give the space back on its own.
         */
        const val MAX_STORED_CHARS: Int = 64 * 1024

        /** How many results are kept. Older ones are deleted on insert, so the table is bounded. */
        const val MAX_KEPT: Int = 200

        /**
         * The largest single read. Below the 2,048-character observation budget, so a
         * read result is never cut by the loop and never offloaded again.
         */
        const val MAX_READ_CHARS: Int = 2_000
    }
}

/**
 * `18,240`: a count with a comma between thousands, whatever the phone's language.
 * Model-visible text must not change with the locale (`18.240` on a Dutch phone).
 */
internal fun groupedCount(n: Int): String = String.format(Locale.ROOT, "%,d", n)
