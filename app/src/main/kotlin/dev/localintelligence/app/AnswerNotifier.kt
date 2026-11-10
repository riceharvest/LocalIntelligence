package dev.localintelligence.app

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * Tells the person a reply is ready when it finished while the app was out of sight (they pressed Home, or the
 * screen went off, during a long reply). Without it the only sign is opening the app again to look.
 *
 * WHY its own visible-activity count: no other piece of the app knows whether a screen is showing, and a reply
 * that ends with the chat on screen needs no notification. Counts started activities, so a rotation (stop then
 * start of the same activity) never reads as "away" for longer than one frame, and a notification is only
 * posted when the count is zero.
 */
object AnswerNotifier {

    private const val CHANNEL_ID = "answer_ready"
    private const val NOTIFICATION_ID = 7301

    @Volatile
    private var started = 0

    /** Registers the activity counter. Called once from [LocalIntelligenceApp.onCreate]. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++; clear(activity) }
            override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** True when no screen of the app is showing. */
    val isAway: Boolean get() = started == 0

    /** Posts "your reply is ready" when the app is away and notifications are allowed. Never throws. */
    fun replyReady(context: Context, answer: String) {
        if (!isAway) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_answers), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = answer.replace(Regex("\\s+"), " ").trim().take(200)
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.notif_answer_ready))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }

    /** Takes the notification away when the person is back in the app. */
    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }
}
