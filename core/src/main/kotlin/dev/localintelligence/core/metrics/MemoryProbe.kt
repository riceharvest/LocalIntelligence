package dev.localintelligence.core.metrics

/**
 * One reading of this process's memory, at one labelled moment.
 *
 * ## EVERY NUMBER HERE IS EITHER MEASURED OR IT IS NOT IN THE STRUCT
 *
 * [PlatformMemory] fields are filled by a platform API on a real device
 * (`android.os.Debug.getMemoryInfo`). [retainedMessages] and [retainedChars] are
 * counted by `:core` walking its own list, so they are exact rather than
 * sampled. There is no field here that is an estimate, because a struct that
 * can hold an estimate is a struct that will eventually hold one and render it
 * as a measurement — which is the specific failure `docs/architecture.md` §16
 * records and this project keeps having to undo.
 *
 * ## WHY BYTES AND NOT A PRE-FORMATTED STRING
 *
 * A `String` here would be formatted once, at the point of capture, by
 * whichever module happened to be holding the value, and every later reader
 * would be showing a number whose rounding and units it cannot see. Bytes are
 * unambiguous and this project already has one formatter
 * ([dev.localintelligence.core.model.gguf.MemoryEstimate.formatBytes]) that
 * every screen uses.
 */
data class MemorySample(
    /** What was happening: `run.start`, `step.3`, `compaction`, `model.loaded`. */
    val label: String,
    /** Milliseconds since the probe was created. Monotonic; not wall clock. */
    val atElapsedMs: Long,
    /**
     * Proportional set size in bytes, or [UNKNOWN_BYTES].
     *
     * PSS is the figure that matters for an OOM kill: the kernel charges a
     * process a share of every shared page, and PSS is that share summed.
     */
    val pssBytes: Long,
    /** Resident set size in bytes, or [UNKNOWN_BYTES]. */
    val rssBytes: Long,
    /** The managed-heap share of PSS, or [UNKNOWN_BYTES]. */
    val dalvikPssBytes: Long,
    /** The native share of PSS — where a llama.cpp model's weights live. */
    val nativePssBytes: Long,
    /** `Debug.getNativeHeapAllocatedSize`, or [UNKNOWN_BYTES]. */
    val nativeHeapAllocatedBytes: Long,
    /** `Runtime.totalMemory() - Runtime.freeMemory()`. */
    val javaHeapUsedBytes: Long,
    /** `Runtime.maxMemory()`. */
    val javaHeapMaxBytes: Long,
    // ---- the agent's own retention, counted exactly ----
    /**
     * Messages in the live session window at this instant.
     *
     * Capped by [dev.localintelligence.core.compaction.RetainedHistory] at
     * 32 between runs. If this ever reads above that cap at a `run.end` label,
     * the bound has been broken and that is the number that says so.
     */
    val retainedMessages: Int,
    /**
     * Characters of model-visible text across the live window.
     *
     * This is the quantity the agent's own code retains, and it is counted by
     * summing `String.length` — the same measure the truncator and the token
     * estimator use, so it is comparable with them.
     */
    val retainedChars: Int,
    /** Characters in the prompt the model was last handed, or 0. */
    val promptChars: Int,
) {
    companion object {
        /**
         * The value every unmeasured byte field carries.
         *
         * A constant rather than `null` so the struct stays non-nullable and a
         * screen cannot forget to handle the missing case — it renders
         * [UNKNOWN_BYTES] and says the platform would not report it. `0` would
         * be a lie in the other direction: a process holding a 2 GB model does
         * not have a zero-byte heap, and a zero here would read as a
         * measurement.
         */
        const val UNKNOWN_BYTES: Long = -1L
    }
}

/**
 * The platform half of a [MemorySample], supplied by whichever module can reach
 * the Android APIs.
 *
 * WHY IT IS AN INTERFACE IN `:core` AND NOT A CALL TO `Debug` IN `:core`:
 * `:core` is pure JVM by rule and by a CI check
 * (`grep -rn '^import android\.' core/src/main/`), so it cannot name
 * `android.os.Debug`. `:android` owns that. The seam is one function, so the
 * rule costs one interface rather than a framework.
 *
 * @return null when the platform will not report — a legitimate answer, and the
 *   one a JVM unit test always gets. It is why every byte field on
 *   [MemorySample] is nullable in spirit and [MemorySample.UNKNOWN_BYTES] in
 *   practice.
 */
fun interface PlatformMemoryReader {
    fun read(): PlatformMemory?
}

/**
 * The platform's own numbers, already in bytes.
 *
 * Deliberately four separate fields rather than one total: the split between
 * managed and native is the whole question for an on-device agent, and a single
 * number cannot answer it. A 2.4 GB model with a 30 MB Java heap and a 2.4 GB
 * native heap and a 2.4 GB total are three different products.
 */
data class PlatformMemory(
    val pssBytes: Long,
    val rssBytes: Long,
    val dalvikPssBytes: Long,
    val nativePssBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val javaHeapUsedBytes: Long,
    val javaHeapMaxBytes: Long,
)

/**
 * Where the agent publishes its memory readings.
 *
 * ## WHY THE LOOP CALLS THIS AND NOT SOMETHING THAT READS A PLATFORM API
 *
 * The loop is `:core` and cannot reach `Debug`. If it needed the number itself
 * to make a decision, that decision could not be made here at all. It does not:
 * every call is an observation with no effect on the run, which is what lets
 * the seam be nullable everywhere and zero-cost when it is absent.
 */
interface MemoryProbe {

    /**
     * Publishes one reading.
     *
     * @param retainedMessages messages in the live window. Passed in rather than
     *   read from a session so the probe cannot reach into the loop's state, and
     *   so a caller that has no session can still report.
     * @param retainedChars characters of model-visible text in that window.
     * @param promptChars characters in the last prompt handed to the model, or 0.
     */
    fun record(label: String, retainedMessages: Int, retainedChars: Int, promptChars: Int)
}

/**
 * A probe that does nothing, for a caller that has none.
 *
 * ## WHY THIS EXISTS AND NOT A NULL CHECK AT EVERY CALL SITE
 *
 * Both are zero-cost. This one is better because the alternative is a
 * `MemoryProbe?` that every call site has to null-check, and a null check
 * someone can forget is how an instrumentation gap becomes a silent one. A
 * no-op object cannot be forgotten: calling it is always correct.
 */
object NoMemoryProbe : MemoryProbe {
    override fun record(label: String, retainedMessages: Int, retainedChars: Int, promptChars: Int) =
        Unit
}

/**
 * The most recent [MemorySample]s in this process, in memory, bounded.
 *
 * ## This is a mirror of [RunMetricsJournal], and deliberately so
 *
 * That object is the established shape for "a run measured something and a
 * screen needs to read it later": a fixed ring under a lock, no I/O, no
 * subscriber, no thread, dies with the process. A second mechanism for the
 * same job would be the second vocabulary this project keeps paying to
 * unpickle. The rules that made it acceptable are the same here:
 *
 *  - **bounded.** [CAPACITY] readings, oldest dropped. A phone that has run a
 *    hundred agent tasks must not be holding a hundred readings for a screen
 *    to page through; keeping everything is a memory leak dressed as a
 *    feature, and this file would be the leak.
 *  - **never throws.** [record] is called from inside the agent loop, in a
 *    process that has just allocated gigabytes of native memory for a model.
 *    An allocation failure while appending a diagnostic record is not
 *    hypothetical, and losing the record is strictly better than losing the
 *    run.
 */
object RunMemoryJournal {

    /** How many readings are kept. Oldest are dropped first. */
    const val CAPACITY: Int = 32

    private val lock = Any()
    private val entries = ArrayDeque<MemorySample>(CAPACITY)

    /**
     * A probe that publishes into this journal, reading the platform half
     * through [platform] every time it is called.
     *
     * The reader is called per reading rather than once at construction on
     * purpose: `Debug.getMemoryInfo()` is a syscall, and a reading taken at
     * construction would describe the process before the thing being measured
     * happened.
     *
     * @param clock monotonic milliseconds, injected so a caller can align these
     *   readings with a run's own [RunMetrics.totalMs].
     */
    fun probe(
        platform: PlatformMemoryReader,
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
    ): MemoryProbe = JournalProbe(platform, clock)

    /** Publishes one reading. Never throws; see the type KDoc. */
    fun record(sample: MemorySample) {
        try {
            synchronized(lock) {
                if (entries.size >= CAPACITY) entries.removeFirst()
                entries.addLast(sample)
            }
        } catch (_: Throwable) {
            // Deliberately swallowed. See the type KDoc.
        }
    }

    /** Newest first. A copy: the caller cannot mutate the journal. */
    fun recent(): List<MemorySample> = synchronized(lock) { entries.toList().asReversed() }

    /** The most recent reading, or null when nothing has been recorded. */
    fun latest(): MemorySample? = synchronized(lock) { entries.lastOrNull() }

    /** The newest reading carrying [label], or null. */
    fun latestAt(label: String): MemorySample? =
        synchronized(lock) { entries.lastOrNull { it.label == label } }

    /** How many readings are held. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** Empties the journal. For a test or a deliberate reset. */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** The journal-backed probe. Private so the clock cannot be misused. */
    private class JournalProbe(
        private val platform: PlatformMemoryReader,
        private val clock: () -> Long,
    ) : MemoryProbe {
        override fun record(
            label: String,
            retainedMessages: Int,
            retainedChars: Int,
            promptChars: Int,
        ) {
            // A reader that throws takes the run down with it, and the whole
            // point of this seam is that observing a run cannot break one.
            val p = try {
                platform.read()
            } catch (_: Throwable) {
                null
            }
            val u = MemorySample.UNKNOWN_BYTES
            RunMemoryJournal.record(
                MemorySample(
                    label = label,
                    atElapsedMs = clock(),
                    pssBytes = p?.pssBytes ?: u,
                    rssBytes = p?.rssBytes ?: u,
                    dalvikPssBytes = p?.dalvikPssBytes ?: u,
                    nativePssBytes = p?.nativePssBytes ?: u,
                    nativeHeapAllocatedBytes = p?.nativeHeapAllocatedBytes ?: u,
                    javaHeapUsedBytes = p?.javaHeapUsedBytes ?: u,
                    javaHeapMaxBytes = p?.javaHeapMaxBytes ?: u,
                    retainedMessages = retainedMessages,
                    retainedChars = retainedChars,
                    promptChars = promptChars,
                ),
            )
        }
    }
}
