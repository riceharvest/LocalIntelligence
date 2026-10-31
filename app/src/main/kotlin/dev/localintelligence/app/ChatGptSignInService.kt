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

/**
 * A foreground service that exists only while a ChatGPT sign-in waits for the browser.
 *
 * WHY: the browser is in front during sign-in, so the app is in the background, and some
 * phone makers kill background apps, which would drop the loopback listener and fail the
 * sign-in. The service keeps the process alive. The listener itself runs in the app's own
 * coroutine, not here: this service has no sign-in logic.
 *
 * Its type is `shortService` on API 35 and up, where Android allows a short foreground
 * service without a permission, and `dataSync` below that. Android can stop a short service
 * after about three minutes; [onTimeout] then removes the notification, and the sign-in
 * still completes while the process is alive.
 */
class ChatGptSignInService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopForegroundAndSelf()
            else -> startForegroundNow()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForegroundAndSelf()
    }

    private fun startForegroundNow() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Signing in to ChatGPT")
            .setContentText("Finish sign-in in your browser, then come back.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityIntent())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun stopForegroundAndSelf() {
        // STOP_FOREGROUND_REMOVE: a detached notification would outlive the service.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "dev.localintelligence.app.CHATGPT_SIGNIN_START"
        const val ACTION_STOP = "dev.localintelligence.app.CHATGPT_SIGNIN_STOP"

        private const val CHANNEL_ID = "chatgpt_signin"
        private const val CHANNEL_NAME = "ChatGPT sign-in"
        private const val NOTIFICATION_ID = 44

        /** Starts the service in the foreground, from the screen the user tapped Continue with ChatGPT on. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChatGptSignInService::class.java).setAction(ACTION_START),
            )
        }

        /** Stops it. A plain startService, because a startForegroundService would need a startForeground this stop never makes. */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, ChatGptSignInService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
