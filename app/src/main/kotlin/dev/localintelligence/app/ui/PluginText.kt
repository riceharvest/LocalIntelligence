package dev.localintelligence.app.ui

import android.content.Context
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.app.R

/**
 * The phone-language title and summary of a built-in plugin, shown on the Settings screen.
 *
 * WHY a map: the plugins declare their English in `:core` and `:android`, where there
 * is no Android resources. The display text lives in strings.xml, keyed by the plugin
 * id. A plugin without a row here (a script plugin, whose text the user wrote) shows
 * the text it declares, which is the only text it has.
 */
internal object PluginText {
    private val texts: Map<String, Pair<Int, Int>> = mapOf(
    "cloud.chatgpt" to Pair(R.string.plugin_chatgpt_title, R.string.plugin_chatgpt_summary),
    "cloud.sandbox" to Pair(R.string.plugin_sandbox_title, R.string.plugin_sandbox_summary),
    "documents" to Pair(R.string.plugin_documents_title, R.string.plugin_documents_summary),
    "cloud.browser" to Pair(R.string.plugin_cloud_browser_title, R.string.plugin_cloud_browser_summary),
    "gateway.local_server" to Pair(R.string.plugin_local_server_title, R.string.plugin_local_server_summary),
    "cloud.openrouter" to Pair(R.string.plugin_openrouter_title, R.string.plugin_openrouter_summary),
    "gateway.telegram" to Pair(R.string.plugin_telegram_title, R.string.plugin_telegram_summary),
    "home.assistant" to Pair(R.string.plugin_home_assistant_title, R.string.plugin_home_assistant_summary),
    "mcp.remote" to Pair(R.string.plugin_mcp_title, R.string.plugin_mcp_summary),
    "email.imap" to Pair(R.string.plugin_email_title, R.string.plugin_email_summary),
    "tools.memory" to Pair(R.string.plugin_memory_block_title, R.string.plugin_memory_block_summary),
    "memory.profile" to Pair(R.string.plugin_profile_title, R.string.plugin_profile_summary),
    "chat.persona" to Pair(R.string.plugin_persona_title, R.string.plugin_persona_summary),
    "skills" to Pair(R.string.plugin_skills_title, R.string.plugin_skills_summary),
    "tools.device" to Pair(R.string.plugin_device_title, R.string.plugin_device_summary),
    "tools.files" to Pair(R.string.plugin_files_title, R.string.plugin_files_summary),
    "tools.phone" to Pair(R.string.plugin_phone_title, R.string.plugin_phone_summary),
    "tools.messages" to Pair(R.string.plugin_messages_title, R.string.plugin_messages_summary),
    "tools.apps" to Pair(R.string.plugin_apps_title, R.string.plugin_apps_summary),
    "tools.clipboard" to Pair(R.string.plugin_clipboard_title, R.string.plugin_clipboard_summary),
    "tools.alarms" to Pair(R.string.plugin_alarms_title, R.string.plugin_alarms_summary),
    "tools.calendar" to Pair(R.string.plugin_calendar_title, R.string.plugin_calendar_summary),
    "tools.contacts" to Pair(R.string.plugin_contacts_title, R.string.plugin_contacts_summary),
    "tools.notifications" to Pair(R.string.plugin_notifications_title, R.string.plugin_notifications_summary),
    "tools.web" to Pair(R.string.plugin_web_title, R.string.plugin_web_summary),
    "tools.info" to Pair(R.string.plugin_info_title, R.string.plugin_info_summary),
    "tools.location" to Pair(R.string.plugin_location_title, R.string.plugin_location_summary),
    "tools.drafts" to Pair(R.string.plugin_drafts_title, R.string.plugin_drafts_summary),
    "tools.subtask" to Pair(R.string.plugin_subtask_title, R.string.plugin_subtask_summary),
    "tools.todo" to Pair(R.string.plugin_todo_title, R.string.plugin_todo_summary),
    "tools.observations" to Pair(R.string.plugin_observations_title, R.string.plugin_observations_summary),
    "memory.history" to Pair(R.string.plugin_history_title, R.string.plugin_history_summary),
    "tools.phone_search" to Pair(R.string.plugin_phone_search_title, R.string.plugin_phone_search_summary),
    )

    /** The title of [plugin], in the phone's language when a string exists for it. */
    fun title(context: Context, plugin: AgentPlugin): String {
        if (plugin.id == "mcp.remote" && plugin.title != "Remote tools (MCP)") {
            return context.getString(R.string.plugin_mcp_managed_title)
        }
        return texts[plugin.id]?.let { context.getString(it.first) } ?: plugin.title
    }

    /** The one-line summary of [plugin], in the phone's language when a string exists for it. */
    fun summary(context: Context, plugin: AgentPlugin): String =
        texts[plugin.id]?.let { context.getString(it.second) } ?: plugin.summary
}
