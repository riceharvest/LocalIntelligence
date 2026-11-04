package dev.localintelligence.app

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
import dev.localintelligence.app.R

/**
 * A foreground service that exists only while an audio file is being transcribed.
 *
 * ```
 * transcribe starts -> service starts -> last chunk ends (or Stop) -> service stops
 * ```
 *
 * WHY: the transcription runs in [dev.localintelligence.app.ui.ChatViewModel], one generation per
 * 30 s chunk. A long recording takes minutes, and once the app leaves the foreground Android can
 * freeze or kill its process, so the transcript stops half-way. This service keeps the process
 * running for the length of the run, the same way [DownloadService] keeps a model download alive.
 *
 * The service holds no transcription logic. It only shows the notification and keeps the process
 * up. It is started from the foreground chat screen, which Android requires for a foreground start.
 * The notification has no button: Stop is in the chat, where the transcript row is.
 *
 * The manifest entry needs `foregroundServiceType="dataSync"`, matching the type passed to
 * startForeground below.
 */
class TranscriptionService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTranscriptionForeground()
            else -> stopForegroundAndSelf()
        }
        // START_NOT_STICKY: a transcription the system killed must not restart by itself. It
        // is a user-started run, and nothing would be left to finish it.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTranscriptionForeground() {
        // Post the notification before anything else: the foreground deadline does not wait for the decode.
        val notification = notification()
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
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.transcribing_audio_file))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun stopForegroundAndSelf() {
        // STOP_FOREGROUND_REMOVE: a detached notification would linger after stopSelf.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // LOW: a progress indicator, not something to interrupt for.
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.transcription_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "dev.localintelligence.app.TRANSCRIBE_START"
        const val ACTION_STOP = "dev.localintelligence.app.TRANSCRIBE_STOP"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 44

        /** Starts the service in the foreground. Called from the foreground chat screen, when a transcription starts. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscriptionService::class.java).setAction(ACTION_START),
            )
        }

        /** Stops the service once the transcription has ended. A plain startService, as the start is already foreground. */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, TranscriptionService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
