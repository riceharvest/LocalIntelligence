package dev.localintelligence.core.hub

/**
 * Progress for a download, emitted on a [kotlinx.coroutines.flow.Flow].
 *
 * ## Why this is not one-per-buffer
 *
 * The obvious implementation is `while (read > 0) emit(...)` inside the copy
 * loop, with a 64 KiB buffer. On a fast connection that is 30 000 emissions for
 * a 2 GB file, and each one crosses a Flow operator chain to reach the UI. The
 * progress bar cannot use any of them: a human perceives about 10 updates per
 * second, so 30 000 emissions is 30 000 chances to do redundant work in a
 * recomposition on a phone that is already running a local LLM.
 *
 * So progress is *coalesced*: the downloader reports at most once per time
 * interval, requires real forward progress before it does, and forces an
 * emission if enough bytes accumulate on a slow link. Terminal events always
 * emit. A download that is cancelled mid-flight has therefore always reported
 * a progress value close to what actually landed, which is the value the resume
 * logic reads back off the filesystem.
 */
sealed interface DownloadProgress {

    /** A completed, checksum-verified file at its final path. */
    data class Done(val file: HubGgufFile, val localPath: String) : DownloadProgress

    /**
     * The download stopped. [partialBytesKept] is what remains in the `.part`
     * file for a later resume; it is never at the final path.
     */
    data class Stopped(val error: HubError, val partialBytesKept: Long) : DownloadProgress

    /**
     * Bytes moved so far.
     *
     * @param totalBytes the full size, or -1 when the server did not say.
     *   The UI must handle -1 as an indeterminate bar rather than dividing by
     *   it.
     * @param bytesPerSecond 0 until enough samples exist to be meaningful.
     */
    data class InProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : DownloadProgress {
        /** 0.0..1.0, or null when the total is unknown. */
        val fraction: Float?
            get() = if (totalBytes > 0) {
                (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            } else {
                null
            }
    }
}

/**
 * Decides when a progress emission is worth making.
 *
 * Pure and clock-injectable so the throttling is tested deterministically
 * rather than by sleeping in a test.
 *
 * @param minBytes suppress an emission until this many new bytes have landed.
 * @param minIntervalMillis suppress an emission until this much wall time has
 *   passed, so a slow connection still shows movement.
 * @param clock monotonic-ish millisecond source; injected so tests control it.
 */
class ProgressThrottle(
    private val minBytes: Long = DEFAULT_MIN_BYTES,
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastEmittedBytes = 0L
    private var lastEmitAt = Long.MIN_VALUE

    /**
     * Call for every buffer read. True when the caller should emit.
     *
     * The first call always emits, so a download that immediately fails still
     * reported *something*, which is what stops a zero-progress bar from
     * appearing and then vanishing with no explanation.
     */
    fun shouldEmit(bytesDownloaded: Long): Boolean {
        val now = clock()
        if (lastEmitAt == Long.MIN_VALUE) {
            record(bytesDownloaded, now)
            return true
        }
        val bytesSince = bytesDownloaded - lastEmittedBytes
        val timeSince = now - lastEmitAt
        // The rule is "at most once per interval, and at least once per
        // STARVATION_BYTES" - NOT "once per interval OR once per minBytes".
        //
        // WHY: the OR form is bounded by the byte gate, and on a fast link
        // 256 KiB arrives far more often than 250 ms. A 2 GB download over 5G
        // would emit ~7600 times, which is the exact per-buffer flood this
        // class exists to prevent. The AND form bounds emissions by the clock
        // at ~4/s, or ~1600 over a 400-second transfer.
        //
        // WHY a starvation escape at all: on a link moving 30 KB/s the byte
        // gate is never met, and a strictly-AND rule would freeze the bar at
        // 0% for the whole download. STARVATION_BYTES lets a genuinely slow
        // transfer keep reporting regardless of the clock.
        // Saturating multiply: a caller passing minBytes = Long.MAX_VALUE (to
        // disable the byte gate) would otherwise overflow the product to a
        // negative number, and every call would read as "starved".
        val starveThreshold = if (minBytes > Long.MAX_VALUE / STARVATION_FACTOR) {
            Long.MAX_VALUE
        } else {
            minBytes * STARVATION_FACTOR
        }
        val starved = bytesSince >= starveThreshold
        if ((timeSince >= minIntervalMillis && bytesSince >= minBytes) || starved) {
            record(bytesDownloaded, now)
            return true
        }
        return false
    }

    /** Forces the next [shouldEmit] to pass. Used for terminal events. */
    fun flush() {
        lastEmittedBytes = Long.MAX_VALUE / 2
        lastEmitAt = Long.MIN_VALUE
    }

    private fun record(bytes: Long, at: Long) {
        lastEmittedBytes = bytes
        lastEmitAt = at
    }

    companion object {
        /**
         * WHY 256 KiB: at that granularity a 2 GB file produces ~8 000
         * candidate emissions, and with the time gate also active a real
         * download emits in the low hundreds. Under 64 KiB the bar is smooth
         * but the Flow traffic is not worth it on a phone.
         */
        const val DEFAULT_MIN_BYTES: Long = 256L * 1024

        /** WHY 250 ms: 4 updates/second is past the point a human reads them. */
        const val DEFAULT_MIN_INTERVAL_MS: Long = 250L

        /**
         * How many [DEFAULT_MIN_BYTES] may accumulate before an emission is
         * forced regardless of elapsed time.
         *
         * WHY 8: at 256 KiB that is a forced report every 2 MiB, so a link
         * slower than roughly 8 MiB per 250 ms still moves the bar, while a
         * fast link is governed by the clock instead.
         */
        const val STARVATION_FACTOR: Long = 8L
    }
}

/**
 * A transfer rate that is not 0 on its first sample.
 *
 * WHY a smoothed average rather than an instantaneous rate: instantaneous rate
 * over a 64 KiB buffer on a bursty mobile connection swings by 10x between
 * samples, and a number that says "14 MB/s" and then "700 kB/s" makes the ETA
 * nonsense. An exponential moving average over a ~2 s window is stable enough
 * to be believed and fast enough to react to a stall.
 *
 * A rate needs a time *and* a byte delta to be defined, so the first sample is
 * necessarily 0 — hence [rate] returning 0 rather than a fabricated number.
 */
class TransferRate(
    private val smoothing: Double = 0.3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastAt: Long? = null
    private var lastBytes: Long = 0
    private var current: Double = 0.0

    /** Records progress and returns the smoothed bytes/second, 0 initially. */
    fun sample(bytesDownloaded: Long): Long {
        val now = clock()
        val previousTime = lastAt
        if (previousTime == null) {
            lastAt = now
            lastBytes = bytesDownloaded
            return 0L
        }
        val elapsedMs = now - previousTime
        if (elapsedMs <= 0L) return current.toLong()
        val deltaBytes = bytesDownloaded - lastBytes
        if (deltaBytes < 0) {
            // A restart (bytes went backwards) resets the average rather than
            // producing a negative rate.
            current = 0.0
            lastAt = now
            lastBytes = bytesDownloaded
            return 0L
        }
        val instant = deltaBytes * 1000.0 / elapsedMs
        current = if (current == 0.0) instant else current * (1 - smoothing) + instant * smoothing
        lastAt = now
        lastBytes = bytesDownloaded
        return current.toLong()
    }
}
