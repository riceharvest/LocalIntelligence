package dev.localintelligence.app.security

import dev.localintelligence.core.tool.redaction.RedactionResult
import dev.localintelligence.core.tool.redaction.SecretCategory
import dev.localintelligence.core.tool.redaction.SecretRedactor
import dev.localintelligence.core.transcript.TranscriptRedaction

/**
 * The one place the app decides what redaction is FOR, and the sentences the
 * screen shows the user about it.
 *
 * Everything mechanical lives in `:core`'s
 * [dev.localintelligence.core.tool.redaction.SecretRedactor] — a set of
 * regexes with no Android in it. This file is the part that is a product
 * decision, and it is deliberately a few constants and three paragraphs rather
 * than a policy engine. The owner ruled out a framework here, and the honest
 * reading of that decision is: the filter exists, the filter is small, and the
 * user is told exactly what it does and does not cover.
 *
 * ## THE BOUNDARY, STATED PRECISELY
 *
 * The filter is applied to a tool's return value, at the moment that value
 * becomes an observation — that is, in
 * [dev.localintelligence.core.tool.redaction.RedactingToolRegistry], which
 * wraps the tool registry so the redaction happens before
 * `ToolResult.observation` is handed to the agent loop. That single point is
 * the whole of the coverage, and the reasoning for it is in that class.
 *
 * Two boundaries were considered and rejected:
 *
 *  - **After the model has answered.** The trace and the chat transcript are
 *    downstream of the model, so filtering there protects the display and not
 *    the model. Worse, it produces a record that disagrees with what the model
 *    actually received: the trace would read `[jwt redacted]` for a token the
 *    model had in full a moment earlier. A log that misdescribes the run is
 *    worse than an unfiltered one, because it is wrong in the direction that
 *    looks like safety.
 *  - **On the model's own output.** Rewriting generated text mid-decode
 *    corrupts the answer and can break grammar-constrained decoding outright.
 *    That is a strictly worse failure than showing a secret the model was
 *    legitimately given.
 *
 * ## WHAT IS NOT COVERED, AND SAYS SO
 *
 * The screen this feeds is required to be honest, and the list of exclusions
 * is the part of it that matters. A user who knows the limits uses the feature
 * correctly; a user told "your secrets are protected" pastes a recovery phrase
 * and does not think again. The exclusions are enumerated in
 * [dev.localintelligence.app.ui.trace.RedactionScreen] rather than here, so
 * there is one copy of the list and it lives next to the thing it describes.
 *
 * ## THE THREAT MODEL, IN ONE SENTENCE
 *
 * There is no network egress and the model runs in this process, so this is
 * not a remote-disclosure bug — it is a local one. The harm is a secret
 * sitting in a Room row and in a context window on a phone whose entire pitch
 * is that nothing leaves it, and the harm compounds every time the transcript
 * is read back. The filter is sized to that, not to an attacker on the
 * network.
 */
object RedactionPolicy {

    /**
     * Whether the filter is applied at all.
     *
     * A constant rather than a setting, because a settings toggle nobody can
     * find is worse than no toggle: it implies a control that does not exist.
     * The screen says the filter is always on, which is true, instead of
     * offering a switch that only produces a false sense of choice. With the
     * filter off, a pasted recovery phrase reaches the model with nothing on
     * screen saying so, and there is no version of that which is a feature.
     *
     * A named constant so the wiring has one obvious thing to change if that
     * is ever revisited, and so the screen reads one source instead of
     * hardcoding "on".
     */
    const val ENABLED: Boolean = true

    /**
     * What the filter does, in the user's terms, for the screen's summary.
     *
     * "The assistant reads" is chosen over "a tool reads" because the user
     * does not know what a tool is and does not need to. The honest part is
     * the second sentence, which names what the filter does NOT touch.
     */
    const val SUMMARY: String =
        "When the assistant reads something you did not type into the chat — the " +
            "clipboard, a file you hand it, a page it fetches — text that looks like a " +
            "secret is replaced with a label first. It does this before the text reaches " +
            "the model, so the original is not in the model's context. It does not run on " +
            "what the model writes back."

    /**
     * The chat's own text, which this filter also covers.
     *
     * ## WHY THIS IS A SEPARATE CONSTANT RATHER THAN A PARAGRAPH ON [SUMMARY]
     *
     * Because it is a different boundary with a different cost, and merging the
     * two is how the screen came to be wrong in the first place. Reading a
     * clipboard is something the app does to the user; saving a chat turn is
     * something the app does FOR the user's own words. The consequences differ
     * too: a false positive on an observation corrupts what the model reasons
     * about, whereas a false positive on a saved turn shows the user their own
     * sentence with a hole in it - annoying, visible, and recoverable by
     * retyping. So they get their own heading and their own limits, and the
     * exclusions are stated separately rather than averaged together.
     *
     * The wording is read from [TranscriptRedaction] rather than restated, so
     * this screen cannot describe a policy the runtime does not implement. That
     * is the same reason the category list is read from the redactor.
     */
    val TRANSCRIPT_SUMMARY: String get() = TranscriptRedaction.TRANSCRIPT_SUMMARY

    /** The cases that get through, for the chat-text filter. */
    val TRANSCRIPT_LIMITS: String get() = TranscriptRedaction.TRANSCRIPT_LIMITS

    /**
     * Redacts one tool observation, or returns it unchanged when [ENABLED] is
     * false.
     *
     * Not on the live path — [dev.localintelligence.core.tool.redaction.RedactingToolRegistry]
     * is the thing that does the work, because the seam has to be the tool
     * boundary and that lives in `:core`. This exists so the app has one
     * readable statement of whether the filter is on, which the screen shows.
     */
    fun redact(text: String): RedactionResult =
        if (ENABLED) SecretRedactor.redact(text) else RedactionResult(text, emptySet(), 0)

    /** The categories actually implemented, read from the redactor. */
    val categories: List<SecretCategory> get() = SecretRedactor.detectedCategories
}

/**
 * How a redaction is announced to the model, in one sentence.
 *
 * This matters more than it looks. A silent replacement hands the model a
 * string with a hole in it and no explanation, and the model will confidently
 * reason about the hole — "the file has two lines" when one is now
 * `[api key redacted]`, or worse, it treats the label as literal content and
 * paraphrases it back to the user as if it were the file. Naming the
 * substitution lets the model say "this part was withheld" instead of inventing
 * a reading.
 *
 * Kept as a function rather than baked into [RedactionResult] because the
 * wording is for the model's benefit, and what the model needs to hear is a
 * prompt concern rather than a redaction one.
 */
fun RedactionResult.modelNotice(): String = when {
    !changed -> ""
    else -> {
        val names = categories
            .joinToString(", ") { it.title.lowercase() }
            .ifEmpty { "sensitive values" }
        val plural = if (count == 1) "1 value was" else "$count values were"
        "$plural replaced with a label because they matched $names. The original " +
            "text is not available to you. Say that it was withheld rather than " +
            "guessing at what it was."
    }
}
