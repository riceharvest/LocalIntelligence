package dev.localintelligence.core.report

// ===========================================================================
// A user's report about one assistant reply, as the text the developer receives.
//
// WHY A PLAIN TEXT FORMAT: the report goes to a Google Form with one paragraph
// question, so the whole report is one field. Plain lines with fixed labels
// read the same in the form's response sheet as they do here, and need no
// JSON or escaping on the other end.
//
// WHY THE LIST IS FIXED: Google Play's AI-generated content policy asks for a
// way to flag offensive content. Four reasons cover what a user can see wrong
// with a reply; "Other" takes the rest, and the note carries the detail.
// ===========================================================================

/** Why the user is reporting a reply. The label is what the report and the sheet show. */
enum class ReplyReportReason(val label: String) {
    OFFENSIVE("Offensive"),
    HARMFUL("Harmful or dangerous"),
    WRONG("Wrong or misleading"),
    OTHER("Other"),
}

/**
 * One report. [model] is the model or remote provider that wrote the reply, as the
 * app names it. [reply] is the reply text, or null when the user unticked "Include
 * the reply text"; a null reply is written as "(not included)".
 *
 * Nothing else goes in: no user messages, no other replies, no memory, no tool output
 * and no device identifiers. The caller builds this from the one reply it was given.
 */
data class ReplyReport(
    val reason: ReplyReportReason,
    val note: String,
    val model: String,
    val appVersion: String,
    val distribution: String,
    val reply: String?,
) {
    /** The report as the form receives it. */
    fun format(): String = buildString {
        appendLine("LocalIntelligence reply report")
        appendLine("Reason: ${reason.label}")
        appendLine("Note: ${note.trim().ifEmpty { "-" }}")
        appendLine("Model: ${model.ifBlank { "unknown" }}")
        appendLine("App: $appVersion ($distribution)")
        appendLine("Reply:")
        append(reply?.let(::capReply) ?: "(not included)")
    }

    private fun capReply(text: String): String =
        if (text.length <= MAX_REPLY_CHARS) text else text.take(MAX_REPLY_CHARS - 1) + "…"

    companion object {
        /** Google Forms limits a paragraph answer. A reply past this is cut with an ellipsis. */
        const val MAX_REPLY_CHARS: Int = 4_000
    }
}
