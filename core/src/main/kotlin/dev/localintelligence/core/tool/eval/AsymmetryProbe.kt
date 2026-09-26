package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.context.DefaultContextBuilder
import dev.localintelligence.core.context.SystemPrompts
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GrammarBuilder

/**
 * Proves, mechanically, the asymmetry the whole multi-turn harness rests on.
 *
 * ## WHY THIS IS A SEPARATE FILE AND NOT A PARAGRAPH OF KDoc
 *
 * `MultiTurnHarness` reports a recall number, and a recall number is only
 * interesting because of WHY it is low. The stated reason is that the model is
 * shown the conversation while the selector is not. That claim is currently
 * supported by reading two source files and believing the reader agrees with
 * the reading — which is precisely the class of claim that made the previous
 * eval numbers unfalsifiable.
 *
 * So this file turns the claim into a check that can fail:
 *
 *  1. build a REAL [Session] through its public API, holding a real
 *     conversation;
 *  2. render what the MODEL sees with the REAL [DefaultContextBuilder];
 *  3. ask the session what the SELECTOR sees, with the REAL
 *     [Session.currentKeywords];
 *  4. assert the subject word appears in (2) and not in (3).
 *
 * If someone "fixes" the asymmetry — by widening `currentKeywords`, or by
 * passing history to the selector — this stops reporting a gap, and the gap
 * section of the recall report should be read as a bug in the harness rather
 * than a finding about the product. That is the correct direction for it to
 * break: the measurement is what has to change, because the product got better.
 *
 * ## WHAT IT DELIBERATELY DOES NOT DO
 *
 * It does not fix anything, and it does not assert that the asymmetry is a bug.
 * The observation half of the exclusion is a deliberate, tested security
 * boundary (`SessionKeywordTrustTest`). The USER-TURN half has no such defence
 * and this file is the evidence that it is a product gap rather than a
 * deliberate trade. Both facts are reported; neither is resolved here.
 */
class AsymmetryProbe(
    private val tools: List<dev.localintelligence.core.tool.AgentTool> = AndroidToolSnapshot.tools,
) {

    /** What one side of the conversation can see. */
    data class View(
        val label: String,
        val text: String,
        val containsSubject: Boolean,
    )

    /**
     * One probe: a subject word, and whether each side of the loop can see it.
     *
     * @param subject the word that identifies the tool, e.g. "battery". Chosen
     *   by the caller because only the caller knows what the scenario is about.
     */
    data class Result(
        val name: String,
        val subject: String,
        val modelSees: Boolean,
        val selectorSees: Boolean,
    ) {
        /** The gap: the model can act on it, the grammar cannot offer it. */
        val asymmetric: Boolean get() = modelSees && !selectorSees
    }

    /**
     * Runs every probe and returns the results.
     *
     * A conversation is built per probe rather than shared, so one scenario's
     * turns cannot make another's subject visible — which would be
     * manufacturing the very finding this is meant to test.
     */
    fun run(): List<Result> = PROBES.map { probe -> probe(probe) }

    private fun probe(probe: Probe): Result {
        val session = Session()
        val history = ArrayList<ChatMessage>()

        // Build the conversation through the PUBLIC session API only: `start`
        // is how a run appends a task, `appendToolObservation` is how a result
        // enters, `appendAssistant` is how a reply enters. Nothing reaches past
        // them, so if the production shape changes this breaks rather than
        // quietly testing something the product does not do.
        for (turn in probe.turns) {
            session.start(turn.utterance)
            history.add(ChatMessage.User(turn.utterance))
            turn.calls?.let { name ->
                val tool = tools.first { it.definition.name == name }
                session.appendToolObservation(tool, turn.observation, true)
                history.add(ChatMessage.ToolObservation(name, turn.observation, true))
            }
            turn.assistant?.let {
                session.appendAssistant(it)
                history.add(ChatMessage.Assistant(it))
            }
        }

        // What the MODEL sees: the real builder, the real tool definitions, the
        // real budget. If the builder ever stopped carrying history, this side
        // would go false and the finding would invert — correctly.
        val visible = tools.take(MODEL_VISIBLE_TOOLS)
        val prompt = DefaultContextBuilder().build(
            task = probe.finalTurn.utterance,
            history = history,
            memories = emptyList<Memory>(),
            tools = visible.map { it.definition },
        )
        val modelText = prompt.joinToString(" ") { messageText(it) }

        // What the SELECTOR sees: the real session, asked the real question.
        // `currentKeywords` is the exact call AgentController.selectTools makes.
        val selectorText = (probe.finalTurn.utterance + " " +
            session.currentKeywords().joinToString(" ")).lowercase()

        return Result(
            name = probe.name,
            subject = probe.subject,
            modelSees = modelText.lowercase().contains(probe.subject),
            selectorSees = selectorText.contains(probe.subject),
        )
    }

    private fun messageText(message: ChatMessage): String = when (message) {
        is ChatMessage.System -> message.text
        is ChatMessage.User -> message.text
        is ChatMessage.Assistant -> message.text
        is ChatMessage.ToolObservation -> "${message.toolName}: ${message.observation}"
    }

    private data class Probe(
        val name: String,
        val subject: String,
        val turns: List<ProbeTurn>,
    ) {
        val finalTurn: ProbeTurn get() = turns.last()
    }

    private data class ProbeTurn(
        val utterance: String,
        val calls: String? = null,
        val observation: String = "",
        val assistant: String? = null,
    )

    private companion object {
        /**
         * The tool count handed to the model-side prompt.
         *
         * Does not affect whether a word is PRESENT in the prompt — history is
         * carried independently of the tool list — so it is a fixed small
         * number rather than a second thing that can drift.
         */
        const val MODEL_VISIBLE_TOOLS = 10

        /**
         * The probes. Each is a conversation whose LAST turn needs a tool
         * identified by a word that appeared only in an EARLIER turn.
         *
         * `subject` must be a word that (a) identifies the tool and (b) is
         * absent from the final utterance — otherwise the selector can see it
         * for the uninteresting reason that the user just said it again.
         */
        val PROBES = listOf(
            Probe(
                name = "battery, then 'check that again'",
                subject = "battery",
                turns = listOf(
                    ProbeTurn(
                        utterance = "how much battery is left",
                        calls = "device.battery",
                        observation = "Battery 34%, about 2h 10m remaining.",
                        assistant = "You're on 34%.",
                    ),
                    ProbeTurn(
                        utterance = "remind me to check that again at 7",
                        calls = "alarm.create",
                        observation = "Alarm set for 19:00.",
                        assistant = "Done.",
                    ),
                ),
            ),
            Probe(
                name = "files, then 'what does it say'",
                subject = "mortgage",
                turns = listOf(
                    ProbeTurn(
                        utterance = "is there a PDF about the mortgage anywhere",
                        calls = "files.search",
                        observation = "Documents/mortgage-2026.pdf",
                        assistant = "Yes, Documents/mortgage-2026.pdf.",
                    ),
                    ProbeTurn(
                        utterance = "what does it say",
                        calls = "files.read_text",
                        observation = "Rate 4.2%, balance 184,300.",
                        assistant = "4.2% on 184,300.",
                    ),
                ),
            ),
            Probe(
                name = "calendar, then 'is that in there'",
                subject = "coffee",
                turns = listOf(
                    ProbeTurn(
                        utterance = "add a coffee with Priya at 4 this afternoon",
                        calls = "calendar.create",
                        observation = "Created: Coffee with Priya, 16:00.",
                        assistant = "Added at 4pm.",
                    ),
                    ProbeTurn(
                        utterance = "is that definitely in there",
                        calls = "calendar.search",
                        observation = "16:00 Coffee with Priya.",
                        assistant = "Yes, 4pm.",
                    ),
                ),
            ),
        )
    }
}

/**
 * The grammar consequence, printed rather than asserted.
 *
 * A missed tool is not a deprioritised tool. [GrammarBuilder.forActions] builds
 * the action grammar from the SELECTED set, so a tool that did not make the
 * cut has no production in the grammar at all and the model cannot emit a
 * syntactically valid call to it. This prints the actual grammar so a reader
 * can see the tool absent rather than take it on trust.
 */
internal fun grammarMentions(tools: List<dev.localintelligence.core.tool.AgentTool>): String =
    GrammarBuilder.forActions(tools.map { it.definition })

/** The system prompt for a visible set, for size comparison in the report. */
internal fun systemPromptFor(tools: List<dev.localintelligence.core.tool.AgentTool>): String =
    SystemPrompts.forTools(tools.map { it.definition })
