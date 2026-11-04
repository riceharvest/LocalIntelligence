package dev.localintelligence.app.ui

import dev.localintelligence.core.skill.DraftStep
import dev.localintelligence.core.skill.RepeatCandidate
import dev.localintelligence.core.skill.RepeatDetector
import dev.localintelligence.core.skill.RepeatRun
import dev.localintelligence.core.skill.SkillDraft

/** Fewer tool calls than this and the run was a single lookup, which is not worth a skill. */
private const val MIN_TOOLS_FOR_SKILL = 2

/**
 * The SKILL.md draft for "Save as skill", or null when the transcript does not
 * offer it.
 *
 * It is offered only on the last row, when that row is an answer, so a run that
 * stopped with a notice never gets one. The steps are the successful tool calls
 * of the run that produced it, in order, and the task is the user message that
 * started the run.
 */
internal fun saveAsSkillDraft(messages: List<ChatMessage>): String? {
    if (messages.lastOrNull() !is ChatMessage.Assistant) return null
    val start = messages.indexOfLast { it is ChatMessage.User }
    if (start < 0) return null
    val task = (messages[start] as ChatMessage.User).text
    val steps = messages.subList(start + 1, messages.size)
        .filterIsInstance<ChatMessage.ToolStep>()
        .filter { it.success && it.toolName.isNotBlank() }
        .map { DraftStep(tool = it.toolName, args = it.args) }
    if (steps.size < MIN_TOOLS_FOR_SKILL) return null
    return SkillDraft.render(task, steps)
}

/** A repeated request to offer as a skill, with the calls of its newest run for the skill body. */
internal data class RepeatOffer(
    val candidate: RepeatCandidate,
    val steps: List<DraftStep>,
)

/**
 * The repeat offer for the transcript, or null. Runs are the chat's own answered requests: a user
 * message, its tool calls, then an answer. A run with an unanswered request is not counted.
 *
 * WHY THE TRANSCRIPT AND NOT THE DATABASE: the chat holds the messages this app kept, and those
 * are the same rows the database stores, so this sees the history the chat shows. It does not
 * read other conversations.
 */
internal fun repeatOfferFor(messages: List<ChatMessage>): RepeatOffer? {
    if (messages.lastOrNull() !is ChatMessage.Assistant) return null
    val runs = ArrayList<RepeatRun>()
    var request: String? = null
    var calls = ArrayList<ChatMessage.ToolStep>()
    for (message in messages) {
        when (message) {
            is ChatMessage.User -> {
                request = message.text
                calls = ArrayList()
            }
            is ChatMessage.ToolStep -> calls += message
            is ChatMessage.Assistant -> {
                val text = request ?: continue
                val named = calls.filter { it.toolName.isNotBlank() }
                runs += RepeatRun(
                    request = text,
                    tools = named.map { it.toolName },
                    success = named.isNotEmpty() && named.all { it.success },
                )
                request = null
            }
            else -> Unit
        }
    }
    val candidate = RepeatDetector.candidate(runs) ?: return null
    // The newest run's calls, for the skill body. The newest run is the last answered one.
    val newest = messages.subList(messages.indexOfLast { it is ChatMessage.User }, messages.size)
        .filterIsInstance<ChatMessage.ToolStep>()
        .filter { it.toolName.isNotBlank() && it.success }
        .map { DraftStep(tool = it.toolName, args = it.args) }
    return RepeatOffer(candidate, newest)
}
