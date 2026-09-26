package dev.localintelligence.android.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import dev.localintelligence.core.metrics.MemorySample
import dev.localintelligence.core.metrics.PlatformMemory
import dev.localintelligence.core.metrics.PlatformMemoryReader

/**
 * Reads this process's real memory off the device, right now.
 *
 * ## WHY THIS IS IN `:android` AND NOT IN `:core`
 *
 * `:core` is pure JVM by rule and by a CI check
 * (`grep -rn '^import android\.' core/src/main/`). `Debug.getMemoryInfo` and
 * `ActivityManager` are Android. The seam is
 * [dev.localintelligence.core.metrics.PlatformMemoryReader]; this file is the
 * one implementation of it, and it is the only place in the project that
 * mentions those two classes.
 *
 * ## WHY `Debug.getMemoryInfo` AND NOT `dumpsys meminfo`
 *
 * `dumpsys` is a shell tool, so the only way to read it from inside the app is
 * to execute it — a process spawn, on the critical path, for a number a
 * platform API already returns. `Debug.getMemoryInfo()` fills a
 * `Debug.MemoryInfo` with the same PSS accounting, in-process, in about a
 * microsecond. `docs/measure/measure_ram.sh` still uses `dumpsys` for the
 * *out-of-process* cross-check, which is the thing this cannot do: it sees
 * only its own process, and cannot tell you what the rest of the system is
 * doing.
 *
 * ## WHAT IT DELIBERATELY DOES NOT CLAIM
 *
 * It reports the app process. It does not report the model the user is about to
 * load, a second process, or anything about the device's free memory. A number
 * that answers "what does this conversation cost on top of the model" and
 * silently implies it answers "will this model fit" is the conflation
 * `RamGateCrossCheck` exists to catch, and this reader does not make it.
 */
object AndroidMemoryReader : PlatformMemoryReader {

    /**
     * One `Debug.MemoryInfo` instance, reused.
     *
     * `Debug.getMemoryInfo` fills a caller-supplied object rather than
     * returning one, and a reading is taken a handful of times per run, so
     * there is no case for allocating a fresh one each time. `getMemoryInfo` is
     * a read of process state, not a mutation of the object, so reuse is safe
     * — but the values are copied out immediately, so no sample ever holds a
     * reference to this.
     */
    private val info = Debug.MemoryInfo()

    override fun read(): PlatformMemory {
        // Throws on a device where the platform will not answer. Callers treat
        // that as "not measured" rather than as a failure; see
        // RunMemoryJournal.JournalProbe, which is where the catch lives.
        Debug.getMemoryInfo(info)

        val runtime = Runtime.getRuntime()
        return PlatformMemory(
            pssBytes = info.totalPss.toLong() * KILOBYTE,
            // There is no `totalRss` FIELD on `Debug.MemoryInfo` in the API 36
            // stub this project compiles against — `javap` shows only
            // `getTotalPss`, `getTotalSwappablePss`, and the
            // private/shared dirty+clean getters, none of which is RSS. It is
            // reached instead through the documented `getMemoryStat` string
            // lookup, which reads smaps and is absent or unparsable on some
            // devices. Both outcomes are handled explicitly: a number when the
            // platform gives one, [MemorySample.UNKNOWN_BYTES] when it does not.
            //
            // Deriving an approximation from the dirty+clean getters was
            // considered and rejected. RSS-from-dirty-plus-clean is not RSS, and
            // shipping it under the field name `rssBytes` is exactly the
            // estimate-presented-as-a-measurement this project keeps undoing.
            // An absent figure that says so is worth more than a plausible one.
            rssBytes = rssFromStats(info),
            dalvikPssBytes = info.dalvikPss.toLong() * KILOBYTE,
            nativePssBytes = info.nativePss.toLong() * KILOBYTE,
            // A different API from the one above and not redundant with it:
            // this is the malloc arena the runtime tracks, which is what a
            // JNI-loaded model grows, and it moves when the arena grows rather
            // than when pages are touched.
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaHeapMaxBytes = runtime.maxMemory(),
        )
    }

    /**
     * Total RAM the device reports, for the "how much is there" half of a
     * memory reading.
     *
     * NOT part of [PlatformMemory], and that is deliberate: this is a property
     * of the DEVICE, not of this process, and putting it on a per-process
     * sample is how a screen ends up comparing a process's PSS against the
     * device's total and calling the difference "free". The device figure is
     * read where a device figure belongs — the self-check's RAM-gate check.
     */
    fun deviceTotalBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MemorySample.UNKNOWN_BYTES
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /**
     * RSS in bytes, or [MemorySample.UNKNOWN_BYTES] when the platform will not say.
     *
     * `getMemoryStat` returns a raw smaps line value as a string — on most
     * devices `"total_rss"` is in kilobytes, the same unit as every other
     * figure on `Debug.MemoryInfo`, and the conversion below assumes so. It is
     * a string for a reason: the set of keys is device- and
     * kernel-dependent, and an absent key is a normal outcome rather than an
     * error. Every failure path here returns the explicit unknown; none of them
     * returns zero, because a process holding a 2 GB model does not have an
     * empty resident set and a zero would render as a measurement.
     */
    private fun rssFromStats(info: Debug.MemoryInfo): Long {
        val raw = try {
            info.getMemoryStat("total_rss")
        } catch (_: Throwable) {
            return MemorySample.UNKNOWN_BYTES
        }
        val kb = raw?.trim()?.toLongOrNull() ?: return MemorySample.UNKNOWN_BYTES
        if (kb < 0) return MemorySample.UNKNOWN_BYTES
        return kb * KILOBYTE
    }

    /** `Debug.MemoryInfo` reports every figure in kilobytes. */
    private const val KILOBYTE = 1024L
}
