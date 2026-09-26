package dev.localintelligence.core.tool.redaction

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolRegistry
import dev.localintelligence.core.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A [ToolRegistry] that redacts what its tools hand back.
 *
 * ## WHERE THIS IS WIRED, AND WHY IT IS NOT WIRED YET
 *
 * The composition root is `AppContainer.tools`, one lazy property:
 *
 * ```
 * val tools: ToolRegistry by lazy { SimpleToolRegistry(androidTools(context)) }
 * ```
 *
 * and it must become
 *
 * ```
 * val tools: ToolRegistry by lazy {
 *     RedactingToolRegistry(SimpleToolRegistry(androidTools(context)))
 * }
 * ```
 *
 * That one line is NOT in this change-set, because `AppContainer.kt` is owned
 * by a different workstream and editing it here would collide with it. It is
 * written out in full rather than left in a comment somewhere, and it is
 * repeated in the pull request body, because a security filter that is written,
 * tested and never installed is worse than no filter at all: it reads as
 * protection in the code review and provides none in the running app.
 *
 * WHY THE REGISTRY PROPERTY AND NOT A WRAPPER AROUND THE CONTROLLER: the
 * controller is built per run by `AppContainer.newController`, and wrapping
 * there would mean the redaction could be bypassed by any future path that
 * builds a registry another way. The registry is the single place every tool
 * is looked up — the selector, the validator and the loop all read it — so
 * decorating it covers the run regardless of who built the controller.
 *
 * ## WHY THE REGISTRY AND NOT THE TOOL
 *
 * The thing that needs protecting is the boundary where bytes the user never
 * typed become text the model reads. Every one of those bytes arrives through
 * exactly one door — `AgentTool.execute` returning a [ToolResult]
 * whose `observation` is the only text the loop feeds back — so decorating the
 * registry is decorating every door at once.
 *
 * The alternative, editing each of the reading tools (clipboard, files, web)
 * to call [SecretRedactor] itself, is worse in three specific ways:
 *
 *  1. **It does not scale.** A tool added next month ships unredacted by
 *     omission, which is the same failure mode [ToolRegistry]
 * already had to solve once for the catalogue: a check that lives in each
 *     implementation is a check the next implementation quietly drops.
 *  2. **It puts a security decision inside a tool that has a different job.**
 *     `ClipboardReadTool` is already carrying the focus rule, MIME
 *     discrimination and a 100 KB cap. Asking it to also decide what counts as
 *     a secret is how a tool's observation contract gets muddy.
 *  3. **It would have to be done in `:android`.** The reading tools live there.
 *     A JVM-pure decorator keeps the policy testable on a plain JVM and keeps
 *     `:core` free of `android.*`, which is a hard invariant of this module.
 *
 * ## WHAT IT REDACTS
 *
 * [ToolResult.observation], and every string inside [ToolResult.data]. The
 * second is not belt-and-braces: `data` is structured and the model does not
 * see it, but it is returned to the caller, and a caller that logs or persists
 * it would store the secret that the observation no longer holds.
 *
 * ## WHAT IT DELIBERATELY DOES NOT TOUCH
 *
 * [AgentTool.definition]. The definition is schema, description and tags — the
 * tool's own name for itself. It is authored by this repository, contains no
 * user data, and is what makes the tool callable at all. Redacting it would
 * break the grammar-constrained decode rather than protect anything.
 *
 * Also untouched: anything the model itself generated. Redacting generated
 * tokens mid-decode corrupts the answer and can break constrained decoding,
 * which is strictly worse than showing a secret the model was handed.
 *
 * ## BEHAVIOUR ON REDACTION
 *
 * A redacted observation is still a successful observation. The result is not
 * failed, not retried, and not reported as an error: the tool genuinely ran
 * and genuinely returned something, and the model is told what came back
 * minus the secret. A filter that turned a working tool call into a failure
 * would make the model retry a call that was fine, which burns a step budget
 * and teaches it that files are broken.
 *
 * Cancellation is not involved anywhere in here and is not caught: this
 * decorator is synchronous and total, and it never suppresses an exception.
 */
class RedactingToolRegistry(
    private val delegate: ToolRegistry,
) : ToolRegistry {

    override fun all(): List<AgentTool> = delegate.all().map(::wrap)

    override fun byName(name: String): AgentTool? = delegate.byName(name)?.let(::wrap)

    /**
     * Wraps one tool.
     *
     * The wrapper is created per call rather than cached in a map. [all] is
     * called once per step by the selector and the list is a couple of dozen
     * entries; a cache would be state on a phone for no measurable saving, and
     * it would be a second thing to invalidate.
     */
    private fun wrap(tool: AgentTool): AgentTool = RedactingTool(tool)
}

/** One [AgentTool] with its result redacted on the way out. */
private class RedactingTool(private val delegate: AgentTool) : AgentTool {

    override val definition get() = delegate.definition

    override suspend fun execute(
        args: ToolArgs,
        context: ToolContext,
    ): ToolResult {
        val result = delegate.execute(args, context)

        // No early return on an unchanged observation: `data` may still hold a
        // string the observation does not, and the two are redacted
        // independently.
        val observation = SecretRedactor.redactToText(result.observation)
        val data = result.data?.let(::redactArgs)
        if (observation === result.observation && data === result.data) return result

        // The copy, not a mutation: ToolResult is a data class handed to the
        // loop, and rebuilding it here means every consumer — the session, the
        // trace, the UI — sees the same redacted string. There is no path that
        // gets the original.
        return result.copy(observation = observation, data = data)
    }
}

/**
 * Redacts every string in a JSON argument object, recursively.
 *
 * `ToolArgs` is a typealias for `JsonObject`, so this is really "redact an
 * object" — the shape is known at the entry point and only the VALUES vary,
 * which is why the recursion below is over [JsonElement].
 */
private fun redactArgs(args: ToolArgs): ToolArgs = JsonObject(
    args.mapValues { (_, value) -> redactElement(value, depth = 1) },
)

/**
 * Recursion is by depth rather than unbounded: tool arguments are produced by
 * a grammar-constrained decode and are structurally shallow, but a defensive
 * bound costs one line and removes the possibility of a stack overflow on a
 * path that runs inside an agent loop.
 */
private fun redactElement(element: JsonElement, depth: Int): JsonElement {
    if (depth > MAX_JSON_DEPTH) return element
    return when (element) {
        is JsonPrimitive ->
            if (element.isString) {
                val original = element.content
                SecretRedactor.redactToText(original).let {
                    if (it === original) element else JsonPrimitive(it)
                }
            } else {
                // A number or a boolean. These are counts and flags —
                // `text_chars`, `mime_count`. Redacting a count would be
                // inventing a lie about how much came back, and the shape
                // rules would not match them anyway.
                element
            }

        is JsonObject -> JsonObject(
            element.mapValues { (_, value) -> redactElement(value, depth + 1) },
        )

        is JsonArray -> JsonArray(element.map { redactElement(it, depth + 1) })
    }
}

/** Bound on [redactElement] recursion. */
private const val MAX_JSON_DEPTH = 12
