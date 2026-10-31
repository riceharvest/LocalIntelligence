package dev.localintelligence.core.settings

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.settings.SettingDef.Choice
import dev.localintelligence.core.settings.SettingDef.FloatValue
import dev.localintelligence.core.settings.SettingDef.IntValue
import dev.localintelligence.core.settings.SettingDef.Secret
import dev.localintelligence.core.settings.SettingDef.Toggle

/**
 * The settings the agent loop itself reads. Features add their own rows next to
 * their code (a plugin's `settings`); these are the ones every run depends on.
 *
 * Defaults are the values the loop shipped with before settings existed, so a
 * fresh install behaves exactly as it did.
 */
object CoreSettings {
    const val COMPUTE_AUTO = "auto"
    const val COMPUTE_CPU = "cpu"
    const val COMPUTE_VULKAN = "vulkan"
    const val COMPUTE_OPENCL = "opencl"

    /** [CONTEXT_LENGTH] value meaning "the Simple table decides". */
    const val CONTEXT_AUTO = "0"

    const val THEME_WALLPAPER = "wallpaper"
    const val THEME_GREEN = "green"
    const val THEME_BLUE = "blue"
    const val THEME_PURPLE = "purple"
    const val THEME_ORANGE = "orange"
    const val THEME_PINK = "pink"

    const val APPEARANCE_SYSTEM = "system"
    const val APPEARANCE_LIGHT = "light"
    const val APPEARANCE_DARK = "dark"

    val MODE = Choice(
        "mode", "Settings mode",
        "Simple keeps settings short and recommends a model for this phone. " +
            "Technical shows everything.",
        SettingSection.GENERAL, SettingsMode.SIMPLE.id,
        listOf(Choice.Option(SettingsMode.SIMPLE.id, "Simple"), Choice.Option(SettingsMode.TECHNICAL.id, "Technical")),
        simple = true, applies = SettingApplies.NOW,
    )

    // ---- general: what Simple mode shows -----------------------------------

    val ASSISTANT_NAME = SettingDef.Text(
        "general.assistant_name", "Assistant name",
        "What the assistant calls itself. Leave empty for no name.",
        SettingSection.GENERAL, default = "", maxLength = 40, simple = true,
    )
    val THEME_COLOR = Choice(
        "ui.theme_color", "Theme colour",
        "Wallpaper takes the colours of your wallpaper (Android 12 and newer).",
        SettingSection.GENERAL, THEME_GREEN,
        listOf(
            Choice.Option(THEME_WALLPAPER, "Wallpaper"),
            Choice.Option(THEME_GREEN, "Green"),
            Choice.Option(THEME_BLUE, "Blue"),
            Choice.Option(THEME_PURPLE, "Purple"),
            Choice.Option(THEME_ORANGE, "Orange"),
            Choice.Option(THEME_PINK, "Pink"),
        ),
        simple = true, applies = SettingApplies.NOW,
    )
    val APPEARANCE = Choice(
        "ui.appearance", "Appearance",
        "Light or dark, or the same as the phone.",
        SettingSection.GENERAL, APPEARANCE_SYSTEM,
        listOf(
            Choice.Option(APPEARANCE_SYSTEM, "Phone"),
            Choice.Option(APPEARANCE_LIGHT, "Light"),
            Choice.Option(APPEARANCE_DARK, "Dark"),
        ),
        simple = true, applies = SettingApplies.NOW,
    )

    // ---- approvals ---------------------------------------------------------
    // Not simple: Simple mode always asks, so an auto-accept chosen in Technical
    // mode cannot keep running where the screen no longer shows it.

    val AUTO_ACCEPT_DESTRUCTIVE = Toggle(
        "approvals.auto_destructive", "Run deletions without asking",
        "Delete and overwrite actions run without a confirmation. Bulk deletions and anything " +
            "outside the app's own storage still ask.",
        SettingSection.APPROVALS, default = false,
    )
    val AUTO_ACCEPT_EXTERNAL = Toggle(
        "approvals.auto_external", "Send messages without asking",
        "Replies and shares to other people go out without a confirmation. A message that is " +
            "only a link still asks. Texts and calls the phone places always ask.",
        SettingSection.APPROVALS, default = false,
    )
    val AUTO_ACCEPT_NETWORK = Toggle(
        "approvals.auto_network", "Open websites without asking",
        "Fetching a web page or searching runs without a confirmation.",
        SettingSection.APPROVALS, default = false,
    )
    val AUTO_ACCEPT_IN_BACKGROUND = Toggle(
        "approvals.auto_background", "Also in scheduled tasks",
        "Scheduled and background tasks use the choices above. Off: they skip anything that " +
            "would need a confirmation.",
        SettingSection.APPROVALS, default = false,
    )

    // ---- interface ---------------------------------------------------------

    val TOOL_CALLS_AUTO_COLLAPSE = Toggle(
        "ui.tool_calls_auto_collapse", "Fold tool calls when done",
        "The list of actions the assistant took folds into one line once it has answered.",
        SettingSection.INTERFACE, default = true, simple = true, applies = SettingApplies.NOW,
    )

    // ---- voice -------------------------------------------------------------

    val READ_REPLIES_ALOUD = Toggle(
        "voice.read_aloud", "Read replies aloud",
        "Each answer is read out loud when it is finished.",
        SettingSection.VOICE, default = false, simple = true, applies = SettingApplies.NOW,
    )
    val VOICE_SOURCE = Choice(
        "voice.source", "Voice",
        "The model's own voice when the loaded model can speak (LFM3-Omni, LFM3-Audio), otherwise " +
            "the phone's text-to-speech.",
        SettingSection.VOICE, default = "model",
        options = listOf(Choice.Option("model", "Model's voice"), Choice.Option("system", "Phone's voice")),
        simple = true, applies = SettingApplies.NOW,
    )
    val MODEL_VOICE = Choice(
        "voice.model_voice", "Model voice", "Which of the model's voices to use, where it has more than one.",
        SettingSection.VOICE, default = "female",
        options = listOf(Choice.Option("female", "Female"), Choice.Option("male", "Male")),
        applies = SettingApplies.NOW,
    )
    val SPEECH_RATE = FloatValue(
        "voice.rate", "Speech rate", "1 is normal speed.",
        SettingSection.VOICE, default = 1f, min = 0.5f, max = 2f, step = 0.05f, applies = SettingApplies.NOW,
    )
    val SPEECH_PITCH = FloatValue(
        "voice.pitch", "Speech pitch", "1 is the voice's normal pitch.",
        SettingSection.VOICE, default = 1f, min = 0.5f, max = 2f, step = 0.05f, applies = SettingApplies.NOW,
    )
    val HANDS_FREE_SILENCE = FloatValue(
        "voice.hands_free_silence", "Silence before sending",
        "Hands-free mode: how long a pause ends your turn, in seconds.",
        SettingSection.VOICE, default = 1.2f, min = 0.6f, max = 3f, step = 0.1f, applies = SettingApplies.NOW,
    )

    // ---- model -------------------------------------------------------------

    val COMPUTE = Choice(
        "model.compute", "Compute",
        "Where llama.cpp runs the model. Auto uses whichever the speed test measured faster on " +
            "this phone, and the CPU until it has run. A GPU that cannot load a model falls back to the CPU.",
        SettingSection.MODEL, COMPUTE_AUTO,
        listOf(
            Choice.Option(COMPUTE_AUTO, "Auto"),
            Choice.Option(COMPUTE_CPU, "CPU"),
            Choice.Option(COMPUTE_VULKAN, "GPU (Vulkan)"),
            Choice.Option(COMPUTE_OPENCL, "GPU (OpenCL)"),
        ),
        applies = SettingApplies.NEXT_MODEL_LOAD,
    )
    val GPU_LAYERS = IntValue(
        "model.gpu_layers", "GPU layers",
        "Layers placed on the GPU when the model runs there; the rest stay on the CPU. 99 is all of them.",
        SettingSection.MODEL, default = 99, min = 1, max = 99, applies = SettingApplies.NEXT_MODEL_LOAD,
    )

    val CONTEXT_LENGTH = Choice(
        "model.context_length", "Context length",
        "Tokens the model can hold at once. Automatic picks a size for this phone. More context " +
            "needs more memory and a bigger prompt takes longer to read.",
        SettingSection.MODEL, CONTEXT_AUTO,
        listOf(
            Choice.Option(CONTEXT_AUTO, "Automatic"),
            Choice.Option("2048", "2,048"),
            Choice.Option("4096", "4,096"),
            Choice.Option("8192", "8,192"),
            Choice.Option("16384", "16,384"),
            Choice.Option("32768", "32,768"),
        ),
        applies = SettingApplies.NEXT_MODEL_LOAD,
    )

    /**
     * Technical only: Simple mode reads the default (q8_0). A quantised V cache needs flash
     * attention; the native load retries with f16 when the context refuses the chosen type.
     */
    val KV_CACHE_TYPE = Choice(
        "model.kv_cache_type", "KV cache type",
        "How the model's attention memory is stored: q8_0 halves the memory of f16 for a negligible " +
            "quality cost, q4_0 quarters it.",
        SettingSection.MODEL, "q8_0",
        listOf(
            Choice.Option("q8_0", "q8_0 (half the memory)"),
            Choice.Option("f16", "f16"),
            Choice.Option("q4_0", "q4_0 (smallest, lower quality)"),
        ),
        applies = SettingApplies.NEXT_MODEL_LOAD,
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

    val NATIVE_TOOL_FORMAT = Toggle(
        "model.native_tool_format", "Model's own tool format",
        "llama.cpp only: the model is shown its tools and calls them in the format it was trained on, " +
            "instead of the app's own format.",
        SettingSection.MODEL, default = true,
    )

    /**
     * How many tokens the model may think before it answers. The native backend
     * ends the thought when the budget runs out, so a small model that thinks on
     * a plain "hi" is cut off instead of going on for a thousand tokens. Technical
     * only: the default is measured, and most users never need to see it.
     */
    val THINKING_BUDGET = IntValue(
        "model.thinking_budget", "Thinking",
        "Tokens the model may think before it answers. 0 turns thinking off.",
        SettingSection.MODEL, default = 128, min = 0, max = 1024,
    )

    /**
     * The HuggingFace read token for gated repositories. Technical only: a
     * Simple user who never opens a gated repo never needs it, and the Hub's
     * own error says where to add it.
     */
    val HF_READ_TOKEN = Secret(
        "hub.hf_read_token", "HuggingFace read token",
        "Needed only for gated repositories, after you accept the licence on huggingface.co. " +
            "Create a read token there. Stored encrypted and never shown back. Public models " +
            "do not need one.",
        SettingSection.MODEL,
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
    val TOOL_LOOKUP = Toggle(
        "agent.tool_lookup", "Let the model look up tools",
        "The prompt lists a few tools and a lookup tool; the model asks for others when it needs them. " +
            "A shorter prompt, but a small model may not look up the right tool.",
        SettingSection.AGENT, default = false,
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
        MODE, ASSISTANT_NAME, THEME_COLOR, APPEARANCE,
        AUTO_ACCEPT_DESTRUCTIVE, AUTO_ACCEPT_EXTERNAL, AUTO_ACCEPT_NETWORK, AUTO_ACCEPT_IN_BACKGROUND,
        TOOL_CALLS_AUTO_COLLAPSE,
        READ_REPLIES_ALOUD, VOICE_SOURCE, MODEL_VOICE, SPEECH_RATE, SPEECH_PITCH, HANDS_FREE_SILENCE,
        TEMPERATURE, TOP_P, TOP_K, MIN_P, REPEAT_PENALTY, PENALTY_LAST_N, FREQUENCY_PENALTY, PRESENCE_PENALTY,
        COMPUTE, GPU_LAYERS, CONTEXT_LENGTH, KV_CACHE_TYPE,
        SEED, MAX_OUTPUT_TOKENS, CONSTRAINED_OUTPUT, MODEL_CHAT_TEMPLATE, NATIVE_TOOL_FORMAT, THINKING_BUDGET, HF_READ_TOKEN,
        MAX_STEPS, MAX_VISIBLE_TOOLS, TOOL_LOOKUP, MAX_MALFORMED_RETRIES, WORKING_TOKEN_LIMIT, MEMORY_RESULTS, OBSERVATION_BUDGET,
    )
}

fun SettingsReader.agentConfig(): AgentConfig = AgentConfig(
    maxSteps = this[CoreSettings.MAX_STEPS],
    maxVisibleTools = this[CoreSettings.MAX_VISIBLE_TOOLS],
    maxMalformedRetries = this[CoreSettings.MAX_MALFORMED_RETRIES],
    workingTokenLimit = this[CoreSettings.WORKING_TOKEN_LIMIT],
    memoryResults = this[CoreSettings.MEMORY_RESULTS],
    observationBudgetChars = this[CoreSettings.OBSERVATION_BUDGET],
    toolLookup = this[CoreSettings.TOOL_LOOKUP],
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
