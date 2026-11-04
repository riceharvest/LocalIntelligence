package dev.localintelligence.core.persona

import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.plugin.PluginHooks
import dev.localintelligence.core.settings.SettingsReader

/**
 * A named description of how the assistant should act ("terse, answers in Dutch").
 *
 * ## WHY A CHAT KEEPS ITS PERSONA
 *
 * A chat stores the persona's id, not its text. The text is read when a run starts,
 * so editing a persona changes the chats that use it from their next message, and
 * deleting one puts those chats back to no persona. Picking a different persona for a
 * chat that already has messages would change the rules under a conversation the
 * model has already answered, so the choice is made when the chat starts.
 */
data class Persona(val id: Long, val name: String, val text: String)

object PersonaLimits {
    const val MAX_NAME_CHARS: Int = 60

    /** How the assistant should act, as the user wrote it. Short, so it fits next to the profile in the prompt. */
    const val MAX_TEXT_CHARS: Int = 500

    /** The message the editor shows for input that cannot be saved, or null when it can. */
    fun problemWith(name: String, text: String): String? = when {
        name.isBlank() -> "Give the persona a name."
        name.trim().length > MAX_NAME_CHARS -> "The name is at most $MAX_NAME_CHARS characters."
        text.isBlank() -> "Describe how the assistant should act."
        text.trim().length > MAX_TEXT_CHARS -> "The description is at most $MAX_TEXT_CHARS characters."
        else -> null
    }
}

/**
 * Persistence for personas and for which persona each chat was started with.
 *
 * Implemented in `:android` on Room. `:core` names only the operations, so the loop and the
 * tests never touch a database.
 */
interface PersonaStore {
    suspend fun list(): List<Persona>

    /** The persona with [id], or null if it was deleted. */
    suspend fun get(id: Long): Persona?

    /** Adds a persona when [id] is null, otherwise replaces its name and text. Returns the id. */
    suspend fun save(id: Long?, name: String, text: String): Long

    /** Deletes a persona. Chats that used it are set to no persona first. */
    suspend fun delete(id: Long)

    /** The persona a chat was started with, or null for none. */
    suspend fun chatPersonaId(sessionId: Long): Long?

    suspend fun setChatPersona(sessionId: Long, personaId: Long?)
}

/**
 * The chat's persona, as session lines: the same place as the About-you profile, so
 * the prompt prefix stays reusable from one message to the next.
 *
 * Built per run only when the chat has a persona. A chat with none adds no plugin at all,
 * so its prompt is the same bytes it was before personas existed.
 */
class PersonaPlugin(private val text: String) : AgentPlugin {
    override val id: String = "chat.persona"
    override val title: String = "Persona"
    override val summary: String = "How the assistant acts in this chat. Chosen when the chat starts."

    override fun hooks(settings: SettingsReader): PluginHooks? = object : PluginHooks {
        override fun contextForSession(): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            return listOf("How to act in this chat:", trimmed)
        }
    }
}
