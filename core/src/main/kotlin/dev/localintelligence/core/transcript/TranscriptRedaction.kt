package dev.localintelligence.core.transcript

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.tool.redaction.SecretCategory
import dev.localintelligence.core.tool.redaction.SecretRedactor

/**
 * Decides what the DURABLE transcript keeps of a turn, and says out loud what
 * it did.
 *
 * ## THE GAP THIS CLOSES
 *
 * Verified on `752215b` by running the real production classes, not by reading
 * them. A user turn containing a credential reached SQLite verbatim and came
 * back verbatim:
 *
 * ```
 * [2] PERSISTED ROW -> messages.text  (Room INSERT)
 *     log in to my router, the password is s3cr3t-router-password-9f2a ...
 *     secret present in the durable row? true
 * [4] WHAT THE MODEL RECEIVES ON THE FOLLOWING TURN
 *     User: log in to my router, the password is s3cr3t-router-password-9f2a ...
 *     >>> SECRET RE-SENT TO THE MODEL ON TURN 2? true
 * ```
 *
 * `MemoryWritePolicy` already refuses to PROMOTE such a turn to durable memory,
 * and `android:allowBackup="false"` already stops the database leaving the
 * device. Neither touches the `messages` table. The write path is
 * `AppContainer.persistConversation` -> `SessionStore.appendMessage` ->
 * `MessageMapping.toColumns` -> `messages.text`, and nothing on it redacts.
 * `RedactingToolRegistry` decorates the TOOL boundary; a user turn does not go
 * through it.
 *
 * ## WHY REDACT AT THE WRITE, NOT AT RENDER
 *
 * Four options were on the table and this is the one chosen:
 *
 *  - **Redact at render.** Rejected: the secret is still on disk, still in the
 *    database file, still restored on the next cold start. It protects a
 *    screenshot and nothing else, while the transcript keeps re-sending the
 *    secret to the model on every subsequent turn. That is the actual harm.
 *  - **Redact at persist.** Chosen. One chokepoint, and the durable artefact is
 *    the thing that was leaking.
 *  - **A setting.** Rejected, and not for lack of surface area. `RedactionPolicy`
 *    already made this call for the tool filter with the reasoning that a
 *    toggle nobody finds is worse than no toggle; a second toggle for the
 *    transcript is the same decision again, and the default-off state is the
 *    leaking one. There is no version of "you can turn this off" that is a
 *    feature for a plaintext credential on disk.
 *  - **Drop the whole turn.** Rejected: it destroys the conversation around the
 *    secret, and it is indistinguishable, to the user, from losing their own
 *    message. A redacted span keeps the turn readable and keeps the sentence
 *    the model needs.
 *
 * ## WHAT THE LIVE TURN STILL GETS
 *
 * The redaction is applied to the COPY that becomes the row. The in-memory
 * `ChatMessage.User` the model is answering right now is untouched, so the
 * assistant still gets a correct answer to "log in with this password" on the
 * turn it was asked. The user is not fighting a filter mid-task; the cost is
 * paid by the NEXT turn, and only if the model needs to recall the value.
 *
 * ## WHY THE STATE IS A TRI-STATE AND NOT A COUNT
 *
 * This is the ambiguity the observability work flagged, and it is real. A
 * `RedactionResult` with `count == 0` is ambiguous between "there was nothing
 * here" and "there was something here and it was already removed upstream".
 * A record can arrive ALREADY redacted - a tool observation has been through
 * [dev.localintelligence.core.tool.redaction.RedactingToolRegistry], or a row
 * was written by a build that had the filter - and re-filtering it correctly
 * reports zero.
 *
 * So `count == 0` is never allowed to mean "clean". [RedactionState] separates
 * the three cases by asking a second question the count cannot answer: does the
 * text already carry a marker? [RedactionState.ALREADY_REDACTED] is that case,
 * and it is a success, not a failure. Reporting it as [RedactionState.CLEAN]
 * would be the exact false-clean this project keeps paying for, and it would
 * make a screen that says "nothing was found here" true about a record that had
 * a secret in it one commit ago.
 *
 * ## WHAT THIS IS NOT
 *
 * A guarantee, and not a security boundary. It is a set of regexes. The same
 * limits [SecretRedactor] documents apply in full: a secret with no recognisable
 * shape - a password written out as prose with no credential word near it, a
 * photo, a value this build does not have a pattern for - passes through. The
 * screen says so in the same words.
 */
object TranscriptRedaction {

    /**
     * What happened to one span on its way to disk.
     *
     * THREE states, not a boolean and not a count. See the class KDoc: the
     * difference between [CLEAN] and [ALREADY_REDACTED] is the difference
     * between "no secret was here" and "a secret was here and is already
     * gone", and only the second is knowable by looking for a marker.
     */
    enum class RedactionState {
        /**
         * Nothing was removed, and no marker is present. The only state in
         * which "nothing was found" is a safe thing to say.
         */
        CLEAN,

        /**
         * This call removed at least one span. The durable text is the
         * redacted one and the original existed a moment ago.
         */
        REDACTED,

        /**
         * Nothing was removed by THIS call, but the text already carried a
         * redaction marker. A secret was here, and something upstream already
         * took it. Reporting this as [CLEAN] is the false-clean failure.
         */
        ALREADY_REDACTED,
        ;

        /** True when a secret was present, whether this call or an earlier one. */
        val withheld: Boolean get() = this != CLEAN
    }

    /**
     * The outcome for one span: the text to persist, what happened, and which
     * categories fired.
     *
     * Carries no secret material, by construction - same contract as
     * [dev.localintelligence.core.tool.redaction.RedactionResult], and for the
     * same reason: this object is returned to a UI and to a log line.
     */
    data class Outcome(
        /** The text to write. Redacted when [state] is not [RedactionState.CLEAN]. */
        val text: String,
        val state: RedactionState,
        /** Categories that fired IN THIS CALL. Empty for [RedactionState.ALREADY_REDACTED]. */
        val categories: Set<SecretCategory>,
        /** How many spans this call replaced. */
        val count: Int,
    ) {
        /**
         * Whether the text changed on the way to disk.
         *
         * FALSE for [RedactionState.ALREADY_REDACTED] - and that is correct:
         * the row is byte-identical to what it would have been, because the
         * secret was removed before this code ever saw it. Callers that need
         * "was a secret involved" must ask [RedactionState.withheld], not this.
         */
        val changed: Boolean get() = state == RedactionState.REDACTED
    }

    /**
     * Redacts one span for persistence.
     *
     * Total, never throws, and the state is always meaningful. The order is
     * load-bearing and is the whole of the tri-state: the redactor runs FIRST
     * (it is a fixed point, so a marker it would have written is stable), and
     * the marker check runs on the INPUT so that "was this already redacted
     * before I got here" is answered about the text as it arrived rather than
     * about the text this call produced.
     *
     * Checking the marker on the output instead would collapse the two
     * non-clean states into one: a call that redacts something always produces
     * a marker, so [RedactionState.ALREADY_REDACTED] would become
     * unreachable and every already-redacted record would read as a fresh
     * redaction - which overstates the work done and, worse, cannot tell a
     * reader whether anything was ever in that text.
     */
    fun redactForPersistence(text: String): Outcome {
        if (text.isEmpty()) return Outcome(text, RedactionState.CLEAN, emptySet(), 0)

        val arrivedRedacted = SecretRedactor.hasMarker(text)
        val result = SecretRedactor.redact(text)
        val state = when {
            result.count > 0 -> RedactionState.REDACTED
            arrivedRedacted -> RedactionState.ALREADY_REDACTED
            else -> RedactionState.CLEAN
        }
        return Outcome(result.text, state, result.categories, result.count)
    }

    /** The whole message, for persistence. See [redactChatMessage]. */
    fun redactForPersistence(message: ChatMessage): Outcome = redactForPersistence(textOf(message))

    /**
     * The text a [ChatMessage] persists, which is NOT the same as
     * `message.modelFacing()`.
     *
     * A tool observation's [ChatMessage.modelFacing] is the UNTRUSTED FENCE -
     * the model-facing rendering, which is correct for a prompt and wrong for a
     * database column. The row stores the bare observation, and this returns
     * the bare observation's text. Conflating the two would write the fence
     * markers into the transcript and then strip them on the way back out, so
     * the stored value and the model-facing value would disagree about whether
     * the content was quoted.
     */
    private fun textOf(message: ChatMessage): String = when (message) {
        is ChatMessage.System -> message.text
        is ChatMessage.User -> message.text
        is ChatMessage.Assistant -> message.text
        is ChatMessage.ToolObservation -> message.observation
    }

    /**
     * The message to persist, with its text redacted.
     *
     * Rebuilds rather than mutates: [ChatMessage] is a sealed hierarchy of
     * data classes, the loop holds references to the originals, and a mutation
     * here would redact the LIVE turn out from under the run that is still
     * answering it. The copy is what reaches the database; the original stays
     * intact in memory for the remainder of the run.
     *
     * That separation is the whole reason the live turn still works. It is also
     * why this is a function over the message and not a setter on the session.
     */
    fun redactChatMessage(message: ChatMessage): Pair<ChatMessage, Outcome> {
        val outcome = redactForPersistence(textOf(message))
        if (outcome.state == RedactionState.CLEAN) return message to outcome
        val redacted = when (message) {
            is ChatMessage.System -> ChatMessage.System(outcome.text)
            is ChatMessage.User -> ChatMessage.User(outcome.text)
            is ChatMessage.Assistant -> ChatMessage.Assistant(outcome.text)
            // origin is carried across, not defaulted: a redaction changes what
            // the text says, not who wrote it, and dropping the flag here would
            // re-open the injection fence this class sits next to.
            is ChatMessage.ToolObservation -> message.copy(observation = outcome.text)
        }
        return redacted to outcome
    }

    /**
     * One sentence for the MODEL, or "" when there is nothing to say.
     *
     * A marker in the transcript is a hole in the user's own words. A model
     * that has not been told about it will either guess at the value or treat
     * `[redacted]` as literal content and paraphrase it back. Both are wrong,
     * and the second is worse because it looks like an answer.
     *
     * Says ALREADY_WITHHELD for the [RedactionState.ALREADY_REDACTED] case
     * rather than "nothing was removed": from the model's position nothing was
     * removed THIS turn, but a value is still absent, and the sentence it needs
     * is the same one. Differing the wording by state would tell the model
     * something about the runtime's internals that is none of its business.
     */
    fun modelNotice(state: RedactionState): String = when (state) {
        RedactionState.CLEAN -> ""
        RedactionState.REDACTED, RedactionState.ALREADY_REDACTED -> ALREADY_WITHHELD
    }

    /** The runtime's own wording. See [modelNotice] for why the states share it. */
    const val ALREADY_WITHHELD: String =
        "A value in an earlier turn was withheld before it was saved. The marker " +
            "in that turn is not the value and is not a placeholder you should fill " +
            "in. If the user refers back to it, say that it was withheld and ask " +
            "them to provide it again."

    /**
     * What the transcript itself is, for the user-facing screen.
     *
     * Written to be true about the parts that are uncomfortable, because the
     * screen that overclaims is worse than no screen: this says the live turn
     * still works, that the redaction is per-value rather than per-message, and
     * that the limits are the redactor's.
     */
    const val TRANSCRIPT_SUMMARY: String =
        "What you type into the chat is checked before the conversation is " +
            "saved. If a value looks like a password, an API key, a token or a " +
            "one-time code, it is replaced with a label in the stored " +
            "conversation and the label is what you see when you come back to " +
            "it later. The message you just sent was answered using what you " +
            "actually typed — the assistant had the real value for that turn. " +
            "Only the saved copy is filtered, and only the parts that match: " +
            "the rest of your message is kept exactly as you wrote it. If you " +
            "ask the assistant to repeat a value back in a later turn, it will " +
            "tell you it was withheld rather than inventing one."

    /**
     * The exclusions, in the user's terms.
     *
     * This is the part of the screen that matters most. A user who believes the
     * filter is complete pastes a seed phrase and does not think again, so every
     * clause here names a case that gets through.
     */
    const val TRANSCRIPT_LIMITS: String =
        "It does not cover:\n" +
            "\n" +
            "• A secret with no recognisable shape. A password written out as " +
            "ordinary words, a photo of a password, or a format this build has " +
            "no pattern for passes through untouched.\n" +
            "\n" +
            "• A one-time code on its own. Codes are only caught next to a word " +
            "like \"code\" or \"OTP\", because a bare six-digit number is also a " +
            "price, a timestamp and a line of a diff. Copy a code to the " +
            "clipboard by itself and paste it, and it will not be filtered.\n" +
            "\n" +
            "• What the assistant writes back. If the model repeats a secret in " +
            "its answer, that answer is saved as the model produced it.\n" +
            "\n" +
            "• Anything already saved. Conversations recorded by an earlier " +
            "version of the app are not rewritten, and there is no cleanup pass " +
            "over your existing history. A secret saved before this version " +
            "stays where it is.\n" +
            "\n" +
            "• A value that was already there. When a saved turn is read back " +
            "and found to carry a label, that means a secret was in it and has " +
            "already been removed — it does not mean the turn was clean."
}
