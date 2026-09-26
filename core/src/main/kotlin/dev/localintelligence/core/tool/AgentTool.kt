package dev.localintelligence.core.tool

import kotlinx.serialization.json.JsonObject
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs

/**
 * Risk classification. The RUNTIME decides what needs confirmation — never the
 * model, and never the tool implementation itself.
 */
enum class ToolRisk {
    READ_ONLY,
    REVERSIBLE,
    DESTRUCTIVE,
    EXTERNAL_COMMUNICATION,

    /**
     * The call leaves the device, or brings untrusted outside text into it.
     *
     * WHY A TIER OF ITS OWN RATHER THAN REUSING EXTERNAL_COMMUNICATION: that
     * tier means "a person or a service will see what the model composed" — it
     * is reasoned about by the argument inspector, which looks for recipients,
     * known contacts, and bare-link bodies. None of that reasoning describes a
     * GET. Worse, the rules attached to it are phrased for a *decision the user
     * is asked to make about content*, so a fetch inherited its dialog copy and
     * its justifications, which are then about sending rather than about
     * visiting.
     *
     * A fetch is the mirror image: nothing is composed, and the exposure is the
     * *destination* plus the fact that the response is third-party text. Naming
     * that is what lets the justification name the host.
     *
     * It cannot be executed unattended. [PolicyConfig.autoExecuteTiers] cannot
     * widen it, because `RiskPolicy.tierFloor` pins this tier to
     * `REQUIRE_CONFIRMATION` below any config value.
     */
    NETWORK_EGRESS,
    PRIVILEGED,
    ;

    val requiresConfirmation: Boolean
        get() = this == DESTRUCTIVE ||
            this == EXTERNAL_COMMUNICATION ||
            this == NETWORK_EGRESS
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val category: String,
    val schema: JsonObject,
    val risk: ToolRisk,
    val tags: Set<String> = emptySet(),
    /** Android permission this tool needs, or null if none. Documentation + UI only. */
    val requiredPermission: String? = null,
    /**
     * How much the CONTENT of this tool's observations can be believed.
     *
     * Declared here, on the definition, rather than passed per call: the tool
     * definition is the reviewed, catalogue-checked artefact, so this is a
     * claim a maintainer makes once and a tool cannot assert for itself at
     * runtime. `CatalogueAgreement` fails the build when the catalogue and the
     * tool disagree about it.
     *
     * Defaults to [ObservationOrigin.NETWORK], the pessimistic reading, so a
     * newly added network tool is fenced as hostile until someone says
     * otherwise. The fencing itself is unconditional and does not depend on
     * this being right — see
     * [dev.localintelligence.core.model.UntrustedContent.neutralise].
     */
    val observationOrigin: ObservationOrigin = ObservationOrigin.NETWORK,
)

/**
 * The result of a tool call.
 *
 * [observation] is the critical field: it is the ONLY thing fed back to the LLM.
 * It must be short, plain text, and already truncated. Never a stack trace,
 * never a raw Android object, never an unbounded JSON blob.
 */
data class ToolResult(
    val success: Boolean,
    val observation: String,
    val data: ToolArgs? = null,
    val error: ToolError? = null,
)

sealed class ToolError(val code: String, open val message: String) {
    data class InvalidArguments(override val message: String) : ToolError("invalid_arguments", message)
    data class PermissionDenied(override val message: String) : ToolError("permission_denied", message)
    data class NotFound(override val message: String) : ToolError("not_found", message)
    data class Unavailable(override val message: String) : ToolError("unavailable", message)
    data class Timeout(override val message: String) : ToolError("timeout", message)
    data class Cancelled(override val message: String) : ToolError("cancelled", message)
    data class Internal(override val message: String) : ToolError("internal", message)
}

/**
 * Context handed to a tool at execution time. Pure data — no Android types.
 * The Android layer is responsible for filling [permissionGranted].
 */
data class ToolContext(
    val permissionGranted: Boolean = true,
    /** Whether the user has already approved this specific call. */
    val userConfirmed: Boolean = false,
    val signal: CancellationSignal = CancellationSignal.None,
)

/** Minimal cancellation primitive so :core stays free of coroutine-internal types. */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object {
        val None = CancellationSignal { false }
    }
}

/**
 * A single tool the agent can call.
 *
 * Implementations MUST:
 *  - never throw for an expected failure; return a failed [ToolResult]
 *  - keep [ToolResult.observation] under the observation budget
 *  - coerce and clamp arguments defensively (models emit wrong types)
 */
interface AgentTool {
    val definition: ToolDefinition
    suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult
}
