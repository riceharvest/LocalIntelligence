package dev.localintelligence.core.skill

/** One tool call from a finished run, in order. [args] is the call's arguments as the trace recorded them. */
data class DraftStep(val tool: String, val args: String)

/**
 * Builds the SKILL.md a "Save as skill" action starts from. The user edits it
 * before anything is saved, so this only has to be a sensible first draft:
 * a name from the task, the task as the description, and the tool calls as
 * numbered steps with their arguments.
 */
object SkillDraft {

    private const val MAX_DESCRIPTION = 300
    private const val MAX_ARGS = 300
    private const val NAME_WORDS = 6

    fun render(task: String, steps: List<DraftStep>): String {
        val description = oneLine(task, MAX_DESCRIPTION)
        val body = buildString {
            append("# ").append(description).append('\n').append('\n')
            append("Do the same thing the same way. Change an argument if the new request differs.\n")
            if (steps.isNotEmpty()) {
                append('\n')
                steps.forEachIndexed { index, step ->
                    append(index + 1).append(". `").append(step.tool).append('`')
                    val args = oneLine(step.args, MAX_ARGS)
                    if (args.isNotEmpty()) append(" with ").append(args)
                    append('\n')
                }
            }
        }
        return SkillParser.render(nameFor(task), description, body)
    }

    /** The first few words of the task, as a folder-safe name. Never empty. */
    fun nameFor(task: String): String {
        val words = task.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.take(NAME_WORDS)
        return SkillSlug.of(words.joinToString("-")).ifEmpty { "saved-task" }
    }

    private fun oneLine(text: String, max: Int): String =
        text.replace(Regex("\\s+"), " ").trim().take(max).trim()
}
