package dev.localintelligence.core.policy

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A parked action awaiting a human decision.
 *
 * WHY this class exists at all, rather than a `Boolean` the loop carries: the
 * classic agent-harness vulnerability is TOCTOU — time-of-check to time-of-use.
 * The model asks to send one message, the user reads "send to Alice", the
 * caller swaps the recipient before executing, and the user approved something
 * that was never proposed. Nothing about a `Boolean` can prevent that, because
 * the boolean is not bound to *what* it authorises.
 *
 * So a session is a capability, not a flag:
 *  - it freezes the tool, the risk, and the exact arguments at park time;
 *  - it resolves **once** — a second approve/deny is refused, not silently
 *    accepted;
 *  - execution is only reachable through an [Approval], and an approval can be
 *    spent **once** and only against the arguments it was issued for.
 *
 * Mutating the arguments after approval is therefore not "detected at
 * execution" — it is *impossible to express*, because the arguments the tool
 * receives come from the approval, not from the caller.
 */
class ConfirmationSession internal constructor(
    /** Deterministic handle. No clock, no randomness — see [park]. */
    val id: String,
    private val decision: Decision,
    private val fingerprint: String,
) {
    /**
     * What the session currently allows. A session moves PENDING to exactly
     * one terminal state and never leaves it.
     */
    enum class State { PENDING, APPROVED, DENIED, SPENT }

    @Volatile
    private var state: State = State.PENDING

    /** Current state. Safe to read from any thread. */
    val currentState: State get() = state

    /** True while the session can still be resolved. */
    val isPending: Boolean get() = state == State.PENDING

    /** The tool this session is about. Immutable, so it cannot be redirected. */
    val toolName: String get() = decision.toolName

    /** The risk tier that triggered the gate. */
    val risk: ToolRisk get() = decision.risk

    /**
     * The arguments as proposed, exactly. Exposed read-only for rendering the
     * confirmation UI; never for execution.
     */
    val proposedArguments: ToolArgs get() = decision.arguments

    /** The tool definition as proposed. */
    val definition: ToolDefinition get() = decision.definition

    /** The sentence shown to the human. */
    val justification: String get() = decision.justification

    /** The rule that produced the gate, for the debug trace. */
    val rule: PolicyRule get() = decision.rule

    /** The original decision, for auditing a completed session. */
    val parkedDecision: Decision get() = decision

    /**
     * Resolves the session exactly once.
     *
     * A second call — whatever it asks for — returns [ConfirmationOutcome.AlreadyResolved]
     * and changes nothing. That is what makes a replayed "approve" a no-op
     * instead of a second execution.
     */
    fun resolve(approved: Boolean): ConfirmationOutcome {
        // Check-and-set must be atomic across threads, or two callers racing on
        // a double-tap both see PENDING and both get an Approval.
        synchronized(this) {
            if (state != State.PENDING) {
                return ConfirmationOutcome.AlreadyResolved(
                    id = id,
                    requestedApproval = approved,
                    wasState = state,
                    reason = "This action was already ${if (state == State.DENIED) "denied" else "decided"}. " +
                        "It cannot be decided twice.",
                )
            }
            state = if (approved) State.APPROVED else State.DENIED
        }
        return if (approved) {
            ConfirmationOutcome.Approved(Approval(session = this))
        } else {
            ConfirmationOutcome.Denied(
                id = id,
                toolName = toolName,
                reason = "$toolName was declined. Do not call it again with the same arguments; " +
                    "do something else or answer without it.",
            )
        }
    }

    /**
     * True when [toolName]/[args] are byte-for-byte what this session parked.
     *
     * Used by the runtime to assert the proposal is unchanged, and by tests to
     * prove the fingerprint is actually sensitive to argument content.
     */
    fun matches(toolName: String, args: ToolArgs): Boolean =
        toolName == this.toolName && fingerprintOf(toolName, args) == fingerprint

    /** The canonical fingerprint this session is bound to. For tests and traces. */
    fun fingerprint(): String = fingerprint

    /**
     * The capability handed out by an approval.
     *
     * It is the *only* way to obtain the arguments to execute, and it yields
     * them once. [claim] returning null is the terminal state of this session:
     * either the arguments were swapped (TOCTOU) or the approval was replayed.
     */
    inner class Approval internal constructor(private val session: ConfirmationSession) {
        /** True once this approval has been spent. */
        val isSpent: Boolean get() = session.state == State.SPENT

        /**
         * Spends the approval, returning the exact arguments to execute.
         *
         * Returns null — and never the arguments — when the caller's
         * [toolName]/[args] do not match what the user actually saw. Swapping
         * the arguments after approval is therefore a silent no-op rather than
         * an attack the runtime has to remember to check for.
         */
        fun claim(toolName: String, args: ToolArgs): ToolArgs? = synchronized(session) {
            if (session.state != State.APPROVED) return null
            if (toolName != session.toolName) return null
            if (fingerprintOf(toolName, args) != session.fingerprint) return null
            session.state = State.SPENT
            // The session's own frozen arguments are returned, not the caller's.
            // Even if a caller could mutate its map, it is not what runs.
            session.decision.arguments
        }
    }

    companion object {
        /**
         * Parks an action for confirmation.
         *
         * Returns null for a decision that is not a confirmation gate. WHY null
         * rather than an exception: the caller is a loop that must never throw
         * (`AgentController.guarded`), and a null here is a programming error
         * the tests pin down, not a runtime condition to recover from.
         */
        fun park(decision: Decision, id: String? = null): ConfirmationSession? {
            if (!decision.requiresConfirmation) return null
            val sessionId = id ?: "cfm:${decision.toolName}:${fingerprintOf(decision.toolName, decision.arguments)}"
            return ConfirmationSession(
                id = sessionId,
                decision = decision,
                fingerprint = fingerprintOf(decision.toolName, decision.arguments),
            )
        }

        /**
         * Canonical identity of a proposed call.
         *
         * Keys are sorted recursively so JSON key order cannot change a
         * fingerprint, while *values* are fully type- and content-sensitive:
         * `"8"` and `8` differ, `["a","b"]` and `["b","a"]` differ. A weaker
         * fingerprint would let a mutation slip through as "the same action".
         */
        fun fingerprintOf(toolName: String, args: ToolArgs): String =
            "$toolName#${canonical(args, depth = 0)}"

        /** Recursion cap. A deeply nested payload is a malformed one. */
        private const val MAX_DEPTH = 16

        private fun canonical(element: JsonElement, depth: Int): String {
            if (depth > MAX_DEPTH) return "\"<too-deep>\""
            return when (element) {
                is JsonNull -> "null"
                is JsonObject -> element.entries
                    .sortedBy { it.key }
                    .joinToString(",", "{", "}") { (k, v) -> "\"$k\":${canonical(v, depth + 1)}" }

                is JsonArray -> element.joinToString(",", "[", "]") { canonical(it, depth + 1) }
                is JsonPrimitive -> if (element.isString) {
                    "\"${element.content}\""
                } else {
                    // Distinguishes numbers, booleans, and the string "8" from 8.
                    element.content
                }
            }
        }
    }
}

/**
 * The result of trying to resolve a session. Exhaustive, so a caller that
 * forgets a branch does not compile.
 */
sealed interface ConfirmationOutcome {
    /** The human approved. The [Approval] must be claimed to execute. */
    data class Approved(val approval: ConfirmationSession.Approval) : ConfirmationOutcome

    /** The human declined. [reason] is what the model is told. */
    data class Denied(val id: String, val toolName: String, val reason: String) : ConfirmationOutcome

    /** Already decided. [wasState] makes the audit trail complete. */
    data class AlreadyResolved(
        val id: String,
        val requestedApproval: Boolean,
        val wasState: ConfirmationSession.State,
        val reason: String,
    ) : ConfirmationOutcome
}
