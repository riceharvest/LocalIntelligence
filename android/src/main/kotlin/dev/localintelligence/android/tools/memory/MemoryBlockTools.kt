package dev.localintelligence.android.tools.memory

import dev.localintelligence.core.memory.MemoryBlock
import dev.localintelligence.core.memory.MemoryBlockEdit
import dev.localintelligence.core.memory.MemoryBlockStore
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.plugin.PluginHooks
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * `memory.update_block`: the one tool that writes the memory block.
 *
 * WHY THE OBSERVATION IS OUR OWN WORDING: it is written by this app, not copied from a page,
 * so it is LOCAL. The guard that stops a page from calling this tool is in `RiskPolicy`, not
 * here; a tool cannot be trusted to refuse itself.
 *
 * The definition must match `V0ToolCatalogue` by name and tier. `requireCatalogueAgreement`
 * checks that at launch.
 */
class MemoryUpdateTool(private val store: MemoryBlockStore) : AgentTool {

    override val definition = ToolDefinition(
        name = "memory.update_block",
        description = "Adds a note to the memory block the assistant sees on every step, or " +
            "replaces or clears it. Use only when the user asks you to remember something.",
        category = "memory",
        schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "add appends one note; replace sets the whole block; clear empties it.")
                    putJsonArray("enum") { add("add"); add("replace"); add("clear") }
                }
                putJsonObject("text") {
                    put("type", "string")
                    put("maxLength", 1000)
                    put("description", "The note to keep, in the user's words. Not needed for clear.")
                }
            }
            putJsonArray("required") { add("action") }
        },
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("remember", "remember that", "keep in mind", "note that", "save this", "memory"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val note = (args["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (val edit = MemoryBlockEdit.apply(store.read(), action, note)) {
            is MemoryBlockEdit.Refused -> ToolResult(
                success = false,
                observation = edit.reason,
                error = ToolError.InvalidArguments(edit.reason),
            )
            is MemoryBlockEdit.Changed -> {
                val kept = note.replace(Regex("\\s+"), " ").trim()
                val changeId = store.write(edit.text, kept)
                val observation = if (edit.text.isEmpty()) {
                    "The memory block is cleared."
                } else {
                    "Remembered. The memory block now holds ${edit.text.length} of ${MemoryBlock.MAX_CHARS} characters."
                }
                // The id travels in data, which the UI and trace read and the model never sees.
                ToolResult(
                    success = true,
                    observation = observation,
                    data = buildJsonObject { put("change_id", changeId) },
                )
            }
        }
    }
}

/**
 * The memory-block family: the write tool and the block's line in the system prompt, under
 * one switch. Two switches would let the tool write a block the prompt does not show, or show
 * a block nothing can edit; one plugin rules that out.
 *
 * The line is read through [SettingsReader] once per run, like the profile. An edit made in a
 * run shows in the next run's prompt, not the current one, which keeps the prompt prefix the
 * same across the run.
 */
class MemoryBlockFamily(private val store: MemoryBlockStore) : AgentPlugin {
    override val id: String = "tools.memory"
    override val title: String = "Memory block"
    override val summary: String =
        "A short note the assistant keeps and sees on every step. It changes when you ask it to remember something."
    override val settings: List<SettingDef<*>> = listOf(MemoryBlock.TEXT)

    private val tool: AgentTool by lazy { MemoryUpdateTool(store) }

    override fun tools(): List<AgentTool> = listOf(tool)

    override fun hooks(settings: SettingsReader): PluginHooks = object : PluginHooks {
        override fun contextForSession(): List<String> {
            val text = settings[MemoryBlock.TEXT].trim()
            if (text.isEmpty()) return emptyList()
            return listOf("Memory block (things the user asked you to remember):", text)
        }
    }
}
