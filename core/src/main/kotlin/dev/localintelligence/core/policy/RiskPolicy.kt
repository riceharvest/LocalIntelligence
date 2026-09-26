package dev.localintelligence.core.policy

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk

/**
 * The deterministic risk policy.
 *
 * This is `docs/architecture.md` §8 made testable: *"The runtime decides. Never
 * ask the model whether something needs confirmation."* Every branch here is
 * plain Kotlin over pure data — no clock, no randomness, no I/O, no Android —
 * so the same call always yields the same [Decision] and a decision can be
 * replayed from a trace months later.
 *
 * The evaluation order is the security property, so it is written top-down and
 * each step can only make the outcome *more* restrictive than the one below:
 *
 *  1. [ToolRisk.PRIVILEGED] → BLOCK. Not confirmable, not configurable. A
 *     privileged tool (accessibility control, root, Shizuku) is the one thing
 *     that turns a prompt-injection bug into remote code execution on the
 *     device. Confirmation is not a control here; it is a speed bump.
 *  2. Unparseable or malformed arguments → BLOCK. We do not know what the call
 *     does, so we do not run it. A model that emits `{"count": "many"}` has
 *     not asked for permission, it has failed to state a request.
 *  2b. A required argument the call left out → BLOCK. Same reasoning, and this
 *     is the check that matters most for a 1-3B model specifically: such a model
 *     emits `{}` far more often than a competent one, and an incomplete call to
 *     a DESTRUCTIVE tool otherwise reaches a confirmation dialog describing an
 *     action with no target. The user is asked to approve a call that cannot
 *     mean anything, which is how a safety dialog trains people to click through
 *     it.
 *  3. Permission not granted → REQUIRE_PERMISSION. Distinct from BLOCK
 *     because the remedy is a system dialog, not refusal.
 *  4. Path escape → REQUIRE_CONFIRMATION. Escalated even for tiers that would
 *     otherwise run, because "delete" inside app storage and "delete" of the
 *     user's Documents folder are not the same request.
 *  5. Blast radius / rate limit → BLOCK. One task cannot quietly delete thirty
 *     files one at a time.
 *  6. Bulk → BLOCK for DESTRUCTIVE, REQUIRE_CONFIRMATION otherwise. The cost of
 *     a wrong bulk call scales with the count, so the threshold is stricter
 *     than for a single item.
 *  7. External-comms argument analysis → REQUIRE_CONFIRMATION, with a bare-link
 *     body called out explicitly.
 *  8. Caller rules → used only to restrict further, never to lift 1–7.
 *  9. Risk tier → the documented fallback. Anything not explicitly safe and
 *     not matched above is UNCLASSIFIED → BLOCK.
 *
 * Step 9's default is the reason this class is safe to extend: a new
 * [ToolRisk] constant cannot be executed by accident, because the fallback for
 * "I have no rule for this" is denial, not permission.
 */
class RiskPolicy(
    private val config: PolicyConfig = PolicyConfig.default,
    private val tracker: BlastRadiusTracker = BlastRadiusTracker(config),
) {
    /**
     * Decides what may happen with [definition] called with [args].
     *
     * Total: it never throws and always returns a [Decision] with a
     * non-blank justification. Callers in the agent loop can rely on that.
     */
    fun evaluate(definition: ToolDefinition, args: ToolArgs): Decision {
        // (1) Privileged is unconditional and comes first so that no later
        //     step — including a permissive caller rule — can reach it.
        if (definition.risk == ToolRisk.PRIVILEGED) {
            return decide(
                definition, args, PolicyOutcome.BLOCK, PolicyRule.PRIVILEGED_DISABLED,
                "${definition.name} uses privileged device access, which is disabled in this build. " +
                    "It cannot be enabled by configuration or confirmation, because it would let any " +
                    "message in this conversation act as code on this phone.",
            )
        }

        // (2) Privileged ARGUMENTS, regardless of the declared tier. The tier
        //     is the tool author's claim; this is the one check that does not
        //     take it at face value, so an accessibility-service enabler
        //     mislabelled REVERSIBLE cannot auto-execute.
        if (definition.risk in PolicyConfig.PRIVILEGED_ARGUMENT_TIERS) {
            val marker = findPrivilegedMarker(args, config)
            if (marker != null) {
                return decide(
                    definition, args, PolicyOutcome.BLOCK, PolicyRule.PRIVILEGED_DISABLED,
                    "${definition.name} carries a privileged capability (\"$marker\") in its arguments, " +
                        "whatever risk tier it declares. Handing an app control of this device is not " +
                        "something a confirmation dialog can make safe.",
                )
            }
        }

        val analysis = ArgumentInspector.analyze(args, config, requiredArguments(definition))

        // (2) Malformed arguments. Null beats a guess: an argument we cannot
        //     read is an argument whose effect we cannot bound.
        if (analysis.malformed.isNotEmpty()) {
            return decide(
                definition, args, PolicyOutcome.BLOCK, PolicyRule.MALFORMED_ARGUMENTS,
                "${definition.name} was called with arguments this runtime cannot interpret " +
                    "(${analysis.malformed.joinToString()}). An action whose scope cannot be read " +
                    "will not be run.",
            )
        }

        // (2b) A required argument the call did not supply. Checked here, and not
        //      only in `ToolCallValidator`, because this is the LAST gate before
        //      the action and the validator is a substitutable seam.
        //
        //      WHY BLOCK AND NOT A CONFIRMATION: the call cannot succeed, so a
        //      dialog is asking the user to approve a no-op. And it is exactly the
        //      destructive case that makes this worth a check — `files.write_text`
        //      requires `content` and is DESTRUCTIVE, so without this the loop
        //      would build a confirmation for "overwrite a file" with nothing to
        //      overwrite and the user would be asked to say yes to a sentence
        //      about an unspecified target.
        if (analysis.missing.isNotEmpty()) {
            return decide(
                definition, args, PolicyOutcome.BLOCK, PolicyRule.MISSING_ARGUMENTS,
                "${definition.name} was called without ${analysis.missing.joinToString()}, which " +
                    "it requires, so it was not run. Call it again with those arguments. Do not " +
                    "guess a value for one you were not given.",
            )
        }

        // (3) Permission. A tool that names a permission the host has not
        //     granted gets told so, rather than failing opaquely mid-call.
        val permission = definition.requiredPermission
        if (permission != null && permission !in config.grantedPermissions) {
            return decide(
                definition, args, PolicyOutcome.REQUIRE_PERMISSION, PolicyRule.PERMISSION_NOT_GRANTED,
                "${definition.name} needs the $permission permission, which has not been granted. " +
                    "Grant it to let this run.",
            )
        }

        // (4) Path escape. Applies to every tier, because a REVERSIBLE write
        //     outside app storage is still a write the user never scoped.
        if (analysis.escapingPaths.isNotEmpty()) {
            return decide(
                definition, args, PolicyOutcome.REQUIRE_CONFIRMATION, PolicyRule.PATH_ESCAPES_APP_STORAGE,
                "${definition.name} touches ${analysis.escapingPaths.joinToString()}, " +
                    "which is outside this app's own storage. Confirm only if you meant to let it " +
                    "read or change files elsewhere on the phone.",
            )
        }

        // (5) Blast radius and rate limits, checked before any tier shortcut.
        val denial = tracker.denialReason(definition.risk)
        if (denial != null) {
            return decide(
                definition, args, PolicyOutcome.BLOCK, PolicyRule.LIMIT_EXCEEDED, denial,
            )
        }

        // (6) Bulk. Destructive bulk is blocked outright: a single mistaken
        //     "delete these 500 files" is not recoverable by confirming it.
        if (analysis.isBulk) {
            val destructive = definition.risk == ToolRisk.DESTRUCTIVE
            val tooManyForBlock = analysis.affectedCount >= config.destructiveBulkBlockThreshold
            return when {
                destructive && tooManyForBlock -> decide(
                    definition, args, PolicyOutcome.BLOCK, PolicyRule.BULK_OPERATION,
                    "${definition.name} would affect ${describeCount(analysis.affectedCount)} items at " +
                        "once. A destructive change that wide is refused rather than confirmed.",
                )

                destructive -> decide(
                    definition, args, PolicyOutcome.REQUIRE_CONFIRMATION, PolicyRule.BULK_OPERATION,
                    "${definition.name} would delete ${describeCount(analysis.affectedCount)} items. " +
                        "Confirm only if you meant to remove all of them.",
                )

                else -> decide(
                    definition, args, PolicyOutcome.REQUIRE_CONFIRMATION, PolicyRule.BULK_OPERATION,
                    "${definition.name} would affect ${describeCount(analysis.affectedCount)} items in one " +
                        "call. Confirm if that is what you want.",
                )
            }
        }

        // (7) External communication, judged on its arguments. This is the rule
        //     the product brief calls out by name: the same share to a known
        //     contact and the same share to a stranger are different risks.
        if (definition.risk == ToolRisk.EXTERNAL_COMMUNICATION) {
            val target = analysis.target
            if (target == null) {
                return decide(
                    definition, args, PolicyOutcome.REQUIRE_CONFIRMATION, PolicyRule.EXTERNAL_TO_UNKNOWN_TARGET,
                    "${definition.name} sends something outside the phone but names no recipient, so it is " +
                        "not possible to tell who receives it. Confirm only if you know where it goes.",
                )
            }
            if (!analysis.targetIsKnown) {
                return decide(
                    definition, args, PolicyOutcome.REQUIRE_CONFIRMATION, PolicyRule.EXTERNAL_TO_UNKNOWN_TARGET,
                    "${definition.name} sends to $target, which is not one of the contacts you marked as " +
                        "trusted. Check that this is who you mean before sending.",
                )
            }
            if (analysis.bodyIsBareLink) {
                return decide(
                    definition, args, PolicyOutcome.REQUIRE_CONFIRMATION, PolicyRule.MESSAGE_IS_BARE_LINK,
                    "The message ${definition.name} would send to $target is a bare link with no other text. " +
                        "Links are the easiest way to send someone somewhere harmful, so read the address " +
                        "before allowing it.",
                )
            }
            return decide(
                definition, args, PolicyOutcome.REQUIRE_CONFIRMATION, PolicyRule.RISK_TIER,
                "${definition.name} sends a message to $target on behalf of someone else. " +
                    "It cannot be unsent once it lands.",
            )
        }

        // (8) Caller rules. Restricted to *raising* restrictiveness: a rule may
        //     turn EXECUTE into anything, or tighten a confirmation into a
        //     block, but may never loosen a BLOCK or a confirmation into EXECUTE.
        //     Without that clamp, a config file becomes a way to disable the
        //     policy, and "the runtime decides" stops being true.
        val base = tierDecision(definition, args, analysis)
        for (rule in config.rules) {
            val proposed = try {
                rule.evaluate(definition, args, base)
            } catch (t: Throwable) {
                // A broken custom rule must not open the gate. Treat a throwing
                // rule as a request to block.
                return decide(
                    definition, args, PolicyOutcome.BLOCK, PolicyRule.CUSTOM,
                    "A configured policy rule failed for ${definition.name} (${t::class.java.simpleName}), " +
                        "so the call is refused rather than run unchecked.",
                )
            }
            if (proposed == null) continue
            if (Decision.restrictiveness(proposed.outcome) > Decision.restrictiveness(base.outcome)) {
                return proposed.copy(rule = PolicyRule.CUSTOM)
            }
            // A rule that tried to loosen is ignored, and the trace says so.
            return base.copy(
                rule = PolicyRule.CUSTOM,
                justification = base.justification +
                    " A configured rule asked to weaken this decision; it was ignored.",
            )
        }
        return base
    }

    /**
     * The argument names [definition]'s own JSON Schema marks required.
     *
     * Read here, once, and passed down, because this is the only place in the
     * package that holds a [ToolDefinition]. A tool that declares no `required`
     * array contributes nothing, which is why `device.battery` and its siblings
     * are unaffected: the check is about a tool that says it needs something.
     *
     * Defensive about shape rather than trusting it: `required` is an array per
     * JSON Schema and `CatalogueSchema` documents that some early tool authors
     * emitted it as an object. A `required` this function cannot read yields an
     * empty set, which disables the check for that tool — the safe direction
     * here, because the alternative (throwing from a risk evaluation) would
     * take down a step over a malformed schema.
     */
    private fun requiredArguments(definition: ToolDefinition): Set<String> {
        val required = definition.schema["required"] ?: return emptySet()
        if (required !is kotlinx.serialization.json.JsonArray) return emptySet()
        return required.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * The first privileged marker found in any argument key or scalar value, or
     * null.
     *
     * WHY keys as well as values: `settings.write{key: "enabled", value:
     * "com.evil.AccessibilityService"}` puts the capability in the value, and
     * `a11y.enable{service: "..."}` puts it in the key. Checking only values
     * would be trivially bypassed by renaming the argument.
     */
    private fun findPrivilegedMarker(args: ToolArgs, config: PolicyConfig): String? {
        if (config.privilegedArgumentMarkers.isEmpty()) return null
        fun scan(text: String): String? {
            val lowered = text.lowercase()
            return config.privilegedArgumentMarkers.firstOrNull { lowered.contains(it) }
        }
        for ((key, value) in args) {
            scan(key)?.let { return it }
            when (value) {
                is kotlinx.serialization.json.JsonPrimitive -> scan(value.content)?.let { return it }
                is kotlinx.serialization.json.JsonArray -> {
                    for (item in value) {
                        if (item is kotlinx.serialization.json.JsonPrimitive) {
                            scan(item.content)?.let { return it }
                        }
                    }
                }

                else -> Unit
            }
        }
        return null
    }

    /**
     * The risk-tier fallback.
     *
     * WHY REVERSIBLE is auto-executed but DESTRUCTIVE is not: reversible means
     * the user can undo it (create an event, write the clipboard), so the cost
     * of a wrong call is an annoyance. DESTRUCTIVE and anything unrecognised
     * are gated, and the unrecognised case is a BLOCK rather than a
     * confirmation — a tool nobody classified has not earned a dialog.
     */
    private fun tierDecision(
        definition: ToolDefinition,
        args: ToolArgs,
        analysis: ArgumentAnalysis,
    ): Decision {
        val risk = definition.risk
        val floor = tierFloor(risk)
        val base = if (risk in config.autoExecuteTiers &&
            Decision.restrictiveness(PolicyOutcome.EXECUTE) >= Decision.restrictiveness(floor)
        ) {
            decide(
                definition, args, PolicyOutcome.EXECUTE, PolicyRule.RISK_TIER,
                "${definition.name} is ${risk.name.lowercase()} and affects ${describeScope(analysis)}; " +
                    "it can be undone or read back, so it runs without asking.",
            )
        } else if (risk == ToolRisk.DESTRUCTIVE) {
            decide(
                definition, args, PolicyOutcome.REQUIRE_CONFIRMATION, PolicyRule.RISK_TIER,
                "${definition.name} cannot be undone. It affects ${describeScope(analysis)}, and if it is " +
                    "wrong there is no way back from it.",
            )
        } else {
            decide(
                definition, args, PolicyOutcome.BLOCK, PolicyRule.UNCLASSIFIED,
                "${definition.name} has risk tier $risk, which this runtime has no rule for. " +
                    "It is refused rather than guessed at.",
            )
        }
        // Clamp to the tier floor. Without this, `autoExecuteTiers =
        // ToolRisk.entries.toSet()` is a one-line config change that turns the
        // whole policy off, and a single mistyped constant becomes a data-loss
        // bug. The floor is the invariant; the config can only tighten.
        return if (Decision.restrictiveness(base.outcome) >= Decision.restrictiveness(floor)) {
            base
        } else {
            decide(
                definition, args, floor, PolicyRule.RISK_TIER,
                base.justification + " (This tier cannot be run unattended, so the configuration " +
                    "was not allowed to widen it.)",
            )
        }
    }

    /**
     * The most permissive outcome any tier may ever reach, regardless of
     * configuration. WHY this exists rather than trusting [PolicyConfig]: the
     * configuration is a value someone can edit, and this is the safety
     * property. PRIVILEGED is handled before the tier logic entirely, so the
     * floor here is only about comms and destruction.
     */
    private fun tierFloor(risk: ToolRisk): PolicyOutcome = when (risk) {
        ToolRisk.PRIVILEGED -> PolicyOutcome.BLOCK
        ToolRisk.DESTRUCTIVE -> PolicyOutcome.REQUIRE_CONFIRMATION
        ToolRisk.EXTERNAL_COMMUNICATION -> PolicyOutcome.REQUIRE_CONFIRMATION
        else -> PolicyOutcome.EXECUTE
    }

    private fun decide(
        definition: ToolDefinition,
        args: ToolArgs,
        outcome: PolicyOutcome,
        rule: PolicyRule,
        justification: String,
    ): Decision = Decision(
        outcome = outcome,
        toolName = definition.name,
        risk = definition.risk,
        rule = rule,
        justification = justification,
        arguments = args,
        definition = definition,
    )

    /**
     * A count too large to print exactly. WHY cap it: the justification is
     * rendered in a UI and logged in a trace, and "9223372036854775807 items"
     * tells a reader less than "an unbounded number of items" while being more
     * likely to break a layout.
     */
    private fun describeCount(count: Int): String = when {
        count == Int.MAX_VALUE -> "an unbounded number of"
        count > 1000 -> "over 1000"
        else -> count.toString()
    }

    /** Names what the call touches, so a dialog can say more than "it runs". */
    private fun describeScope(analysis: ArgumentAnalysis): String = when {
        analysis.escapingPaths.isNotEmpty() -> analysis.escapingPaths.joinToString()
        analysis.paths.isNotEmpty() -> analysis.paths.joinToString()
        analysis.target != null -> "a message to ${analysis.target}"
        analysis.affectedCount > 0 -> "${analysis.affectedCount} items"
        else -> "nothing outside this app"
    }

    /**
     * Records that a call actually ran. Separate from [evaluate] so a *denied*
     * call never consumes the task's budget — otherwise a model could starve
     * itself by attempting what it is not allowed to do.
     */
    fun recordExecuted(risk: ToolRisk) = tracker.recordExecuted(risk)

    /** Records that a human approved a gated call. */
    fun recordConfirmed(risk: ToolRisk) = tracker.recordConfirmed(risk)

    /** Clears per-task counters. MUST be called at the start of every task. */
    fun resetTask() = tracker.reset()

    /** The tracker, exposed for assertions and for a debug trace view. */
    val blastRadius: BlastRadiusTracker get() = tracker
}
