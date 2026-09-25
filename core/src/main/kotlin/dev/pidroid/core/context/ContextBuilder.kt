package dev.pidroid.core.context

import dev.pidroid.core.agent.Memory
import dev.pidroid.core.model.ChatMessage
import dev.pidroid.core.tool.ToolDefinition

/**
 * Assembles the exact prompt the model sees. Optimized for small models:
 * target 3-6K tokens, hard objective that no routine task needs a 20K prefill.
 */
interface ContextBuilder {
    fun build(
        task: String,
        history: List<ChatMessage>,
        memories: List<Memory>,
        tools: List<ToolDefinition>,
    ): List<ChatMessage>
}

/**
 * Structured compaction output. Deliberately NOT a generic conversation
 * summary — a 3B model needs explicit slots to stay on task.
 */
data class CompactedState(
    val task: String,
    val progress: List<String> = emptyList(),
    val knownFacts: List<String> = emptyList(),
    val actionsTaken: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val remainingWork: List<String> = emptyList(),
) {
    fun render(): String = buildString {
        appendLine("Task: $task")
        if (progress.isNotEmpty()) appendLine("Progress: ${progress.joinToString("; ")}")
        if (knownFacts.isNotEmpty()) appendLine("Known facts: ${knownFacts.joinToString("; ")}")
        if (actionsTaken.isNotEmpty()) appendLine("Actions already taken: ${actionsTaken.joinToString("; ")}")
        if (failures.isNotEmpty()) appendLine("Failures: ${failures.joinToString("; ")}")
        if (remainingWork.isNotEmpty()) appendLine("Remaining work: ${remainingWork.joinToString("; ")}")
    }.trim()
}

/** The system prompt. Kept tiny on purpose — no philosophy document. */
object SystemPrompts {
    val BASE: String = """
You operate this Android device on behalf of the user.

Use the available tools when required.
Never claim an action succeeded unless its tool result says it succeeded.
Do not repeat an action that already failed unless something relevant changed.
When the task is complete, answer concisely.
""".trimIndent()

    fun forTools(tools: List<ToolDefinition>): String = buildString {
        appendLine(BASE)
        if (tools.isNotEmpty()) {
            appendLine()
            appendLine("Available tools:")
            tools.forEach { def ->
                appendLine("- ${def.name}: ${def.description}")
            }
        }
    }
}
