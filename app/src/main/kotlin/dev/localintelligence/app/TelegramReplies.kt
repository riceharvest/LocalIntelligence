package dev.localintelligence.app

import dev.localintelligence.android.gateway.telegram.TelegramApi
import dev.localintelligence.android.gateway.telegram.TelegramSettings
import dev.localintelligence.android.gateway.telegram.splitForTelegram
import dev.localintelligence.app.execution.ScheduledRunReporter
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.agent.StepTrace
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Sends replies to Telegram and words the outcome of a run for its chat.
 *
 * ## Why a process-wide scope
 *
 * A reply is sent when a run ends, and the run's service is stopping at that moment,
 * so its own scope may be cancelled a line later. The send is best effort: if the
 * process dies first, the reply is lost and the run's answer is still on the phone.
 */
internal object TelegramReplies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val EMPTY_ANSWER = "(The agent gave an empty answer.)"

    /** Sends [text] to [chatId] in as many messages as Telegram's limit needs. Never throws. */
    fun send(settings: SettingsStore, chatId: Long, text: String) {
        val token = settings.stored(TelegramSettings.TOKEN)
        if (token.isBlank()) return
        val parts = splitForTelegram(text.ifBlank { EMPTY_ANSWER })
        scope.launch {
            try {
                val api = TelegramApi()
                for (part in parts) api.sendMessage(token, chatId, part)
            } catch (e: IOException) {
                settings.set(TelegramSettings.LAST_ERROR, "Could not send a reply: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * What a finished run says in its chat. An answer is sent as the agent wrote it. Anything
     * else reuses the sentence the scheduled-task result already uses, without its machine prefix.
     */
    fun outcomeText(
        outcome: RunOutcome,
        trace: List<StepTrace>,
        readiness: ModelAvailability?,
        approvalDeclined: Boolean,
    ): String {
        val body = when (outcome) {
            is RunOutcome.Answer -> outcome.text.trim()
            else -> ScheduledRunReporter.notificationBody(
                ScheduledRunReporter.describe(outcome, trace, readiness),
            )
        }
        return if (approvalDeclined) {
            body + "\n\nSome actions needed your approval on the phone, so they were skipped. " +
                "Approve them there and send the request again."
        } else {
            body
        }
    }
}
