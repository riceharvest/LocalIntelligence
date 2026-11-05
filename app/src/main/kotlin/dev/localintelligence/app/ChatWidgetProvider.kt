package dev.localintelligence.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * The home-screen search bar: tap the bar to type in the chat, tap the mic to talk.
 *
 * WHY IT DOES NO WORK: [onUpdate] only builds two click targets and hands them to
 * the launcher. It does not touch the container, the model or the network, so a
 * widget on the home screen costs nothing until someone taps it. The widget info
 * sets updatePeriodMillis to 0, so nothing redraws it on a timer either.
 *
 * WHY DISTINCT REQUEST CODES: a PendingIntent's identity ignores extras. The bar
 * and the mic both open MainActivity, so without different request codes the
 * system would hand back one PendingIntent for both, and the mic would type.
 */
class ChatWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_chat_search)
        views.setOnClickPendingIntent(
            R.id.widget_search_bar,
            activity(context, REQUEST_TYPE, LauncherEntries.typeInChat(context)),
        )
        views.setOnClickPendingIntent(
            R.id.widget_mic,
            activity(context, REQUEST_VOICE, LauncherEntries.voice(context)),
        )
        // The bar's text is a hint, so TalkBack gets a sentence that says what a tap does.
        views.setContentDescription(R.id.widget_search_bar, context.getString(R.string.widget_search_description))
        views.setContentDescription(R.id.widget_mic, context.getString(R.string.widget_mic_description))
        return views
    }

    private fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val REQUEST_TYPE = 1
        const val REQUEST_VOICE = 2
    }
}
