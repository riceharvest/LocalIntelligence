package dev.localintelligence.app.ui

import dev.localintelligence.core.skill.DraftStep
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
