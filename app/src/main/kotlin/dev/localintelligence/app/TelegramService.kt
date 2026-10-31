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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.localintelligence.android.gateway.telegram.TelegramApi
import dev.localintelligence.android.gateway.telegram.TelegramChat
import dev.localintelligence.android.gateway.telegram.TelegramChats
import dev.localintelligence.android.gateway.telegram.TelegramSettings
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Long-polls Telegram while the plugin is on, and turns messages from linked chats into runs.
 *
 * ```
 * plugin on + Technical + token set -> service starts -> poll getUpdates
 *   linked chat sends text   -> ExecutionService run, reply sent when it ends
 *   unlinked chat sends /start while the link window is open -> offered in settings
 *   plugin off / token cleared -> poll loop ends -> service stops
 * ```
 *
 * ## Why a foreground service
 *
 * Android stops a plain background service within minutes, and then no message
 * would be read. The service is `dataSync`, the same type the run and download
 * services use, and the notification says Telegram is connected so the user can
 * see it is running. Android 15+ caps `dataSync` at six hours in any day; when
 * that limit is hit [onTimeout] records why and stops, rather than being killed.
 *
 * ## Why the poll offset is kept
 *
 * Telegram redelivers every update it has not been told is received. The offset
 * is written after each update, so a restart never runs the same message twice.
 * Running a phone action twice because the process restarted is the failure this
 * avoids.
 */
class TelegramService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val api = TelegramApi()
    private lateinit var container: AppContainer
    private lateinit var settings: SettingsStore
    private var poll: Job? = null

    /** True between startForeground and stopForeground, so a status update never posts a stray notification. */
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        container = (application as LocalIntelligenceApp).container
        settings = container.settings
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, every time. A start that does not reach startForeground
        // within five seconds is a crash, whatever the poller then decides.
        startForegroundNow()
        if (!wanted(container)) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (poll?.isActive != true) {
            poll = scope.launch { pollLoop() }
        }
        // Sticky: if the system drops the service while the user still wants it, Android
        // restarts it. The check above stops it again if the user has since switched it off.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15+: the six-hour daily limit for `dataSync`. Say why, then stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        setError("Android stopped Telegram after its six-hour background limit. Switch Telegram off and on to reconnect.")
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        poll?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        var backoffMs = 0L
        while (coroutineContext.isActive && wanted(container)) {
            val token = settings.stored(TelegramSettings.TOKEN)
            try {
                val updates = api.getUpdates(token, offset())
                backoffMs = 0L
                setError("")
                for (update in updates) {
                    // The offset moves on even when handling fails: a message that
                    // could not be handled must not come back and be handled again.
                    try {
                        handle(update)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setError("Could not handle a message: ${e.javaClass.simpleName}")
                    }
                    settings.set(TelegramSettings.OFFSET, (update.updateId + 1).toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramApi.Failure) {
                // 401 and 404 mean the token itself is wrong. Retrying cannot fix that, so the
                // loop stops and the panel says what to do.
                if (e.code == 401 || e.code == 404) {
                    setError("Telegram does not accept this token. Paste the token from @BotFather again.")
                    break
                }
                setError(e.message ?: "Telegram refused the call")
                backoffMs = nextBackoff(backoffMs)
                delay(backoffMs)
            } catch (e: IOException) {
                setError("No connection to Telegram: ${e.javaClass.simpleName}")
                backoffMs = nextBackoff(backoffMs)
                delay(backoffMs)
            }
        }
        stopForegroundAndSelf()
    }

    /** One incoming message. Only linked chats are answered; anything else is offered for approval or ignored. */
    private fun handle(update: TelegramApi.Incoming) {
        if (!TelegramChats.isLinked(settings.stored(TelegramSettings.LINKED), update.chatId)) {
            offerLink(update)
            return
        }
        if (update.isStart) {
            TelegramReplies.send(settings, update.chatId, WELCOME)
            return
        }
        val text = update.text
        val nowSeconds = System.currentTimeMillis() / 1000
        when {
            // Text only for now. A photo or voice note gets this reply and nothing runs.
            text == null -> TelegramReplies.send(settings, update.chatId, ONLY_TEXT)
            // A message from before the poller reconnected: do not act on a stale request silently.
            nowSeconds - update.date > STALE_SECONDS ->
                TelegramReplies.send(settings, update.chatId, STALE)
            text.isBlank() -> Unit
            else -> ExecutionService.start(applicationContext, text, replyChatId = update.chatId)
        }
    }

    /**
     * Shows a /start from an unlinked chat in settings for the owner to approve. Only while the
     * link window is open, and only for a private chat: a group would let every member of it
     * give the phone orders, so a group is never offered.
     */
    private fun offerLink(update: TelegramApi.Incoming) {
        if (!update.isStart || update.chatType != "private") return
        val until = settings.stored(TelegramSettings.LINK_OPEN_UNTIL).toLongOrNull() ?: 0L
        if (System.currentTimeMillis() > until) return
        settings.set(
            TelegramSettings.PENDING,
            TelegramChats.encodeOne(TelegramChat(update.chatId, update.chatName)),
        )
        TelegramReplies.send(settings, update.chatId, WAITING_FOR_APPROVAL)
    }

    private fun offset(): Long = settings.stored(TelegramSettings.OFFSET).toLongOrNull() ?: 0L

    private fun setError(text: String) {
        if (settings.stored(TelegramSettings.LAST_ERROR) != text) {
            settings.set(TelegramSettings.LAST_ERROR, text)
            if (foreground) updateNotification()
        }
    }

    private fun statusText(): String {
        val error = settings.stored(TelegramSettings.LAST_ERROR)
        return if (error.isBlank()) CONNECTED_TEXT else "Telegram: " + error.take(120)
    }

    private fun startForegroundNow() {
        val notification = notification(statusText())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
        foreground = true
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(statusText()))
    }

    private fun stopForegroundAndSelf() {
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 46
        private const val CHANNEL_ID = "telegram"
        private const val CHANNEL_NAME = "Telegram"
        private const val CONNECTED_TEXT = "Telegram is connected"

        /** A message older than this is not run. */
        private const val STALE_SECONDS = 60 * 60L

        private const val MAX_BACKOFF_MS = 5 * 60_000L

        private const val WELCOME = "Connected. Send a message and this phone's agent will answer it."
        private const val ONLY_TEXT = "Only text is supported for now. Send your request as text."
        private const val STALE = "That message is over an hour old, so it was not run. Send it again."
        private const val WAITING_FOR_APPROVAL =
            "Waiting for the owner to approve this chat in LocalIntelligence."

        /** 5 s, 10 s, 20 s ... capped at five minutes. */
        private fun nextBackoff(previous: Long): Long =
            if (previous == 0L) 5_000L else (previous * 2).coerceAtMost(MAX_BACKOFF_MS)

        /**
         * Whether the gateway should run now: the plugin is on, the app is in Technical mode
         * (a token is a Technical setting), and a token is set. Anything else means no socket.
         */
        fun wanted(container: AppContainer): Boolean {
            val settings = container.settings
            return settings.isPluginEnabled(container.telegramPlugin) &&
                settings.mode == SettingsMode.TECHNICAL &&
                settings.isSecretSet(TelegramSettings.TOKEN)
        }

        /**
         * Starts the service when it is wanted and stops it when not. Called at launch and whenever
         * the wanted state changes. A start the platform refuses (a background start on Android 12+)
         * is recorded in settings instead of crashing the app.
         */
        fun sync(context: Context, container: AppContainer) {
            val intent = Intent(context, TelegramService::class.java)
            if (!wanted(container)) {
                context.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                container.settings.set(
                    TelegramSettings.LAST_ERROR,
                    "Android would not start Telegram in the background. Open LocalIntelligence to connect.",
                )
            }
        }
    }
}
