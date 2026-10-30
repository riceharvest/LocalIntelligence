package dev.localintelligence.core.tool

/**
 * Whether a message asks for a tool: it mentions the tool's name or one of its tags.
 *
 * WHY NOT THE SELECTOR'S SCORE: that score also counts words shared with the tool's
 * description, and common words ("for", "the", "what") give almost every tool a score
 * above zero, so nearly every call would count as asked for. Measured offline on the
 * shipped tools: "Give me one short tip to sleep better" scored alarm.create and
 * apps.open above zero; by name and tags it asks for neither, while "Set a timer for 12
 * minutes", "Zet een wekker om 7 uur" and "Open the calculator" still ask for their tool.
 * Used by the risk policy's guard, which confirms an action the message did not ask for.
 */
object ToolRequest {
    fun asksFor(message: String, definition: ToolDefinition): Boolean {
        if (message.contains(definition.name, ignoreCase = true)) return true
        val words = lexicalTokens(message).filterNot { it in FILLER }.toSet()
        if (words.isEmpty()) return false
        val own = lexicalTokens(definition.name) + definition.tags.flatMap { lexicalTokens(it) }
        return own.any { it in words }
    }

    /** Words too common to say which tool a message wants. */
    private val FILLER = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "your", "you", "can", "could",
        "please", "want", "need", "make", "help", "some", "any", "all", "get", "use", "not", "are",
        "was", "will", "would", "should", "have", "has", "how", "what", "when", "which", "who",
        "just", "also", "about", "there", "its", "one", "een", "het", "van", "wat", "dat", "die",
    )
}
