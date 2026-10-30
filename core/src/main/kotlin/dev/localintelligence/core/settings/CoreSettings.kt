package dev.localintelligence.core.settings

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.settings.SettingDef.Choice
import dev.localintelligence.core.settings.SettingDef.FloatValue
import dev.localintelligence.core.settings.SettingDef.IntValue
import dev.localintelligence.core.settings.SettingDef.Toggle

/**
 * The settings the agent loop itself reads. Features add their own rows next to
 * their code (a plugin's `settings`); these are the ones every run depends on.
 *
 * Defaults are the values the loop shipped with before settings existed, so a
 * fresh install behaves exactly as it did.
 */
object CoreSettings {
    val MODE = Choice(
        "mode", "Settings mode",
        "Simple picks the model and settings for this phone and hides the technical ones. " +
            "Technical shows everything.",
        SettingSection.GENERAL, SettingsMode.SIMPLE.id,
        listOf(Choice.Option(SettingsMode.SIMPLE.id, "Simple"), Choice.Option(SettingsMode.TECHNICAL.id, "Technical")),
        simple = true, applies = SettingApplies.NOW,
    )

    // ---- approvals ---------------------------------------------------------

    val AUTO_ACCEPT_DESTRUCTIVE = Toggle(
        "approvals.auto_destructive", "Run deletions without asking",
        "Delete and overwrite actions run without a confirmation. Bulk deletions and anything " +
            "outside the app's own storage still ask.",
        SettingSection.APPROVALS, default = false, simple = true,
    )
    val AUTO_ACCEPT_EXTERNAL = Toggle(
        "approvals.auto_external", "Send messages without asking",
        "Replies and shares to other people go out without a confirmation. A message that is " +
            "only a link still asks.",
        SettingSection.APPROVALS, default = false, simple = true,
    )
    val AUTO_ACCEPT_NETWORK = Toggle(
        "approvals.auto_network", "Open websites without asking",
        "Fetching a web page or searching runs without a confirmation.",
        SettingSection.APPROVALS, default = false, simple = true,
    )
    val AUTO_ACCEPT_IN_BACKGROUND = Toggle(
        "approvals.auto_background", "Also in scheduled tasks",
        "Scheduled and background tasks use the choices above. Off: they skip anything that " +
            "would need a confirmation.",
        SettingSection.APPROVALS, default = false, simple = true,
    )

    // ---- interface ---------------------------------------------------------

    val TOOL_CALLS_AUTO_COLLAPSE = Toggle(
        "ui.tool_calls_auto_collapse", "Fold tool calls when done",
        "The list of actions the assistant took folds into one line once it has answered.",
        SettingSection.INTERFACE, default = true, simple = true, applies = SettingApplies.NOW,
    )

    // ---- sampling ----------------------------------------------------------

    val TEMPERATURE = FloatValue(
        "sampling.temperature", "Temperature", "Randomness. 0 is deterministic.",
        SettingSection.SAMPLING, default = 0.7f, min = 0f, max = 2f, step = 0.05f,
    )
    val TOP_P = FloatValue(
        "sampling.top_p", "Top-p", "Nucleus sampling cutoff. 1 disables it.",
        SettingSection.SAMPLING, default = 0.95f, min = 0f, max = 1f, step = 0.01f,
    )
    val TOP_K = IntValue(
        "sampling.top_k", "Top-k", "Keep only the k most likely tokens. 0 disables it.",
        SettingSection.SAMPLING, default = 40, min = 0, max = 200,
    )
    val MIN_P = FloatValue(
        "sampling.min_p", "Min-p", "Drop tokens below this share of the top token's probability.",
        SettingSection.SAMPLING, default = 0.05f, min = 0f, max = 1f, step = 0.01f,
    )
    val REPEAT_PENALTY = FloatValue(
        "sampling.repeat_penalty", "Repeat penalty", "1 disables it.",
        SettingSection.SAMPLING, default = 1.1f, min = 1f, max = 2f, step = 0.01f,
    )
    val PENALTY_LAST_N = IntValue(
        "sampling.penalty_last_n", "Penalty window",
        "How many recent tokens the repeat and frequency penalties look back over. 0 disables them.",
        SettingSection.SAMPLING, default = 64, min = 0, max = 2048,
    )
    val FREQUENCY_PENALTY = FloatValue(
        "sampling.frequency_penalty", "Frequency penalty",
        "Pushes down a token more the more often it appeared in the window. 0 disables it.",
        SettingSection.SAMPLING, default = 0f, min = 0f, max = 2f, step = 0.05f,
    )
    val PRESENCE_PENALTY = FloatValue(
        "sampling.presence_penalty", "Presence penalty",
        "Pushes down any token already used in the window, once. 0 disables it.",
        SettingSection.SAMPLING, default = 0f, min = 0f, max = 2f, step = 0.05f,
    )
    val SEED = IntValue(
        "sampling.seed", "Seed", "-1 picks a new one every time.",
        SettingSection.SAMPLING, default = -1, min = -1, max = Int.MAX_VALUE,
    )
    val MAX_OUTPUT_TOKENS = IntValue(
        "sampling.max_output_tokens", "Max tokens per step", "Longest single reply or action.",
        SettingSection.SAMPLING, default = 512, min = 16, max = 8192, step = 16,
    )
    val CONSTRAINED_OUTPUT = Toggle(
        "sampling.constrained_output", "Grammar-constrained actions",
        "llama.cpp only: the model can only produce a valid action or reply. Off lets it write " +
            "freely and relies on the parser.",
        SettingSection.SAMPLING, default = true,
    )
    val MODEL_CHAT_TEMPLATE = Toggle(
        "sampling.model_chat_template", "Use the model's chat format",
        "llama.cpp only: the prompt uses the model's own turn markers, which small instruct " +
            "models answer better in. Off uses a plain 'User / Assistant' transcript.",
        SettingSection.SAMPLING, default = true,
    )

    // ---- agent loop --------------------------------------------------------

    val MAX_STEPS = IntValue(
        "agent.max_steps", "Max steps", "Actions per message before the agent stops.",
        SettingSection.AGENT, default = 8, min = 1, max = 50,
    )
    val MAX_VISIBLE_TOOLS = IntValue(
        "agent.max_visible_tools", "Tools shown per step", "How many tools the model sees at once.",
        SettingSection.AGENT, default = 6, min = 1, max = 25,
    )
    val MAX_MALFORMED_RETRIES = IntValue(
        "agent.retries", "Retries on a bad reply",
        "Malformed replies in a row before the agent gives up. 1 means no retry.",
        SettingSection.AGENT, default = 3, min = 1, max = 10,
    )
    val WORKING_TOKEN_LIMIT = IntValue(
        "agent.working_token_limit", "Compaction threshold",
        "Tokens of conversation kept before older turns are summarised.",
        SettingSection.AGENT, default = 6000, min = 1000, max = 128_000, step = 500,
    )
    val MEMORY_RESULTS = IntValue(
        "agent.memory_results", "Memories per step", "Remembered facts added to each request.",
        SettingSection.AGENT, default = 5, min = 0, max = 20,
    )
    val OBSERVATION_BUDGET = IntValue(
        "agent.observation_budget", "Tool output budget", "Characters of tool output the model sees.",
        SettingSection.AGENT, default = ObservationTruncator.DEFAULT_BUDGET_CHARS, min = 200, max = 20_000, step = 100,
    )

    val all: List<SettingDef<*>> = listOf(
        MODE,
        AUTO_ACCEPT_DESTRUCTIVE, AUTO_ACCEPT_EXTERNAL, AUTO_ACCEPT_NETWORK, AUTO_ACCEPT_IN_BACKGROUND,
        TOOL_CALLS_AUTO_COLLAPSE,
        TEMPERATURE, TOP_P, TOP_K, MIN_P, REPEAT_PENALTY, PENALTY_LAST_N, FREQUENCY_PENALTY, PRESENCE_PENALTY,
        SEED, MAX_OUTPUT_TOKENS, CONSTRAINED_OUTPUT, MODEL_CHAT_TEMPLATE,
        MAX_STEPS, MAX_VISIBLE_TOOLS, MAX_MALFORMED_RETRIES, WORKING_TOKEN_LIMIT, MEMORY_RESULTS, OBSERVATION_BUDGET,
    )
}

fun SettingsReader.agentConfig(): AgentConfig = AgentConfig(
    maxSteps = this[CoreSettings.MAX_STEPS],
    maxVisibleTools = this[CoreSettings.MAX_VISIBLE_TOOLS],
    maxMalformedRetries = this[CoreSettings.MAX_MALFORMED_RETRIES],
    workingTokenLimit = this[CoreSettings.WORKING_TOKEN_LIMIT],
    memoryResults = this[CoreSettings.MEMORY_RESULTS],
    observationBudgetChars = this[CoreSettings.OBSERVATION_BUDGET],
)

fun SettingsReader.samplingParams(): SamplingParams = SamplingParams(
    temperature = this[CoreSettings.TEMPERATURE],
    topP = this[CoreSettings.TOP_P],
    topK = this[CoreSettings.TOP_K],
    minP = this[CoreSettings.MIN_P],
    repeatPenalty = this[CoreSettings.REPEAT_PENALTY],
    penaltyLastN = this[CoreSettings.PENALTY_LAST_N],
    frequencyPenalty = this[CoreSettings.FREQUENCY_PENALTY],
    presencePenalty = this[CoreSettings.PRESENCE_PENALTY],
    seed = this[CoreSettings.SEED],
    maxOutputTokens = this[CoreSettings.MAX_OUTPUT_TOKENS],
)

/**
 * The risk tiers whose confirmations the user chose to skip. Empty for a
 * background run unless they also allowed that.
 */
fun SettingsReader.autoAcceptTiers(background: Boolean): Set<ToolRisk> {
    if (background && !this[CoreSettings.AUTO_ACCEPT_IN_BACKGROUND]) return emptySet()
    return buildSet {
        if (this@autoAcceptTiers[CoreSettings.AUTO_ACCEPT_DESTRUCTIVE]) add(ToolRisk.DESTRUCTIVE)
        if (this@autoAcceptTiers[CoreSettings.AUTO_ACCEPT_EXTERNAL]) add(ToolRisk.EXTERNAL_COMMUNICATION)
        if (this@autoAcceptTiers[CoreSettings.AUTO_ACCEPT_NETWORK]) add(ToolRisk.NETWORK_EGRESS)
    }
}
