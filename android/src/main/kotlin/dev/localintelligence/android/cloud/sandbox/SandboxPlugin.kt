package dev.localintelligence.android.cloud.sandbox

import dev.localintelligence.android.tools.requireCatalogueAgreement
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.mode
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ToolError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ===========================================================================
// A cloud computer the remote model can run commands in, on the user's own Daytona key.
//
// Off by default, and gated four ways before anything is sent:
//   1. Technical mode only. Simple mode never offers these tools.
//   2. The plugin is switched on.
//   3. A Daytona key is stored.
//   4. The remote chat model is the one answering this run (AppContainer.newController keeps
//      the sandbox.* tools out of every other run). WHY: a small on-device model is not trusted
//      to drive a cloud computer that is billed to the user's account.
//
// One sandbox per conversation: it is created on first use, kept in memory for the session,
// and stopped (not deleted) by New chat. A stopped sandbox keeps its files on Daytona until
// Daytona's own timers delete it.
// ===========================================================================

/** Name prefix of every sandbox tool. The run assembly uses it to keep these out of other runs. */
const val SANDBOX_TOOL_PREFIX: String = "sandbox."

/** The settings rows this plugin adds. Keys are prefixed with the plugin id. */
object SandboxSettings {
    const val PLUGIN_ID = "cloud.sandbox"

    /** One option today. A second provider adds a Choice option and a [SandboxProvider]; nothing else changes. */
    val PROVIDER = SettingDef.Choice(
        "cloud.sandbox.provider", "Sandbox provider",
        "The service that runs the commands. Daytona is the only one so far.",
        SettingSection.PLUGINS, default = DAYTONA_ID,
        options = listOf(SettingDef.Choice.Option(DAYTONA_ID, "Daytona")),
    )

    /** Not simple: only Technical mode shows it, and Simple mode reads it as blank, so no key means no tools. */
    val API_KEY = SettingDef.Secret(
        "cloud.sandbox.api_key", "Daytona API key",
        "Your own key from the Daytona dashboard. Stored encrypted and never shown back. " +
            "Without one, the sandbox tools are not offered and nothing is sent.",
        SettingSection.PLUGINS,
    )

    val API_URL = SettingDef.Text(
        "cloud.sandbox.api_url", "Daytona API address",
        "Leave blank for Daytona's cloud. Your key is sent to this address, so change it only to " +
            "a Daytona server you run yourself. It must start with https://.",
        SettingSection.PLUGINS, default = DAYTONA_DEFAULT_API, maxLength = 200, blankMeansDefault = true,
    )

    val IDLE_MINUTES = SettingDef.IntValue(
        "cloud.sandbox.idle_minutes", "Stop the sandbox after idle",
        "Minutes without a command before Daytona stops the sandbox. A stopped sandbox keeps its files " +
            "and starts again on the next command.",
        SettingSection.PLUGINS, default = 15, min = 5, max = 240, step = 5,
    )

    val all: List<SettingDef<*>> = listOf(PROVIDER, API_KEY, API_URL, IDLE_MINUTES)
}

/**
 * Off by default. Offers `sandbox.run`, `sandbox.write_file` and `sandbox.read_file`, but only in
 * Technical mode with a key stored. [reader] is the live store, so a key or mode change applies at
 * the next message.
 */
class SandboxPlugin(private val reader: SettingsReader) : AgentPlugin {
    private val session = SandboxSession(reader)

    override val id: String = SandboxSettings.PLUGIN_ID
    override val title: String = "Cloud sandbox"
    override val summary: String =
        "Run commands and files in a cloud computer on your own Daytona account. Technical mode only, and " +
            "only while a remote chat model answers. Commands and files are sent to Daytona."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = SandboxSettings.all

    private val built: List<AgentTool> by lazy {
        requireCatalogueAgreement(
            listOf(
                SandboxRunTool(session),
                SandboxWriteFileTool(session),
                SandboxReadFileTool(session),
            ),
        )
    }

    override fun tools(): List<AgentTool> =
        if (reader.mode != SettingsMode.TECHNICAL || reader[SandboxSettings.API_KEY].isBlank()) emptyList() else built

    /** Every tool this plugin ships, key or no key, for the startup catalogue check. */
    fun shippedTools(): List<AgentTool> = built

    /** New chat: the conversation's sandbox is stopped, not deleted. Best effort; see [SandboxSession.end]. */
    suspend fun endConversation() = session.end()
}

/**
 * The sandbox of the current conversation, created and started on demand.
 *
 * A [Mutex] serialises creation, so two tool calls in one run cannot each create a sandbox. The
 * id is held in memory only: a process restart forgets it, and the next call creates a new one.
 */
internal class SandboxSession(private val reader: SettingsReader) {
    private val lock = Mutex()

    /** The sandbox this conversation uses, or null before the first call and after New chat. */
    private var current: SandboxInfo? = null

    /** The provider built from the live settings. Daytona is the only option, which the Choice setting enforces. */
    fun provider(): SandboxProvider = DaytonaProvider(
        apiBase = reader[SandboxSettings.API_URL],
        apiKey = reader[SandboxSettings.API_KEY],
    )

    /**
     * A running sandbox, creating, starting or waiting for it as needed.
     *
     * A sandbox that no longer exists, or is in a failed state, is replaced by a new one. A stopped
     * one is started. Anything still changing state is polled until it is started or the wait runs
     * out at [START_TIMEOUT_MS].
     */
    suspend fun ready(signal: CancellationSignal): SandboxInfo = lock.withLock {
        val provider = provider()
        val known = current?.let { knownOrNull(provider, it.id) }
        var info = known ?: provider.create(reader[SandboxSettings.IDLE_MINUTES])
        current = info
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        var startAsked = false
        while (info.state != STARTED) {
            if (signal.isCancelled()) {
                throw SandboxFailure(ToolError.Cancelled("sandbox wait"), "Stopped waiting for the sandbox.")
            }
            if (info.state in DEAD_STATES) {
                current = null
                throw SandboxFailure(
                    ToolError.Unavailable("sandbox ${info.state}"),
                    "The sandbox could not start (${info.state}). Try again; a new one will be made.",
                )
            }
            if (System.currentTimeMillis() > deadline) {
                throw SandboxFailure(
                    ToolError.Timeout("sandbox start"),
                    "The sandbox did not start within ${START_TIMEOUT_MS / 1000} seconds. Try again.",
                )
            }
            if (info.state in STOPPED_STATES && !startAsked) {
                provider.start(info.id)
                startAsked = true
            }
            delay(POLL_MS)
            info = provider.info(info.id)
            current = info
        }
        info
    }

    /** The known sandbox if it still exists and is not in a failed state; null means make a new one. */
    private suspend fun knownOrNull(provider: SandboxProvider, id: String): SandboxInfo? =
        try {
            provider.info(id).takeUnless { it.state in DEAD_STATES }
        } catch (e: SandboxFailure) {
            if (e.error is ToolError.NotFound) null else throw e
        }

    /**
     * Stops the conversation's sandbox and forgets it. Best effort: a failed stop is not shown,
     * because Daytona's own idle timer stops the sandbox anyway.
     */
    suspend fun end() {
        val id = lock.withLock {
            val known = current
            current = null
            known?.id
        } ?: return
        try {
            provider().stop(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left to Daytona's auto-stop. Nothing the user can act on here.
        }
    }

    private companion object {
        const val STARTED = "started"
        const val POLL_MS = 2_000L
        const val START_TIMEOUT_MS = 120_000L

        /** States Daytona will not recover from on its own. */
        val DEAD_STATES = setOf("error", "build_failed", "destroyed", "destroying")

        /** States a command can start from. */
        val STOPPED_STATES = setOf("stopped", "paused", "archived")
    }
}
