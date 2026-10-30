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
import dev.localintelligence.core.hub.DownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A foreground service that exists only while a model download is running.
 *
 * ```
 * download starts -> service starts -> download ends -> service stops
 * ```
 *
 * WHY: when the app leaves the foreground, Android can cut a background app's
 * network or kill its process under memory pressure, and a 2 GB transfer then
 * ends with nothing shown to the user. A `dataSync` foreground service keeps the
 * process running while the transfer goes on, and its notification shows the
 * progress and a Cancel button.
 *
 * The transfer itself runs in [HubTransfers], not here. This service only keeps
 * the process alive and shows the notification, so it has no download logic of
 * its own. It stops itself when [HubTransfers] reports the end, and it is started
 * with START_NOT_STICKY for the same reason as [ExecutionService]: a download the
 * system killed must not silently restart.
 *
 * The manifest entry needs `foregroundServiceType="dataSync"`, which matches the
 * type passed to startForeground below. The permissions it needs are already
 * declared for [ExecutionService].
 */
class DownloadService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private lateinit var transfers: HubTransfers
    private var watcher: Job? = null

    /**
     * True while the notification is the foreground one. Guards the progress updates,
     * which can arrive after the service has removed its notification: without it, a
     * late update would post a notification that nothing removes.
     */
    private var showing = false
    private val lock = Any()

    override fun onCreate() {
        super.onCreate()
        transfers = (application as LocalIntelligenceApp).container.hubTransfers
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDownloadForeground()
            // The Cancel button only exists while a transfer runs, so there is always something to cancel.
            // The service stops when the transfer reports its end, which is what this cancel leads to.
            ACTION_CANCEL -> transfers.cancel()
            ACTION_STOP -> {
                intent.getStringExtra(EXTRA_RESULT)?.let { postResult(it) }
                stopForegroundAndSelf()
            }
            else -> stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDownloadForeground() {
        // Post the notification before anything else: the foreground deadline does not wait for the transfer.
        val notification = notificationFor(transfers.transfer.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
        synchronized(lock) { showing = true }
        watchProgress()
    }

    /** Re-posts the progress notification each time the transfer reports a change. */
    private fun watchProgress() {
        if (watcher?.isActive == true) return
        watcher = serviceScope.launch {
            transfers.transfer.collect { state ->
                if (state == null || !state.running) return@collect
                synchronized(lock) {
                    if (!showing) return@synchronized
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notificationFor(state))
                }
            }
        }
    }

    private fun notificationFor(state: HubTransfers.Transfer?): Notification {
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progress = state?.progress
        val (percent, text) = when (progress) {
            is DownloadProgress.InProgress -> {
                val pct = if (progress.totalBytes > 0L) {
                    (progress.bytesDownloaded * 100 / progress.totalBytes).toInt().coerceIn(0, 100)
                } else {
                    null
                }
                pct to (state.fileName + (pct?.let { ", $it%" } ?: ""))
            }
            else -> null to (state?.fileName ?: "Starting")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading a model")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            // Indeterminate until the first progress event, or when the server did not say the size.
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityIntent())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    /** Posts the outcome once the transfer has ended, on its own channel so it is not swallowed by the progress one. */
    private fun postResult(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        // Off when the user has disabled notifications; the download's state is still in the app.
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle("Model download")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun stopForegroundAndSelf() {
        synchronized(lock) {
            showing = false
            // STOP_FOREGROUND_REMOVE, not DETACH: a detached notification survives stopSelf() and lingers.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // LOW: progress is not something to interrupt for. The result gets its own channel, so it can be silenced without silencing progress.
        val progress = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        val results = NotificationChannel(RESULT_CHANNEL_ID, RESULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(progress)
        manager.createNotificationChannel(results)
    }

    companion object {
        const val ACTION_START = "dev.localintelligence.app.DOWNLOAD_START"
        const val ACTION_CANCEL = "dev.localintelligence.app.DOWNLOAD_CANCEL"
        const val ACTION_STOP = "dev.localintelligence.app.DOWNLOAD_STOP"
        private const val EXTRA_RESULT = "result"

        /** Starts the service in the foreground. Called when a transfer starts, from the foreground Hub screen. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(ACTION_START),
            )
        }

        /**
         * Stops the service, posting [result] first when there is one. A plain startService is used rather than
         * startForegroundService, which would require a startForeground call this stop never makes.
         */
        fun stop(context: Context, result: String?) {
            runCatching {
                context.startService(
                    Intent(context, DownloadService::class.java)
                        .setAction(ACTION_STOP)
                        .putExtra(EXTRA_RESULT, result),
                )
            }
        }

        private const val CHANNEL_ID = "downloads"
        private const val CHANNEL_NAME = "Model downloads"
        private const val NOTIFICATION_ID = 42
        private const val RESULT_CHANNEL_ID = "download_results"
        private const val RESULT_CHANNEL_NAME = "Model download results"
        private const val RESULT_NOTIFICATION_ID = 43
    }
}
