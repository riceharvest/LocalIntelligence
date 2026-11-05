package dev.localintelligence.app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * The one place that knows how the launcher and the home-screen widget open the app.
 *
 * WHY ONE FILE: the widget and the dynamic shortcuts must agree on what "New chat"
 * and "Voice" mean. Two copies of the intent would drift, and a drift here shows
 * up as a shortcut that opens the wrong thing with no error.
 *
 * Every entry is an ACTION_VIEW aimed at [MainActivity] with one extra, and
 * [MainActivity] reads the extra in its launch handler. Nothing here loads the
 * model: an entry only starts the activity, and the activity's own gate waits
 * for the app lock and the restore before it does anything.
 */
object LauncherEntries {

    /** Dynamic shortcut ids. The static "scheduled-tasks" shortcut in res/xml/shortcuts.xml keeps its own id. */
    const val SHORTCUT_NEW_CHAT = "new-chat"
    const val SHORTCUT_VOICE = "voice"

    /** Opens the chat with the composer ready for typing. */
    fun typeInChat(context: Context): Intent =
        entry(context).putExtra(MainActivity.EXTRA_FOCUS_COMPOSER, true)

    /** Starts a hands-free voice session in the chat. */
    fun voice(context: Context): Intent =
        entry(context).putExtra(MainActivity.EXTRA_START_HANDS_FREE, true)

    /** Clears the chat on screen for a new conversation. Older chats are kept. */
    fun newChat(context: Context): Intent =
        entry(context).putExtra(MainActivity.EXTRA_NEW_CHAT, true)

    /**
     * Replaces the dynamic shortcuts with New chat and Voice. The static
     * scheduled-tasks shortcut stays as it is. Called on every launch, so a
     * language change shows up in the labels the next time the app opens.
     */
    fun publishShortcuts(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        manager.setDynamicShortcuts(
            listOf(
                ShortcutInfo.Builder(context, SHORTCUT_NEW_CHAT)
                    .setShortLabel(context.getString(R.string.shortcut_new_chat_short))
                    .setLongLabel(context.getString(R.string.shortcut_new_chat_long))
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_new_chat))
                    .setIntent(newChat(context))
                    .build(),
                ShortcutInfo.Builder(context, SHORTCUT_VOICE)
                    .setShortLabel(context.getString(R.string.shortcut_voice_short))
                    .setLongLabel(context.getString(R.string.shortcut_voice_long))
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_voice))
                    .setIntent(voice(context))
                    .build(),
            ),
        )
    }

    /**
     * The same flags the Quick Settings tile uses: a launch from outside the app
     * starts a fresh task, and an existing [MainActivity] is reused rather than stacked.
     */
    private fun entry(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
