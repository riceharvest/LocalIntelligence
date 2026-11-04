package dev.localintelligence.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.localintelligence.android.gateway.localserver.LocalRun
import dev.localintelligence.android.gateway.localserver.LocalServer
import dev.localintelligence.android.gateway.localserver.LocalServerEngine
import dev.localintelligence.android.gateway.localserver.LocalServerReveal
import dev.localintelligence.android.gateway.localserver.LocalServerSettings
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.execution.RunGate
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.StreamingModelBackend
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import java.io.IOException
import java.security.SecureRandom

/**
 * Serves the loaded model to other apps on this phone, while the local-server plugin is on.
 *
 * ```
 * plugin on + Technical -> token made if missing -> service starts -> LocalServer listens on 127.0.0.1
 * plugin off or Simple  -> service stops -> the port closes and any request in flight is cancelled
 * ```
 *
 * ## Why a foreground service
 *
 * A server that stops when the user leaves the app answers nothing. The service is `dataSync`, the
 * same type as the Telegram gateway, and its notification says the server is on, so the user can see
 * that other apps can reach the model. Android 15+ caps `dataSync` at six hours a day; when that limit
 * is hit [onTimeout] records why and stops, rather than being killed.
 *
 * ## Why the token is made here
 *
 * The token is made the moment the server is switched on, and only then. It is stored encrypted like
 * every other secret, and the Settings panel shows it once, from memory, with a Copy button.
 */
class LocalServerService : Service() {

    private lateinit var container: AppContainer
    private var server: LocalServer? = null

    /** True between startForeground and stopForeground, so a stray status update never posts a notification. */
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        container = (application as LocalIntelligenceApp).container
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, every time. A start that does not reach startForeground within five seconds
        // is a crash, whatever the server then decides.
        startForegroundNow()
        if (!wanted(container)) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (server == null) {
            val next = LocalServer(
                engine = container.localServer,
                // Read per request: Regenerate cuts off the old token without a restart.
                expectedToken = { container.settings[LocalServerSettings.TOKEN] },
            )
            try {
                next.start()
                server = next
                setError("")
            } catch (e: IOException) {
                setError(getString(R.string.local_server_port_in_use, LocalServerSettings.PORT))
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        // Sticky: if the system drops the service while the user still wants it, Android restarts it.
        // wanted() above stops it again if the user has since switched it off.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15+: the six-hour daily limit for `dataSync`. Say why, then stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        setError(getString(R.string.local_server_timeout_error))
        server?.stop()
        server = null
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun setError(text: String) {
        if (container.settings[LocalServerSettings.LAST_ERROR] != text) {
            container.settings.set(LocalServerSettings.LAST_ERROR, text)
        }
    }

    private fun startForegroundNow() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
        foreground = true
    }

    private fun stopForegroundAndSelf() {
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(R.string.local_server_notification_text)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_local_server), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 50
        private const val CHANNEL_ID = "local_server"

        /** How often [claimForChat] looks again for the gate, and for how many tries. About one second in all. */
        private const val PREEMPT_POLLS = 20
        private const val PREEMPT_POLL_MS = 50L

        /** The switch is on and the app is in Technical mode. The token is checked by [wanted]. */
        fun enabledInTechnical(container: AppContainer): Boolean {
            val settings = container.settings
            return settings.isPluginEnabled(container.localServerPlugin) && settings.mode == SettingsMode.TECHNICAL
        }

        /** Whether the server should listen now: switched on, Technical, and a token exists. */
        fun wanted(container: AppContainer): Boolean =
            enabledInTechnical(container) && container.settings.isSecretSet(LocalServerSettings.TOKEN)

        /**
         * Starts the service when it is wanted and stops it when not. Called at launch and whenever
         * [enabledInTechnical] changes. A start the platform refuses (a background start on Android 12+)
         * is recorded in settings instead of crashing the app.
         */
        fun sync(context: Context, container: AppContainer) {
            if (enabledInTechnical(container) && !container.settings.isSecretSet(LocalServerSettings.TOKEN)) {
                regenerateToken(context, container.settings)
            }
            val intent = Intent(context, LocalServerService::class.java)
            if (!wanted(container)) {
                context.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                container.settings.set(
                    LocalServerSettings.LAST_ERROR,
                    context.getString(R.string.local_server_start_refused),
                )
            }
        }

        /**
         * Makes a new token from 32 random bytes, stores it encrypted, and keeps it in memory for the
         * panel to show once. The old token stops working on the next request.
         */
        fun regenerateToken(context: Context, store: SettingsStore): String? {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            if (!store.setSecret(LocalServerSettings.TOKEN, token)) {
                LocalServerReveal.token = null
                store.set(LocalServerSettings.LAST_ERROR, context.getString(R.string.local_server_token_store_failed))
                return null
            }
            LocalServerReveal.token = token
            return token
        }

        /**
         * The run claim for a chat message typed in the app. A chat run takes priority over the server:
         * if the server holds the model, its request is cancelled and the chat waits briefly for the gate.
         * Scheduled and Telegram runs do not come here, so they are still refused while a request runs.
         *
         * The wait is a short poll on the main thread, about one second at most. A cancelled llama
         * decode stops at the next token, so the wait is normally a few polls.
         */
        fun claimForChat(container: AppContainer): RunGate.Claim? {
            container.runGate.tryClaim()?.let { return it }
            val engine = container.localServer
            if (!engine.generating) return null
            engine.cancelInFlight()
            repeat(PREEMPT_POLLS) {
                Thread.sleep(PREEMPT_POLL_MS)
                container.runGate.tryClaim()?.let { return it }
            }
            return null
        }
    }
}

/**
 * The app's side of the local server: one request is one run of the loaded model, on the messages it
 * sent and nothing else. No tools, no agent loop, no memory and no chat history are involved.
 *
 * It claims the process-wide [RunGate] for the whole request, so a chat or scheduled run cannot start a
 * second decode into the same native context. A request never starts a model load: with no model
 * resident it answers [LocalRun.NoModel].
 */
class LocalServerRuns(private val container: AppContainer) : LocalServerEngine {

    /** True while this server holds the run gate for a request. Read by [LocalServerService.claimForChat]. */
    @Volatile
    var generating: Boolean = false
        private set

    @Volatile
    private var backend: ModelBackend? = null

    /** Set by a cancel. Checked before the decode starts, so a cancel that lands first is not lost. */
    @Volatile
    private var cancelled: Boolean = false

    override fun modelId(): String? {
        val availability = container.modelAvailability.current
        if (!availability.canRun) return null
        return (availability as? ModelAvailability.Ready)?.displayName ?: "local"
    }

    override suspend fun run(
        messages: List<ChatMessage>,
        params: SamplingParams,
        onStarted: () -> Unit,
        onToken: (String) -> Unit,
    ): LocalRun {
        val claim = container.runGate.tryClaim() ?: return LocalRun.Busy
        claim.use {
            if (!container.modelAvailability.current.canRun) return LocalRun.NoModel
            val model = container.residentBackend()
            cancelled = false
            generating = true
            backend = model
            try {
                onStarted()
                if (cancelled) return LocalRun.Failed
                val request = GenerationRequest(
                    messages = messages,
                    params = params,
                    // The server's requests must not touch the prefix state the chat reuses between turns.
                    reusePrefix = false,
                )
                val result = (model as? StreamingModelBackend)
                    ?.generateStreaming(request, onToken)
                    ?: model.generate(request)
                return when (result.stopReason) {
                    StopReason.COMPLETED -> LocalRun.Done(result.text, result.promptTokens, result.completionTokens, length = false)
                    StopReason.MAX_TOKENS -> LocalRun.Done(result.text, result.promptTokens, result.completionTokens, length = true)
                    StopReason.CANCELLED, StopReason.ERROR -> LocalRun.Failed
                }
            } finally {
                backend = null
                generating = false
            }
        }
    }

    override fun cancelInFlight() {
        cancelled = true
        backend?.cancel()
    }
}
