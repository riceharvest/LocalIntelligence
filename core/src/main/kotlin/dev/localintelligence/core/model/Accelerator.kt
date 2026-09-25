package dev.localintelligence.core.model

/**
 * The hardware a model actually ran on.
 *
 * ## Why this is a core type at all
 *
 * The whole reason a second [ModelBackend] exists is to answer "which runtime is
 * faster on *this* phone" with a measurement. A tok/s number is meaningless
 * without the accelerator it came from — a GPU figure and a CPU figure are
 * different claims, and reporting either without naming the hardware is how a
 * benchmark ends up comparing two numbers that were never comparable.
 *
 * So the active accelerator has to travel with the capabilities, and the fallback
 * has to travel with the accelerator. This file is that payload, and it is pure
 * JVM on purpose: `:core` is where anything testable in seconds lives, and
 * "which backend did we pick and why" is a decision, not a platform detail.
 *
 * Nothing in `:core` may learn whether an NPU exists — that is an
 * [AcceleratorProbe], supplied by `:android` and faked in tests. See
 * [AcceleratorSelector].
 */
enum class AcceleratorKind(
    /** Stable id, used in configs and in benchmark output. */
    val id: String,
    /**
     * Best first. Deliberately an explicit number rather than `ordinal`, because
     * a benchmark table sorted by enum declaration order silently reorders the
     * moment somebody inserts a value, and the order is a correctness claim.
     */
    val rank: Int,
) {
    NPU(id = "npu", rank = 0),
    GPU(id = "gpu", rank = 1),
    CPU(id = "cpu", rank = 2),
    ;

    /** True when this hardware is strictly faster than [other], per [rank]. */
    fun isFasterThan(other: AcceleratorKind): Boolean = rank < other.rank

    override fun toString(): String = name

    companion object {
        /**
         * Parses an id, case- and whitespace-insensitively.
         *
         * Returns null for anything unrecognised rather than guessing: a typo in a
         * stored preference must not silently become CPU, because a user who asked
         * for an NPU and got CPU with no explanation is the exact failure this file
         * exists to prevent. Callers that want a default use [parseOr].
         */
        fun fromId(raw: String?): AcceleratorKind? {
            val key = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == key }
        }

        /** As [fromId], substituting [fallback] for an unrecognised value. */
        fun parseOr(raw: String?, fallback: AcceleratorKind = CPU): AcceleratorKind =
            fromId(raw) ?: fallback
    }
}

/**
 * What the user asked for, which is not the same as what they get.
 *
 * The distinction is the whole point. [AUTO] is the default and must never be
 * treated as a synonym for "whatever is convenient" — it has to resolve against
 * what the device can actually do and say so when the answer is not NPU.
 */
enum class AcceleratorPreference(
    val id: String,
    /**
     * What this preference is asking to be downgraded from. `AUTO` asks for the
     * best hardware present; the rest ask for a specific tier and are still
     * allowed to fall back, because a phone that cannot run the requested tier
     * should not fail to load a model at all.
     */
    val idealKind: AcceleratorKind,
) {
    AUTO(id = "auto", idealKind = AcceleratorKind.NPU),
    NPU(id = "npu", idealKind = AcceleratorKind.NPU),
    GPU(id = "gpu", idealKind = AcceleratorKind.GPU),
    CPU(id = "cpu", idealKind = AcceleratorKind.CPU),
    ;

    /**
     * The order this preference is willing to settle for, best first.
     *
     * Ending at [AcceleratorKind.CPU] is what makes selection total. The CPU
     * backend needs no vendor library, so there is always an answer, and a
     * missing accelerator can never become a failed load. Every entry after the
     * first is a candidate the user may be silently downgraded to, and each one
     * that is skipped must appear in the report.
     */
    fun candidates(): List<AcceleratorKind> = when (this) {
        AUTO -> listOf(
            AcceleratorKind.NPU,
            AcceleratorKind.GPU,
            AcceleratorKind.CPU,
        )
        NPU -> listOf(
            AcceleratorKind.NPU,
            AcceleratorKind.GPU,
            AcceleratorKind.CPU,
        )
        GPU -> listOf(AcceleratorKind.GPU, AcceleratorKind.CPU)
        CPU -> listOf(AcceleratorKind.CPU)
    }

    override fun toString(): String = name

    companion object {
        val DEFAULT = AUTO

        /** Null for an unrecognised value, for the same reason as [AcceleratorKind.fromId]. */
        fun fromId(raw: String?): AcceleratorPreference? {
            val key = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == key }
        }

        /**
         * As [fromId], defaulting to [DEFAULT].
         *
         * The one place a bad value is tolerated, because a persisted preference
         * that no longer parses (a rename, a downgrade) must not stop the app from
         * loading a model. `AUTO` is the safe landing: it re-derives the answer from
         * the device rather than pinning a tier nobody asked for.
         */
        fun parseOr(raw: String?): AcceleratorPreference = fromId(raw) ?: DEFAULT
    }
}

/**
 * What a probe found out about one piece of hardware.
 *
 * [missingLibraries] is the evidence, not decoration. "NPU unavailable" is not
 * actionable; "NPU unavailable: no delegate library in
 * /data/app/~~x/lib/arm64" is something a user or a bug report can act on, and it
 * is the difference between a fallback that looks deliberate and one that looks
 * broken.
 */
data class AcceleratorStatus(
    val kind: AcceleratorKind,
    val available: Boolean,
    /** A complete, human-readable sentence. Never empty, even when available. */
    val detail: String,
    /** The specific libraries or paths that are absent. Empty when [available]. */
    val missingLibraries: List<String> = emptyList(),
    /**
     * True when the probe could not form a confident answer and is declining to
     * claim availability.
     *
     * Separate from `!available` because "I looked and it is not there" and "I
     * could not look" are different facts, and only the second one is worth a
     * bug report. Treated as unavailable for selection either way.
     */
    val probeFailed: Boolean = false,
) {
    init {
        require(detail.isNotBlank()) {
            "an accelerator status with no detail cannot be shown to a user; " +
                "the UI has nothing to render"
        }
    }
}

/**
 * The device's answer to "what can you actually run".
 *
 * An interface, not a function, because the one thing that must be testable is
 * exactly the thing that needs a device: a phone with an NPU, and a phone without
 * one, have to both be reachable from a JVM test. `:android` supplies the real
 * implementation; tests supply a set literal.
 */
fun interface AcceleratorProbe {
    /**
     * Probes [kind], or returns null when this probe knows nothing about it.
     *
     * Null is allowed rather than an "unavailable" status so that a probe
     * covering only the tiers it knows about does not have to invent reasons for
     * the rest — [AcceleratorSelector] treats null as unavailable and says so.
     */
    fun probe(kind: AcceleratorKind): AcceleratorStatus?
}

/**
 * The answer, in the form the UI and the benchmark consume.
 *
 * [active] and [fallbackReason] are read together or not at all. An `active` with
 * no reason next to it is the ambiguous case this type exists to remove: a reader
 * cannot tell an NPU run from a CPU run that happened to be labelled NPU.
 */
data class AcceleratorReport(
    val requested: AcceleratorPreference,
    val active: AcceleratorKind,
    /**
     * Why [active] is not the best thing that was asked for, in one sentence.
     *
     * Null **only** when the ideal tier was actually delivered — including the
     * `AUTO` case where NPU was unavailable and GPU was genuinely the best
     * hardware present, because nothing was lost. Any path that skips a
     * candidate fills this in.
     */
    val fallbackReason: String?,
    /** Per-tier evidence, so a support answer can name the missing library. */
    val statuses: Map<AcceleratorKind, AcceleratorStatus>,
) {
    /** True when the run is not on the hardware the preference asked for. */
    val degraded: Boolean get() = fallbackReason != null

    /** One line for a UI footer or a benchmark row. Never empty. */
    fun describe(): String = buildString {
        append(active.name)
        if (degraded) {
            append(" (wanted ")
            append(requested.id)
            append("): ")
            append(fallbackReason)
        } else {
            append(" (requested ")
            append(requested.id)
            append(')')
        }
    }

    companion object {
        /**
         * The report for a backend that never resolved an accelerator.
         *
         * CPU is the honest answer here: it is the one tier that needs no vendor
         * library, so it is the only thing that can be said about a backend that
         * did not report anything. Note it carries *no* fallback reason — nothing
         * was downgraded, because nothing was ever chosen.
         */
        val UNKNOWN = AcceleratorReport(
            requested = AcceleratorPreference.DEFAULT,
            active = AcceleratorKind.CPU,
            fallbackReason = null,
            statuses = emptyMap(),
        )
    }
}

/**
 * Turns a preference and a probe into a decision.
 *
 * ## The rules, and why each one exists
 *
 * 1. **Best available wins.** `AUTO` on a phone with a working NPU is an NPU run,
 *    not a cautious CPU one. An `AUTO` that always chose CPU would be a lie about
 *    what the code does.
 * 2. **Explicit preferences are honoured exactly when possible, and degraded
 *    otherwise.** A user who pinned CPU to get a comparable baseline must not be
 *    moved to GPU just because it would be faster — and a user who pinned NPU on
 *    a phone without one must get a model, not an error dialog.
 * 3. **Every degradation is explained.** [AcceleratorReport.fallbackReason] is
 *    non-empty on every path that skips a candidate, and names what was missing.
 * 4. **Selection never fails.** CPU is the last candidate in every preference
 *    order, so [resolve] always returns a report. A phone with no NPU libraries
 *    and no OpenCL gets CPU, and the reason says precisely that.
 */
object AcceleratorSelector {

    /**
     * Resolves [preference] against [probe].
     *
     * Total by construction: the candidate list always ends in
     * [AcceleratorKind.CPU], and CPU is treated as available when no probe claims
     * otherwise. That last part is the safety net — a probe that returns null for
     * CPU (a partial probe, a test double that forgot it) must not turn into an
     * unhandled error at load time.
     */
    fun resolve(
        preference: AcceleratorPreference,
        probe: AcceleratorProbe,
    ): AcceleratorReport {
        val order = preference.candidates()
        val statuses = LinkedHashMap<AcceleratorKind, AcceleratorStatus>(order.size)

        var chosen: AcceleratorKind? = null
        val skipped = mutableListOf<AcceleratorKind>()

        for (kind in order) {
            val status = probe.probe(kind) ?: unavailable(kind, "not probed on this device")
            statuses[kind] = status
            val usable = status.available || kind == AcceleratorKind.CPU
            if (chosen == null && usable) {
                chosen = kind
            } else if (chosen == null) {
                skipped += kind
            }
        }

        val active = chosen ?: AcceleratorKind.CPU
        val reason = if (active == order.first()) {
            null
        } else {
            explain(order.first(), active, skipped, statuses)
        }
        return AcceleratorReport(
            requested = preference,
            active = active,
            fallbackReason = reason,
            statuses = statuses,
        )
    }

    /**
     * One sentence naming every tier that was passed over and why.
     *
     * Every skipped tier is listed, not just the first. A user who pinned NPU and
     * landed on CPU has been declined twice over, and saying only "NPU
     * unavailable" leaves them thinking the GPU was tried and worked.
     */
    private fun explain(
        wanted: AcceleratorKind,
        active: AcceleratorKind,
        skipped: List<AcceleratorKind>,
        statuses: Map<AcceleratorKind, AcceleratorStatus>,
    ): String {
        val causes = skipped.mapNotNull { kind ->
            statuses[kind]?.detail?.takeIf { it.isNotBlank() }
        }
        val because = when {
            causes.isEmpty() -> "the ${wanted.name} path was not available on this device"
            causes.size == 1 -> causes.first()
            else -> causes.joinToString("; ")
        }
        return "$because; using ${active.name} instead"
    }

    private fun unavailable(kind: AcceleratorKind, why: String) = AcceleratorStatus(
        kind = kind,
        available = false,
        detail = "${kind.name} unavailable: $why",
    )
}

/**
 * A [ModelBackend] that can say which hardware it ran on.
 *
 * Additive and separate from [ModelCapabilities] on purpose.
 * [ModelCapabilities] is a frozen five-field contract that several backends and
 * the agent core already construct; adding an accelerator field to it would break
 * every construction site and change the meaning of a type that is deliberately
 * about *model* abilities rather than *machine* ones. An opt-in interface adds
 * the capability without touching anything that exists.
 *
 * [ModelBackend.acceleratorReport] reads this interface and degrades to
 * [AcceleratorReport.UNKNOWN] for a backend that does not implement it, so callers
 * never need a cast.
 */
interface AcceleratorAware {
    val acceleratorReport: AcceleratorReport
}

/**
 * The active accelerator for any backend.
 *
 * [AcceleratorReport.UNKNOWN] when the backend does not report one, so a UI can
 * bind to it unconditionally and simply hide the row when nothing is known. This
 * is what makes the field usable across backends without every implementation
 * having to care about accelerators at all.
 */
val ModelBackend.acceleratorReport: AcceleratorReport
    get() = (this as? AcceleratorAware)?.acceleratorReport ?: AcceleratorReport.UNKNOWN
