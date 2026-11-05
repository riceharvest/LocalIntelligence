package dev.localintelligence.app.settings

import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import java.io.File

/**
 * What one rung of the Simple load ladder asks llama.cpp for.
 *
 * @param contextLength the n_ctx for this rung, never above the window the app chose.
 * @param threads the thread count, or 0 for the backend's own choice.
 * @param cpuOnly true to skip any GPU, whatever the Compute setting says.
 */
data class LoadAttempt(val contextLength: Int, val threads: Int, val cpuOnly: Boolean)

/**
 * Simple mode's load ladder and the memory of which rung worked.
 *
 * ## Why a ladder
 *
 * A phone that cannot start a model at the window and thread count the app picked is not a dead
 * end. The window shrinks first (it is the largest allocation), then the thread count (fewer
 * threads means less peak working memory and less contention with the rest of the phone), then
 * the CPU alone. Each rung is a little less ambitious, and the first one that loads is kept.
 *
 * ## Why a marker file, not only the settings row
 *
 * A native crash, or the system killing the process for memory, gives no exception to catch.
 * So before every native load the rung is written to [marker] with a plain, synchronous file
 * write. It is deleted when the load returns. A marker found at the next load means that load
 * never returned, so the next launch starts one rung lower and does not repeat the crash. The
 * settings store writes asynchronously (`apply`), which a crash a few milliseconds later can lose,
 * so the settings row is only used for the rung that worked, written after the load has returned.
 *
 * ## What resets it
 *
 * A model the person picks themselves (from Models, or a fresh download) starts again from the
 * first rung. Automatic loads never reset, so a model that crashed the phone is not retried
 * forever: after the last rung has crashed, the automatic load stops and the chat says it could
 * not start, until the person picks the model again.
 *
 * Keys are the model's URI, hashed to a short string so it fits a settings row.
 */
class LoadSafety(
    private val settings: SettingsStore,
    private val marker: File,
    private val cores: Int,
) {

    /** The rung the next automatic load of [key] starts at. Reads only; it changes nothing. */
    fun startRung(key: String): Int {
        val crashed = crashedRung(key)?.let { it + 1 } ?: 0
        val remembered = rememberedRung(key)
        return maxOf(crashed, remembered).coerceAtMost(LAST)
    }

    /** True when every rung has crashed the phone, so automatic loads stop until the person picks the model. */
    fun exhausted(key: String): Boolean = crashedRung(key) == LAST

    /** Writes the marker before a native load of rung [rung]. Synchronous, so a crash cannot lose it. */
    fun attempting(key: String, rung: Int) {
        runCatching { marker.writeText("${keyOf(key)}|$rung") }
    }

    /** The load of [rung] returned. A success is remembered for the next launch. */
    fun attemptReturned(key: String, rung: Int, ok: Boolean) {
        runCatching { marker.delete() }
        if (ok) settings.set(REMEMBERED, "${keyOf(key)}|$rung")
    }

    /**
     * A reply was cut off when the process died (the run mark was still set at the next launch).
     * The next load of [key] starts one rung lower, so a reply that crashes the phone is not
     * repeated on the next launch in the same configuration.
     */
    fun stepDownAfterDeath(key: String) {
        val next = (startRung(key) + 1).coerceAtMost(LAST)
        settings.set(REMEMBERED, "${keyOf(key)}|$next")
    }

    /** A model the person picked: forget the rung and the crash, and start at the first rung. */
    fun reset(key: String) {
        runCatching { marker.delete() }
        settings.set(REMEMBERED, "")
    }

    /** The window [rung] asks for, from the window the app chose. Never below [FLOOR_CONTEXT]. */
    fun contextAt(rung: Int, chosen: Int): Int = attempt(rung, chosen).contextLength

    /** What [rung] asks llama.cpp for. Rung 0 is exactly what the app would have asked for without the ladder. */
    fun attempt(rung: Int, chosen: Int): LoadAttempt {
        val divisor = DIVISORS[rung.coerceIn(0, LAST)]
        val context = if (divisor == 1) chosen else maxOf(minOf(chosen, FLOOR_CONTEXT), chosen / divisor)
        val threads = if (rung >= FEWER_THREADS_RUNG) maxOf(2, cores / 2) else 0
        return LoadAttempt(contextLength = context, threads = threads, cpuOnly = rung >= CPU_RUNG)
    }

    private fun crashedRung(key: String): Int? {
        val text = runCatching { marker.readText() }.getOrNull() ?: return null
        val (markedKey, rung) = text.split('|').let { it.getOrNull(0) to it.getOrNull(1)?.toIntOrNull() }
        return rung?.takeIf { markedKey == keyOf(key) }
    }

    private fun rememberedRung(key: String): Int {
        val stored = settings.stored(REMEMBERED)
        if (!stored.startsWith("${keyOf(key)}|")) return 0
        return stored.substringAfter('|').toIntOrNull()?.coerceIn(0, LAST) ?: 0
    }

    companion object {
        /** The rungs, from the first (the app's own choice) to the last (CPU, fewer threads, smallest window). */
        const val LAST = 3

        /** Rung at which the thread count is lowered, and the rung at which the CPU is forced. */
        private const val FEWER_THREADS_RUNG = 2
        private const val CPU_RUNG = 3

        /** Window divisor per rung: full, half, quarter, quarter. */
        private val DIVISORS = intArrayOf(1, 2, 4, 4)

        /** The smallest window a retry asks for, the same floor the context setting uses. */
        const val FLOOR_CONTEXT = 2048

        /** Short, stable key for a model URI. A hash is enough: a collision only means one retry starts lower. */
        fun keyOf(uri: String): String = Integer.toHexString(uri.hashCode())

        val REMEMBERED = SettingDef.Text(
            "model.load_rung", "Load rung", "The load step that last started the model.",
            SettingSection.MODEL, default = "", maxLength = 64,
        )
    }
}
