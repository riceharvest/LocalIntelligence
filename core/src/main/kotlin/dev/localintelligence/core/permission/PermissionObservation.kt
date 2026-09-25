package dev.localintelligence.core.permission

/**
 * Turns a platform denial into text a 1B model can act on.
 *
 * WHY this exists at all: the shortest path from "the system refused" to the
 * prompt is `throwable.toString()`, and that is exactly what
 * docs/tool-contract.md forbids. `java.lang.SecurityException: Permission Denial:
 * ... requires android.permission.READ_CALENDAR` costs a model more to read than
 * the whole tool description, teaches it nothing actionable, and leaks the
 * platform's internals into a context window we budget in thousands of tokens.
 *
 * Every observation this object produces is:
 *  - bounded (see [BUDGET_CHARS]),
 *  - free of class names, package names, stack frames and numeric error codes,
 *  - a statement of what cannot happen and what the user would have to do.
 *
 * The sanitiser is the last line of defence, not a parser: platform messages are
 * free text, so rather than trying to understand them we reject anything that
 * looks like a code or a type and keep the human words.
 */
object PermissionObservation {

    /**
     * Ceiling for a permission observation, in characters.
     *
     * WHY 320 and not the 2048 general budget: this string is the entire result
     * of a failed call, and a failure is a thing a confused model will re-read
     * on every subsequent turn. It has to fit in the reader's attention, not
     * just in the budget.
     */
    const val BUDGET_CHARS = 320

    /**
     * Matches a fully-qualified Java/Kotlin type such as
     * `java.lang.SecurityException` or `android.os.ParcelableException`.
     * Lowercase-first segments only, so ordinary prose ("e.g. contacts") is safe.
     */
    private val QUALIFIED_TYPE = Regex("""\b[a-z][a-zA-Z0-9_]*(\.[a-z][a-zA-Z0-9_]*)*\.[A-Z][A-Za-z0-9_]*""")

    /** Matches a standalone stack frame line: `at com.foo.Bar.baz(Bar.kt:42)`. */
    private val STACK_FRAME = Regex("""\bat\s+[\w.$]+\([^)]*\)""")

    /** Bare capitalised exception-ish words (`SecurityException`, `NullPointerException`). */
    private val EXCEPTION_WORD = Regex("""\b[A-Z][A-Za-z0-9_]*(Exception|Error|Throwable)\b""")

    /**
     * Platform error codes, in the three shapes they actually appear in:
     * signed (`-13`, `-1`), hex (`0xDEAD`), and long unsigned runs (`403`).
     *
     * WHY signed numbers count on their own: Android denial messages carry
     * codes like `-13` and `-1` that are only one or two digits, so a "3+ digits
     * means a code" rule leaves them in the text. A leading minus in this
     * context is never prose — it is a code or a negative count the model has
     * no use for. Short unsigned numbers (`5`, `42`) are deliberately kept, so
     * an ordinary number in a sentence survives.
     */
    private val RAW_CODE = Regex("""(?<![\w.])(?:-[0-9]+|0[xX][0-9a-fA-F]+|[0-9]{3,})(?![\w.])""")

    /**
     * Platform content URIs (`content://com.android.calendar/...`).
     *
     * WHY strip them: they are long, meaningless to a model, and the only part
     * of one worth keeping is a provider name, which the capability label
     * already carries.
     */
    private val CONTENT_URI = Regex("""\b[a-z][a-z0-9+.-]*://\S*""")

    /** Control characters other than the whitespace collapses anyway. */
    private val CONTROL_CHAR = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")

    private val WHITESPACE = Regex("""\s+""")

    /** Appended when sanitising had to cut something, so the model knows. */
    private const val REDACTION_MARK = "…"

    /**
     * Strips anything from [raw] that a model should not read.
     *
     * Deterministic and total: always returns printable text, never throws, and
     * the result is guaranteed to satisfy [containsNoPlatformNoise].
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return ""
        var out = raw
        out = STACK_FRAME.replace(out, " ")
        out = CONTENT_URI.replace(out, " ")
        out = RAW_CODE.replace(out, " ")
        out = QUALIFIED_TYPE.replace(out, " ")
        out = EXCEPTION_WORD.replace(out, " ")
        out = WHITESPACE.replace(out, " ").trim()
        // Collapse the double spaces left behind by removals, then tidy stray
        // punctuation that now dangles at the end of the sentence.
        out = out.replace(Regex("""\s+([.,;:])"""), "$1").trim()
        // Last: anything still unprintable goes, so a hostile or corrupt input
        // cannot put a control character in front of a model.
        out = CONTROL_CHAR.replace(out, " ")
        return out.replace(WHITESPACE, " ").trim()
    }

    /**
     * Whether [text] is free of the four classes of platform noise the
     * observation rule forbids.
     *
     * Exposed so the invariant is testable directly rather than inferred from
     * a string comparison. A future editor who adds a fifth leak has to extend
     * this, and this test fails when they forget.
     */
    fun containsNoPlatformNoise(text: String): Boolean =
        !STACK_FRAME.containsMatchIn(text) &&
            !QUALIFIED_TYPE.containsMatchIn(text) &&
            !EXCEPTION_WORD.containsMatchIn(text) &&
            !RAW_CODE.containsMatchIn(text)

    /**
     * Builds the sentence the model sees when a tool is blocked.
     *
     * [label] is the tool's human-facing capability ("your calendar"), not the
     * tool name and not the permission id: the model has to turn this sentence
     * into something it says to a human, and `READ_CALENDAR` is not that.
     */
    fun forState(
        state: PermissionState,
        label: String,
    ): String {
        val what = label.ifBlank { "that" }
        val text = when (state) {
            PermissionState.GRANTED ->
                "The permission for $what is granted."

            PermissionState.DENIED ->
                "Cannot use $what: permission not granted. " +
                    "Ask the user once to allow it, then try again. If they decline, " +
                    "tell them what you could not do and stop."

            PermissionState.DENIED_PERMANENTLY ->
                "Cannot use $what: permission permanently denied. " +
                    "The app cannot ask again. Tell the user to enable it in Android " +
                    "Settings, then do not retry this in this conversation."

            PermissionState.NOT_APPLICABLE ->
                "Cannot use $what: this permission does not apply on this device or app. " +
                    "Tell the user it is unavailable and do not retry."
        }
        return clamp(sanitize(text))
    }

    /**
     * Forces [text] under [BUDGET_CHARS], on a word boundary.
     *
     * A cut mid-word produces a token the model will try to use, so the cut
     * walks back to the last space when there is one.
     */
    fun clamp(text: String): String {
        if (text.length <= BUDGET_CHARS) return text
        val hardCut = BUDGET_CHARS - REDACTION_MARK.length
        if (hardCut <= 0) return REDACTION_MARK
        val head = text.take(hardCut)
        val lastSpace = head.lastIndexOf(' ')
        val body = if (lastSpace > hardCut / 2) head.take(lastSpace) else head
        return body.trimEnd(' ', ',', ';', ':') + REDACTION_MARK
    }
}
