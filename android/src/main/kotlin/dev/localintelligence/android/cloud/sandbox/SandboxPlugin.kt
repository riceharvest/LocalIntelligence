package dev.localintelligence.android.cloud.sandbox

import android.content.Context
import dev.localintelligence.android.tools.requireCatalogueAgreement
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.android.cloud.CredentialSource
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
// A computer the remote model can run commands in. The user picks one provider in Settings:
//   - Daytona and Vercel Sandbox: a cloud sandbox on the user's own account (a key or token).
//   - My SSH server: the user's own machine, reached over SSH.
//   - Termux on this phone: free, runs through Termux's RUN_COMMAND intent.
//
// Off by default, and gated before anything is sent:
//   1. Technical mode only. Simple mode never offers these tools (every row here is Technical).
//   2. The plugin is switched on.
//   3. The selected provider has what it needs (see SandboxProviders.configured).
//   4. The remote chat model is the one answering this run (AppContainer.newController keeps
//      the sandbox.* tools out of every other run). WHY: a small on-device model is not trusted
//      to drive a computer that is billed to the user's account or that runs on the phone.
//
// One sandbox per conversation: it is created on first use, kept in memory for the session,
// and stopped (not deleted) by New chat. Termux and SSH have no sandbox to create or stop; the
// SSH server and the phone simply stay as they are.
// ===========================================================================

/** Name prefix of every sandbox tool. The run assembly uses it to keep these out of other runs. */
const val SANDBOX_TOOL_PREFIX: String = "sandbox."

/** The settings rows this plugin adds. Keys are prefixed with the plugin id. */
object SandboxSettings {
    const val PLUGIN_ID = "cloud.sandbox"

    /** Termux needs three steps, so its setup is spelled out here rather than in a separate row. */
    val PROVIDER = SettingDef.Choice(
        "cloud.sandbox.provider", "Sandbox provider",
        "Where the commands run. Daytona, Vercel Sandbox and My SSH server each need your own key, token or " +
            "address. Termux runs on this phone and needs: Termux installed; allow-external-apps=true in " +
            "~/.termux/termux.properties; and Run commands in Termux environment switched on under this app's " +
            "Additional permissions in Android Settings.",
        SettingSection.PLUGINS, default = DAYTONA_ID,
        options = listOf(
            SettingDef.Choice.Option(DAYTONA_ID, "Daytona"),
            SettingDef.Choice.Option(VERCEL_ID, "Vercel Sandbox"),
            SettingDef.Choice.Option(SSH_ID, "My SSH server"),
            SettingDef.Choice.Option(TERMUX_ID, "Termux on this phone"),
        ),
    )

    /** Not simple: only Technical mode shows it, and Simple mode reads it as blank, so no key means no tools. */
    val API_KEY = SettingDef.Secret(
        "cloud.sandbox.api_key", "Daytona API key",
        "Your own key from the Daytona dashboard, used when Daytona is the provider. Stored encrypted and never " +
            "shown back. Without one, the sandbox tools are not offered and nothing is sent.",
        SettingSection.PLUGINS,
    )

    val API_URL = SettingDef.Text(
        "cloud.sandbox.api_url", "Daytona API address",
        "Leave blank for Daytona's cloud. Your key is sent to this address, so change it only to " +
            "a Daytona server you run yourself. It must start with https://.",
        SettingSection.PLUGINS, default = DAYTONA_DEFAULT_API, maxLength = 200, blankMeansDefault = true,
    )

    val VERCEL_TOKEN = SettingDef.Secret(
        "cloud.sandbox.vercel_token", "Vercel access token",
        "An access token from your Vercel account settings. Stored encrypted and never shown back. " +
            "Used only when Vercel Sandbox is the provider.",
        SettingSection.PLUGINS,
    )

    val VERCEL_PROJECT = SettingDef.Text(
        "cloud.sandbox.vercel_project", "Vercel project",
        "The project ID or name the sandboxes belong to. Required with an access token.",
        SettingSection.PLUGINS, default = "", maxLength = 120,
    )

    val VERCEL_TEAM = SettingDef.Text(
        "cloud.sandbox.vercel_team", "Vercel team ID",
        "Only if the project belongs to a team. Leave blank for a personal account.",
        SettingSection.PLUGINS, default = "", maxLength = 120,
    )

    val SSH_HOST = SettingDef.Text(
        "cloud.sandbox.ssh_host", "SSH host",
        "The host name or IP address of your server. Connections go only to this server.",
        SettingSection.PLUGINS, default = "", maxLength = 200,
    )

    val SSH_PORT = SettingDef.IntValue(
        "cloud.sandbox.ssh_port", "SSH port",
        "The port the SSH server listens on. 22 unless your server uses another.",
        SettingSection.PLUGINS, default = 22, min = 1, max = 65535, step = 1,
    )

    val SSH_USER = SettingDef.Text(
        "cloud.sandbox.ssh_user", "SSH user",
        "The account to log in as. Commands run in its home folder.",
        SettingSection.PLUGINS, default = "", maxLength = 64,
    )

    val SSH_KEY = SettingDef.Secret(
        "cloud.sandbox.ssh_key", "SSH private key",
        "Paste the private key (OpenSSH or PEM) with no passphrase. Stored encrypted. Leave blank to use the password. " +
            "RSA and ECDSA keys work; Ed25519 keys are not tested on Android.",
        SettingSection.PLUGINS,
    )

    val SSH_PASSWORD = SettingDef.Secret(
        "cloud.sandbox.ssh_password", "SSH password",
        "Used when no private key is set. Stored encrypted and never shown back.",
        SettingSection.PLUGINS,
    )

    /**
     * The server's fingerprint, pinned on the first connect. A later change is refused. Clear the row
     * only after you have re-keyed the server yourself; the next connect pins the new key.
     */
    val SSH_HOST_KEY = SettingDef.Text(
        "cloud.sandbox.ssh_host_key", "SSH server fingerprint",
        "Pinned automatically on the first connect (SHA256 fingerprint). A different key from the server " +
            "is refused. Clear this only after you re-keyed the server yourself.",
        SettingSection.PLUGINS, default = "", maxLength = 300,
    )

    val IDLE_MINUTES = SettingDef.IntValue(
        "cloud.sandbox.idle_minutes", "Stop the sandbox after idle",
        "Minutes without a command before the sandbox is stopped. Daytona stops it when idle. Vercel has no idle " +
            "timer, so it stops this many minutes after the sandbox starts. A stopped sandbox keeps its files " +
            "and starts again on the next command. Ignored by Termux and My SSH server.",
        SettingSection.PLUGINS, default = 15, min = 5, max = 240, step = 5,
    )

    val all: List<SettingDef<*>> = listOf(
        PROVIDER, API_KEY, API_URL, VERCEL_TOKEN, VERCEL_PROJECT, VERCEL_TEAM,
        SSH_HOST, SSH_PORT, SSH_USER, SSH_KEY, SSH_PASSWORD, SSH_HOST_KEY, IDLE_MINUTES,
    )
}

/**
 * The one place a keyed provider reads its credential from. Today each value is the user's own key
 * from Settings. A managed key for Simple users would be read here, so the tools never change.
 */
internal class SandboxCredentials(private val reader: SettingsReader) {
    fun daytonaKey(): String = reader[SandboxSettings.API_KEY]
    fun vercelToken(): String = reader[SandboxSettings.VERCEL_TOKEN]
    fun sshKey(): String = reader[SandboxSettings.SSH_KEY]
    fun sshPassword(): String = reader[SandboxSettings.SSH_PASSWORD]
}

/** The one write this plugin makes: pinning an SSH server's fingerprint. SettingsStore implements it. */
interface SandboxSettingsWriter {
    fun <T : Any> set(def: SettingDef<T>, value: T)
}

/** Builds the provider a setting names, from the live settings, and says whether it has what it needs. */
internal class SandboxProviders(
    private val reader: SettingsReader,
    private val writer: SandboxSettingsWriter,
    private val context: Context,
) {
    private val credentials = SandboxCredentials(reader)

    /** The provider id the Choice setting holds now. */
    fun selected(): String = reader[SandboxSettings.PROVIDER]

    /** A provider by id. An unknown id is Daytona, which is what the Choice setting falls back to too. */
    fun build(id: String): SandboxProvider = when (id) {
        VERCEL_ID -> VercelProvider(
            token = credentials.vercelToken(),
            projectId = reader[SandboxSettings.VERCEL_PROJECT],
            teamId = reader[SandboxSettings.VERCEL_TEAM],
        )
        SSH_ID -> SshProvider(
            host = reader[SandboxSettings.SSH_HOST],
            port = reader[SandboxSettings.SSH_PORT],
            user = reader[SandboxSettings.SSH_USER],
            privateKey = credentials.sshKey(),
            password = credentials.sshPassword(),
            pinnedKey = { reader[SandboxSettings.SSH_HOST_KEY] },
            savePin = { value -> writer.set(SandboxSettings.SSH_HOST_KEY, value) },
        )
        TERMUX_ID -> TermuxProvider(context)
        else -> DaytonaProvider(
            apiBase = reader[SandboxSettings.API_URL],
            apiKey = credentials.daytonaKey(),
        )
    }

    /** True when the selected provider has what it needs. The tools are offered only then. */
    fun configured(): Boolean = when (selected()) {
        VERCEL_ID -> credentials.vercelToken().isNotBlank() && reader[SandboxSettings.VERCEL_PROJECT].isNotBlank()
        SSH_ID -> reader[SandboxSettings.SSH_HOST].isNotBlank() && reader[SandboxSettings.SSH_USER].isNotBlank() &&
            (credentials.sshKey().isNotBlank() || credentials.sshPassword().isNotBlank())
        TERMUX_ID -> termuxReady(context)
        else -> credentials.daytonaKey().isNotBlank()
    }
}

/**
 * Off by default. Offers `sandbox.run`, `sandbox.write_file` and `sandbox.read_file`, but only in
 * Technical mode with the selected provider configured. [reader] is the live store, so a change
 * applies at the next message.
 */
class SandboxPlugin(
    private val reader: SettingsReader,
    writer: SandboxSettingsWriter,
    context: Context,
) : AgentPlugin {
    private val providers = SandboxProviders(reader, writer, context)
    private val session = SandboxSession(providers, reader)

    override val id: String = SandboxSettings.PLUGIN_ID
    override val title: String = "Cloud sandbox"
    override val summary: String =
        "Run commands and files in a cloud sandbox, your own SSH server, or Termux on this phone. Technical mode " +
            "only, and only while a remote chat model answers. Cloud and SSH commands are sent to that service."
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
        if (reader.mode != SettingsMode.TECHNICAL || !providers.configured()) emptyList() else built

    /** Every tool this plugin ships, configured or not, for the startup catalogue check. */
    fun shippedTools(): List<AgentTool> = built

    /** New chat: the conversation's sandbox is stopped, not deleted. Best effort; see [SandboxSession.end]. */
    suspend fun endConversation() = session.end()
}

/**
 * The sandbox of the current conversation, created and started on demand.
 *
 * A [Mutex] serialises creation, so two tool calls in one run cannot each create a sandbox. The
 * id is held in memory only: a process restart forgets it, and the next call creates a new one.
 * The provider that made the sandbox is remembered with it, so a change of provider in Settings
 * stops the old sandbox with the provider that owns it, not with the new one.
 */
internal class SandboxSession(
    private val providers: SandboxProviders,
    private val reader: SettingsReader,
) {
    private val lock = Mutex()

    /** The sandbox this conversation uses, or null before the first call and after New chat. */
    private var current: SandboxInfo? = null

    /** The provider id that made [current]. */
    private var currentProvider: String? = null

    /** A running sandbox and the provider that holds it. Tools use both, so they always match. */
    class Ready(val provider: SandboxProvider, val info: SandboxInfo)

    /**
     * A running sandbox, creating, starting or waiting for it as needed.
     *
     * A sandbox that no longer exists, or is in a failed state, is replaced by a new one. A stopped
     * one is started. Anything still changing state is polled until it is started or the wait runs
     * out at [START_TIMEOUT_MS].
     */
    suspend fun ready(signal: CancellationSignal): Ready = lock.withLock {
        val chosen = providers.selected()
        if (current != null && currentProvider != chosen) {
            val old = current
            val oldProvider = currentProvider
            current = null
            currentProvider = null
            if (old != null) stopQuietly(oldProvider, old.id)
        }
        val provider = providers.build(chosen)
        val known = current?.let { knownOrNull(provider, it.id) }
        var info = known ?: provider.create(reader[SandboxSettings.IDLE_MINUTES])
        current = info
        currentProvider = chosen
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        var startAsked = false
        while (info.state != STARTED) {
            if (signal.isCancelled()) {
                throw SandboxFailure(ToolError.Cancelled("sandbox wait"), "Stopped waiting for the sandbox.")
            }
            if (info.state in DEAD_STATES) {
                current = null
                currentProvider = null
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
        Ready(provider, info)
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
     * because the provider's own idle timer stops the sandbox anyway.
     */
    suspend fun end() {
        val known = lock.withLock {
            val old = current
            val oldProvider = currentProvider
            current = null
            currentProvider = null
            old?.let { it to oldProvider }
        } ?: return
        stopQuietly(known.second, known.first.id)
    }

    /** Stops one sandbox with the provider that made it. Failures are not shown; see [end]. */
    private suspend fun stopQuietly(providerId: String?, id: String) {
        val provider = providers.build(providerId ?: return)
        try {
            provider.stop(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Left to the provider's own timer. Nothing the user can act on here.
        }
    }

    private companion object {
        const val STARTED = "started"
        const val POLL_MS = 2_000L
        const val START_TIMEOUT_MS = 120_000L

        /** States the provider will not recover from on its own. */
        val DEAD_STATES = setOf("error", "build_failed", "destroyed", "destroying")

        /** States a command can start from. */
        val STOPPED_STATES = setOf("stopped", "paused", "archived")
    }
}
