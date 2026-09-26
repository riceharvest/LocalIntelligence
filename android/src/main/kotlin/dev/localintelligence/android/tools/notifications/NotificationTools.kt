package dev.localintelligence.android.tools.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

// ===========================================================================
// notifications.* — read, reply, dismiss.
//
// THE PERMISSION IS NOT A RUNTIME PERMISSION.
//
// A NotificationListenerService is bound by the system only after a human
// walks into Settings > Notifications > Device & app notifications, finds this
// app, and flips a switch. There is no requestPermissions() call, no
// shouldShowRequestPermissionRationale(), and nothing an app can do to grant
// it to itself. The one thing the app CAN do is detect the absence and say so
// precisely, which is why [Notifications.notice] is a first-class part of this
// file rather than an afterthought: a tool that answers "0 notifications" when
// it simply cannot see any would be lying to the model, and the model would
// tell the user their phone is quiet.
//
// `notifications.reply` is EXTERNAL_COMMUNICATION, which means the runtime
// asks for confirmation before it runs. That is not ceremony. A RemoteInput is
// sent to a third-party app under the user's identity, and on Android 14+ a
// grant can be revoked at any moment. When the reply path is unavailable the
// tool returns Unavailable and says why; it never fabricates a success.
// ===========================================================================

/** Cap on how many notifications one observation will describe. */
internal const val DEFAULT_LIST_LIMIT: Int = 10
internal const val MAX_LIST_LIMIT: Int = 30

/** The settings screen the user has to open, named exactly. */
internal const val LISTENER_SETTINGS_ACTION: String =
    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

/** Characters kept per notification field. A long SMS is not a summary. */
internal const val MAX_TITLE_CHARS: Int = 120
internal const val MAX_BODY_CHARS: Int = 240

// ----------------------------------------------------------------------------
// Argument coercion
// ----------------------------------------------------------------------------

internal object NotificationArgs {

    fun coerceInt(
        raw: kotlinx.serialization.json.JsonElement?,
        fallback: Int,
        min: Int,
        max: Int,
    ): Int {
        val value = when (raw) {
            null, is JsonNull -> fallback
            is JsonPrimitive -> when {
                raw.isString -> raw.content.toIntOrNull() ?: raw.content.toDoubleOrNull()?.toInt()
                else -> raw.content.toDoubleOrNull()?.toInt()
            }
            else -> null
        } ?: fallback
        return value.coerceIn(min, max)
    }

    fun coerceString(raw: kotlinx.serialization.json.JsonElement?): String? =
        (raw as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun coerceBool(raw: kotlinx.serialization.json.JsonElement?, fallback: Boolean = false): Boolean =
        when (raw) {
            null, is JsonNull -> fallback
            is JsonPrimitive -> when (raw.content.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> fallback
            }
            else -> fallback
        }
}

// ----------------------------------------------------------------------------
// Observation building
// ----------------------------------------------------------------------------

/**
 * The one notification summary, in pure data.
 *
 * This is a projection of [StatusBarNotification] with every unbounded field
 * already clamped, so everything downstream of the platform — the formatter,
 * the search, the cap — is plain Kotlin and runs in a JVM test.
 */
internal data class NotificationSummary(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val isOngoing: Boolean,
    val canReply: Boolean,
    val postedAtMillis: Long,
) {
    fun searchableHaystack(): String =
        "$appLabel $title $body $packageName".lowercase()
}

/** Everything a model-visible string in this file can say. */
internal object Notifications {

    /**
     * Normalises one field of untrusted notification text.
     *
     * Two things happen here and both are load-bearing. C0/C1 control
     * characters are removed outright, and the rest is whitespace-collapsed:
     * a notification body is attacker-influenced (anyone can send you a
     * message) and a bare ESC byte inside an observation can drive whatever
     * renders it later. Collapsing newlines to spaces also stops a hostile
     * body from forging extra lines in a numbered list, which is the one
     * structural attack available against this observation.
     */
    fun clamp(value: String?): String =
        value
            ?.replace(CONTROL_CHARS, " ")
            ?.replace(CONTROL_RUNS, " ")
            ?.trim()
            ?.take(MAX_BODY_CHARS)
            ?: ""

    /**
     * The observation when the listener is not enabled. This string is the
     * entire UX of this feature for a user who has not set it up, so it names
     * the screen, the app, and the consequence — and promises nothing.
     */
    fun notEnabledNotice(): String =
        "Notification access is not enabled, so I cannot see any notifications. " +
            "Open Android Settings > Notifications > Device & app notifications, " +
            "find LocalIntelligence, and turn on notification access " +
            "(the same screen opens from the action $LISTENER_SETTINGS_ACTION). " +
            "I read nothing; nothing was changed. Tell the user this and stop — " +
            "retrying without the toggle will never work."

    fun notConnectedNotice(): String =
        "Notification access is enabled but the listener service is not connected yet. " +
            "It connects within a second or two of the phone waking; try again shortly, " +
            "and do not read anything as empty until then."

    fun listObservation(items: List<NotificationSummary>, totalActive: Int): String {
        if (items.isEmpty()) {
            return if (totalActive == 0) {
                "No notifications are currently showing on the device."
            } else {
                "No notifications matched the filter (of $totalActive active)."
            }
        }
        // Clamp HERE, not only in the projector. This is the last function
        // before untrusted, remote-sender-controlled text becomes an
        // observation, so it is the one place that must not depend on every
        // caller having remembered to sanitise. A raw newline in a body would
        // otherwise let a message forge an extra numbered line.
        val lines = items.mapIndexed { index, n ->
            val title = clamp(n.title)
            val body = clamp(n.body)
            val app = if (n.appLabel.isNotBlank()) clamp(n.appLabel) else n.packageName
            val head = if (title.isNotBlank()) "$app: $title" else app
            val tail = if (body.isNotBlank()) " — $body" else ""
            val flags = buildString {
                if (n.canReply) append(" [replyable]")
                if (n.isOngoing) append(" [ongoing]")
            }
            "${index + 1}. $head$tail$flags (key ${shortKey(n.key)}, app ${n.packageName})"
        }
        val header = if (items.size == totalActive) {
            "${items.size} active notification${if (items.size == 1) "" else "s"}:"
        } else {
            "${items.size} of $totalActive active notifications:"
        }
        return ObservationTruncator.truncate(
            buildString {
                append(header)
                append("\n")
                append(lines.joinToString("\n"))
            },
        )
    }

    private fun NotificationSummary.appLabelSafe(): String = appLabel.ifBlank { packageName }

    /**
     * A notification key looks like `0|com.whatsapp|2|null|10023`. It is long,
     * full of pipes, and hostile to both a 1B model and a JSON Schema enum. The
     * model is shown a short form and must echo it back; [keyMatches] accepts
     * the short form and the exact key.
     */
    fun shortKey(key: String): String {
        val parts = key.split('|')
        return if (parts.size >= 2) "${parts[1]}#${parts.last()}" else key.take(40)
    }

    fun keyMatches(short: String, candidates: List<NotificationSummary>): NotificationSummary? {
        if (short.isBlank()) return null
        val exact = candidates.firstOrNull { it.key == short }
        if (exact != null) return exact
        val lower = short.lowercase()
        return candidates.firstOrNull { shortKey(it.key).equals(lower, ignoreCase = true) }
    }

    /**
     * Why a reply is impossible, in the words a user can act on. Returning
     * `Unavailable` with this text is the honest answer; a fabricated
     * "Reply sent" is the one outcome this file must never produce.
     */
    fun notReplyableNotice(app: String): String =
        "Cannot reply to a $app notification. The app has not published a quick-reply " +
            "box on it (no RemoteInput), which means either the conversation is already " +
            "closed or $app only offers replies inside its own UI. Nothing was sent. " +
            "Either ask the user to reply in $app, or leave it."

    fun replySentNotice(app: String, text: String): String =
        "Reply handed to $app: \"${clampReply(text)}\". The messaging app owns delivery " +
            "from here; it may still ask the user to confirm."

    fun notFoundNotice(short: String): String =
        "No active notification matches the key \"$short\". It may have already been " +
            "dismissed. Run notifications.list again for the current keys, and do not " +
            "guess a key from an earlier list."

    fun dismissedNotice(app: String, title: String): String {
        val what = if (title.isNotBlank()) "$app ($title)" else app
        return "Dismissed the $what notification. The app can still post a new one."
    }

    fun dismissFailedNotice(app: String): String =
        "Asked $app to dismiss the notification but it is still showing. " +
            "Some apps (ongoing calls, media, alarms) block dismissal by design. " +
            "Do not retry in a loop."

    private fun clampReply(text: String): String =
        text.replace(CONTROL_CHARS, " ").replace(CONTROL_RUNS, " ").trim().take(160)

    private val CONTROL_RUNS = Regex("\\s+")

    /**
     * C0 and C1 control characters, minus the ones that are legitimate
     * whitespace and have already been collapsed. A notification body comes
     * from a remote sender, so an escape byte in it is a hostile input.
     */
    private val CONTROL_CHARS = Regex("[\\p{Cc}\\p{Cf}]")
}

// ----------------------------------------------------------------------------
// The listener service
// ----------------------------------------------------------------------------

/**
 * The platform listener. Small on purpose.
 *
 * `getActiveNotifications()` already gives the current snapshot synchronously,
 * so this class keeps no cache of its own: a cache here would be a second
 * source of truth that disagrees with the system after the first dismissal, and
 * on a phone the difference between "what the system says" and "what we
 * remembered" is the difference between correct and creepy.
 *
 * Binding itself is the permission check. `onListenerConnected` is only called
 * after the user granted access, so a non-null [connected] instance is proof
 * of the grant; the manifest flag and the bound service are two independent
 * facts and [isEnabledForPackage] distinguishes them.
 */
class LocalNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    companion object {
        /** The bound service, or null. Volatile because it is written on the main
         * thread by the platform and read from whichever thread the agent loop
         * happens to be on.
         */
        @Volatile
        @JvmStatic
        var connected: LocalNotificationListenerService? = null
            private set

        fun isConnected(): Boolean = connected != null

        /** The live snapshot, or null when the listener is not bound. */
        fun snapshotOrNull(): List<StatusBarNotification>? =
            connected?.activeNotifications?.toList()

        // ------------------------------------------------------------------ consent
        //
        // Binding is the permission, and nothing an app ships can grant it. These
        // three functions are the whole of what CAN be done about it, and they were
        // all missing: the constant naming the settings screen existed and was only
        // ever interpolated into a model-visible sentence, so the listener could
        // never be bound and the feature was unreachable from any code path.

        /**
         * True when this package is on the system's notification-listener allow
         * list — the grant, independent of whether the service happens to be bound
         * right now.
         *
         * ## Two routes, because minSdk is 26 and the good one is 31
         *
         * `NotificationManager.isNotificationListenerAccessGranted(ComponentName)`
         * is the correct API and is what this uses from API 31. Below that the
         * only way to ask is to read the
         * `enabled_notification_listeners` setting and compare component names
         * ourselves, so that is what the fallback does. The existing
         * [isNotificationListenerEnabledFor] is the pure half of that comparison
         * and now has a caller, which it did not before — it was written for this
         * and nothing ever wired it up.
         *
         * Never throws: a failure to ask is a "not granted", which is the state
         * every caller already handles.
         */
        @JvmStatic
        fun isGranted(context: Context): Boolean = try {
            val component = ComponentName(context, LocalNotificationListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager
                manager?.isNotificationListenerAccessGranted(component) == true
            } else {
                // API 26-30: the allow list is a colon-separated string of
                // flattened ComponentNames. Parsing is done here rather than in
                // the pure helper so the helper stays a set-membership test and
                // assertable without a Context.
                val raw = Settings.Secure.getString(
                    context.contentResolver,
                    ENABLED_NOTIFICATION_LISTENERS,
                ).orEmpty()
                val enabled = raw.split(':')
                    .mapNotNull { part ->
                        part.takeIf { it.isNotBlank() }
                            ?.let { ComponentName.unflattenFromString(it) }
                    }
                    .toSet()
                isNotificationListenerEnabledFor(
                    component.packageName,
                    enabled.mapTo(mutableSetOf()) { it.packageName },
                )
            }
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            // A malformed component string in the setting. Treated as "not
            // granted", which is the safe direction: the banner stays up.
            false
        }

        /**
         * The `Settings.Secure` key holding the allow list on API 26-30.
         *
         * A literal rather than a constant from the SDK: there is no public
         * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` field, only the string
         * value, and the platform has never changed it.
         */
        private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

        /**
         * The Intent that opens the notification-access screen.
         *
         * On API 30+ there is a per-app detail screen that lands the user directly
         * on this app's toggle; the list screen is the fallback, and on API 26-29
         * it is the only thing that exists.
         *
         * Never returns null. An Intent that cannot resolve is still returned and
         * still fails loudly at [startActivity], because a null here would invite
         * a caller to quietly do nothing — which is the exact failure this whole
         * path exists to remove.
         */
        @JvmStatic
        @SuppressLint("InlinedApi")
        fun settingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, LocalNotificationListenerService::class.java)
                            .flattenToString(),
                    )
            } else {
                Intent(LISTENER_SETTINGS_ACTION)
            }

        /**
         * Opens the notification-access screen for this app.
         *
         * The only way consent can be requested, because the only way it can be
         * *granted* is a human toggling a switch in Settings. Returns false when the
         * screen could not be opened, so the caller can tell the user to go there
         * by hand rather than leaving a button that appears to do nothing.
         *
         * FLAG_ACTIVITY_NEW_TASK: this is callable from a service or a receiver,
         * which have no task of their own.
         */
        @JvmStatic
        fun requestConsent(context: Context): Boolean = try {
            context.startActivity(
                settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

/**
 * Whether this package is on the system's notification-listener allow list.
 *
 * `NotificationManager.getEnabledListenerPackages` is the API 26+ way to ask
 * and it does not require the listener to be running — which is exactly what is
 * needed to tell "never granted" apart from "granted but not connected yet".
 * The two are different user problems and they get different observations.
 */
internal fun isNotificationListenerEnabledFor(pkg: String, enabledPackages: Set<String>): Boolean =
    pkg in enabledPackages

// ----------------------------------------------------------------------------
// The platform bridge
// ----------------------------------------------------------------------------

/**
 * Converts platform objects into [NotificationSummary] rows.
 *
 * `CharSequence` from a notification can be a SpannableString of arbitrary
 * length, and `extras` can be missing entirely on a stripped notification, so
 * every read is null-guarded and every field is clamped before it reaches the
 * formatter. Worst case retained heap for a 30-item list: ~20 KB.
 */
internal object NotificationProjector {

    fun project(sbn: StatusBarNotification, label: (String) -> String): NotificationSummary {
        val notification = sbn.notification
        val extras = notification?.extras
        val title = Notifications.clamp(
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        ).take(MAX_TITLE_CHARS)
        val body = Notifications.clamp(
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        )
        return NotificationSummary(
            key = sbn.key ?: "",
            packageName = sbn.packageName ?: "",
            appLabel = label(sbn.packageName ?: ""),
            title = title,
            body = body,
            isOngoing = notification?.flags?.and(Notification.FLAG_ONGOING_EVENT) != 0,
            canReply = hasRemoteInput(notification),
            postedAtMillis = sbn.postTime,
        )
    }

    /**
     * A RemoteInput is a direct-reply field the posting app declared. Its
     * presence is the ONLY reliable signal that a reply can be delivered, which
     * is why it is projected into the summary: `notifications.list` can then
     * tell the model which notifications are answerable before it tries.
     */
    fun hasRemoteInput(notification: Notification?): Boolean {
        val actions = notification?.actions ?: return false
        if (actions.isEmpty()) return false
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            for (input in inputs) {
                if (input?.resultKey != null) return true
            }
        }
        return false
    }

    /** The first usable RemoteInput on the notification, or null. */
    fun firstRemoteInput(notification: Notification?): android.app.RemoteInput? {
        val actions = notification?.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            for (input in inputs) {
                if (input?.resultKey != null) return input
            }
        }
        return null
    }

    /**
     * Fills the RemoteInput and fires the action.
     *
     * The API here is load-bearing and easy to get wrong, so it is spelled out:
     *
     *  - `RemoteInput.setResultsFromIntent` is NOT public API. The public
     *    equivalent is the static `RemoteInput.addResultsToIntent`, which
     *    writes the results into the *Intent* the action will be sent with.
     *  - On API 28+ the intent must also declare a results source or the
     *    system drops the reply silently. `SOURCE_FREE_FORM_INPUT` is the
     *    correct value for a typed reply; without it a reply "succeeds" and
     *    never arrives, which is the one failure this file must not produce.
     *  - `PendingIntent.send` returns `void`. It throws
     *    `PendingIntent.CanceledException` when the intent is dead, so
     *    "returned without throwing" IS the success signal, and it is the only
     *    success signal there is. There is no branch here that reports a reply
     *    as sent unless the platform accepted the bundle.
     */
    fun sendReply(
        context: android.content.Context,
        sbn: StatusBarNotification,
        text: String,
    ): Boolean {
        val notification = sbn.notification ?: return false
        val input = firstRemoteInput(notification) ?: return false
        val actions = notification.actions ?: return false
        val target = actions.firstOrNull { action ->
            action?.remoteInputs?.any { it?.resultKey == input.resultKey } == true
        } ?: return false
        val pending = target.actionIntent ?: return false

        val results = android.os.Bundle().apply {
            putCharSequence(input.resultKey, text)
        }
        val intent = android.content.Intent()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Without this the platform discards the reply on 28+.
                android.app.RemoteInput.setResultsSource(
                    intent,
                    android.app.RemoteInput.SOURCE_FREE_FORM_INPUT,
                )
            }
            android.app.RemoteInput.addResultsToIntent(arrayOf(input), intent, results)
            pending.send(context, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            // The posting app withdrew the notification between the snapshot
            // and the send. A CanceledException is "too late", never "sent".
            false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}

// ----------------------------------------------------------------------------
// Tool 1: notifications.list
// ----------------------------------------------------------------------------

/**
 * READ_ONLY. Lists the notifications currently on screen.
 *
 * Empty is a real, common answer and it is stated as prose, never as `[]`.
 * When the listener is not enabled it returns `PermissionDenied` with the
 * exact settings path — the model can then tell the user what to do instead of
 * retrying a call that can only fail.
 */
class NotificationListTool(
    private val listener: () -> LocalNotificationListenerService? = {
        LocalNotificationListenerService.connected
    },
    private val grant: PlatformGrant = PlatformGrant.GRANT_ALL,
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "notifications.list",
        description = "Lists the notifications currently showing on the phone, newest first.",
        category = "notifications",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive("How many to return, $DEFAULT_LIST_LIMIT by default."),
                            )
                        },
                    )
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Optional text to match against app name, title or body."),
                            )
                        },
                    )
                    put(
                        "onlyReplyable",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive("Only notifications that accept a quick reply."),
                            )
                        },
                    )
                },
            )
            put("required", buildJsonArray { })
        },
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf(
            "notifications", "alerts", "messages", "list", "banner",
            "inbox", "what came in", "ping",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        return try {
            // The grant is asked of the PLATFORM. It used to be read from
            // `context.permissionGranted`, which is filled from a set nothing
            // populates and so was false on every call — meaning a user who HAD
            // granted notification access was told they had not, and a user who
            // had not was told the same thing for a different reason they could
            // not act on. `grant` calls NotificationListenerService
            // .isGranted(), which reads the system allow list directly.
            if (!grant.isGranted(ToolPermissions.NOTIFICATION_LISTENER)) {
                return failed(
                    PermissionDenial.observation("notifications.list", ToolPermissions.NOTIFICATION_LISTENER),
                    ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.NOTIFICATION_LISTENER)),
                )
            }
            if (context.signal.isCancelled()) return cancelled()

            val service = listener()
            if (service == null || !LocalNotificationListenerService.isConnected()) {
                return failed(
                    Notifications.notConnectedNotice(),
                    ToolError.Unavailable("NotificationListenerService is not bound"),
                )
            }
            if (context.signal.isCancelled()) return cancelled()

            val active = try {
                service.activeNotifications
            } catch (e: SecurityException) {
                return failed(
                    Notifications.notEnabledNotice(),
                    ToolError.PermissionDenied("SecurityException reading activeNotifications"),
                )
            } ?: return failed(
                Notifications.notConnectedNotice(),
                ToolError.Unavailable("activeNotifications returned null"),
            )

            val all = active.map { sbn -> NotificationProjector.project(sbn, LABELS::labelFor) }
            val query = NotificationArgs.coerceString(args["query"])?.lowercase()
            val onlyReplyable = NotificationArgs.coerceBool(args["onlyReplyable"])
            val limit = NotificationArgs.coerceInt(
                args["limit"],
                fallback = DEFAULT_LIST_LIMIT,
                min = 1,
                max = MAX_LIST_LIMIT,
            )

            val filtered = all
                .filter { !onlyReplyable || it.canReply }
                .filter { query == null || it.searchableHaystack().contains(query) }
                .sortedByDescending { it.postedAtMillis }
                .take(limit)

            if (context.signal.isCancelled()) return cancelled()

            val observation = Notifications.listObservation(filtered, all.size)
            ToolResult(
                success = true,
                observation = observation,
                data = buildJsonObject {
                    put("count", JsonPrimitive(filtered.size))
                    put("active", JsonPrimitive(all.size))
                    put(
                        "items",
                        buildJsonArray {
                            filtered.forEach { n ->
                                add(
                                    buildJsonObject {
                                        put("key", JsonPrimitive(Notifications.shortKey(n.key)))
                                        put("app", JsonPrimitive(n.appLabel.ifBlank { n.packageName }))
                                        put("title", JsonPrimitive(n.title))
                                        put("body", JsonPrimitive(n.body))
                                        put("replyable", JsonPrimitive(n.canReply))
                                        put("ongoing", JsonPrimitive(n.isOngoing))
                                    },
                                )
                            }
                        },
                    )
                },
            )
        } catch (e: SecurityException) {
            failed(
                Notifications.notEnabledNotice(),
                ToolError.PermissionDenied("SecurityException: ${e.javaClass.simpleName}"),
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                observation = "Could not read notifications (${e.javaClass.simpleName}). " +
                    "Nothing was changed. Do not retry in a loop.",
                error = ToolError.Internal("${e.javaClass.simpleName} in NotificationListTool"),
            )
        }
    }

    private fun failed(observation: String, error: ToolError) = ToolResult(
        success = false,
        observation = observation,
        error = error,
    )

    private fun cancelled() = ToolResult(
        success = false,
        observation = "Cancelled before the notification list was read.",
        error = ToolError.Cancelled("signal raised during notifications.list"),
    )
}

// ----------------------------------------------------------------------------
// Tool 2: notifications.reply
// ----------------------------------------------------------------------------

/**
 * EXTERNAL_COMMUNICATION. Replies to a messaging notification through its
 * RemoteInput.
 *
 * This is the hardest tool in the wave, and its value is mostly in what it
 * refuses to do:
 *
 *  - no listener  -> Unavailable, never a fake send
 *  - unknown key  -> NotFound, naming the stale key
 *  - no RemoteInput on that notification -> Unavailable, saying the app does
 *    not offer quick reply and naming the app, so the model can ask the user
 *    to reply in-app
 *  - the platform rejects the fill -> Unavailable
 *
 * There is exactly one path to a success observation, and it runs only after
 * `RemoteInput.setResultsFromIntent` and the action `send` both returned
 * without throwing.
 */
class NotificationReplyTool(
    private val listener: () -> LocalNotificationListenerService? = {
        LocalNotificationListenerService.connected
    },
    private val sender: (android.content.Context?, StatusBarNotification, String) -> Boolean =
        { context, sbn, text ->
            context?.let { NotificationProjector.sendReply(it, sbn, text) } ?: false
        },
    private val grant: PlatformGrant = PlatformGrant.GRANT_ALL,
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "notifications.reply",
        description = "Sends a quick reply to a messaging notification that offers a reply box.",
        category = "notifications",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Notification key from notifications.list, e.g. com.whatsapp#2."),
                            )
                        },
                    )
                    put(
                        "text",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("The message body to send."))
                        },
                    )
                },
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("key"))
                    add(JsonPrimitive("text"))
                },
            )
        },
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        tags = setOf(
            "reply", "respond", "answer", "message back", "notification", "chat",
            "text message", "send",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        return try {
            val rawKey = NotificationArgs.coerceString(args["key"])
            val text = NotificationArgs.coerceString(args["text"])
            if (rawKey == null) {
                return invalid("Missing the \"key\" argument. Run notifications.list first and " +
                    "copy a key from it.")
            }
            if (text == null) {
                return invalid("Missing the \"text\" argument: there is nothing to reply with.")
            }
            if (text.length > 2000) {
                return invalid("The reply is ${text.length} characters, over the 2000 limit. " +
                    "Send a short message.")
            }
            // Platform truth — see the note on notifications.list. Nothing is
            // sent on this path, and the message says so.
            if (!grant.isGranted(ToolPermissions.NOTIFICATION_LISTENER)) {
                return denied(
                    PermissionDenial.observation("notifications.reply", ToolPermissions.NOTIFICATION_LISTENER) +
                        " No reply was sent.",
                )
            }
            if (context.signal.isCancelled()) return cancelled()

            val service = listener()
            if (service == null || !LocalNotificationListenerService.isConnected()) {
                return ToolResult(
                    success = false,
                    observation = Notifications.notConnectedNotice() + " No reply was sent.",
                    error = ToolError.Unavailable("NotificationListenerService is not bound"),
                )
            }
            if (context.signal.isCancelled()) return cancelled()

            val active = try {
                service.activeNotifications
            } catch (e: SecurityException) {
                return denied(Notifications.notEnabledNotice())
            } ?: return ToolResult(
                success = false,
                observation = Notifications.notConnectedNotice() + " No reply was sent.",
                error = ToolError.Unavailable("activeNotifications returned null"),
            )

            val summaries = active.map { sbn -> NotificationProjector.project(sbn, LABELS::labelFor) }
            val target = Notifications.keyMatches(rawKey, summaries)
                ?: return ToolResult(
                    success = false,
                    observation = Notifications.notFoundNotice(rawKey),
                    error = ToolError.NotFound("no active notification for key $rawKey"),
                )

            val sbn = active.firstOrNull { it.key == target.key }
                ?: return ToolResult(
                    success = false,
                    observation = Notifications.notFoundNotice(rawKey),
                    error = ToolError.NotFound("notification disappeared between listing and reply"),
                )

            if (!NotificationProjector.hasRemoteInput(sbn.notification)) {
                return ToolResult(
                    success = false,
                    observation = Notifications.notReplyableNotice(
                        target.appLabel.ifBlank { target.packageName },
                    ),
                    error = ToolError.Unavailable("no RemoteInput on ${target.packageName}"),
                )
            }
            if (context.signal.isCancelled()) return cancelled()

            val delivered = try {
                sender(AndroidContextHolder.current, sbn, text)
            } catch (e: SecurityException) {
                return ToolResult(
                    success = false,
                    observation = "The system refused to send the reply (SecurityException). " +
                        "Nothing was sent. Ask the user to reply inside " +
                        "${target.appLabel.ifBlank { target.packageName }}.",
                    error = ToolError.PermissionDenied("SecurityException while sending reply"),
                )
            } catch (e: Exception) {
                return ToolResult(
                    success = false,
                    observation = "The reply could not be handed to " +
                        "${target.appLabel.ifBlank { target.packageName }} " +
                        "(${e.javaClass.simpleName}). Nothing was sent.",
                    error = ToolError.Unavailable("${e.javaClass.simpleName} while sending reply"),
                )
            }

            if (!delivered) {
                return ToolResult(
                    success = false,
                    observation = "The system rejected the quick reply for " +
                        "${target.appLabel.ifBlank { target.packageName }}. Nothing was sent. " +
                        "Do not retry; ask the user to reply inside the app.",
                    error = ToolError.Unavailable("RemoteInput fill was rejected"),
                )
            }

            ToolResult(
                success = true,
                observation = Notifications.replySentNotice(
                    target.appLabel.ifBlank { target.packageName },
                    text,
                ),
                data = buildJsonObject {
                    put("key", JsonPrimitive(Notifications.shortKey(target.key)))
                    put("app", JsonPrimitive(target.packageName))
                    put("chars", JsonPrimitive(text.length))
                },
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                observation = "notifications.reply failed unexpectedly (${e.javaClass.simpleName}). " +
                    "No reply was sent.",
                error = ToolError.Internal("${e.javaClass.simpleName} in NotificationReplyTool"),
            )
        }
    }

    private fun invalid(what: String) = ToolResult(
        success = false,
        observation = what,
        error = ToolError.InvalidArguments(what.take(120)),
    )

    private fun denied(what: String) = ToolResult(
        success = false,
        observation = what,
        error = ToolError.PermissionDenied("notification listener not granted"),
    )

    private fun cancelled() = ToolResult(
        success = false,
        observation = "Cancelled before the reply was sent. Nothing was sent.",
        error = ToolError.Cancelled("signal raised during notifications.reply"),
    )
}

// ----------------------------------------------------------------------------
// Tool 3: notifications.dismiss
// ----------------------------------------------------------------------------

/**
 * REVERSIBLE. Cancels a notification by key.
 *
 * Dismissal is genuinely reversible in the way that matters: the posting app
 * can re-post, and nothing is destroyed. It is not DESTRUCTIVE — no user data
 * is deleted, only a banner — so per §8 it executes without a confirmation
 * dialog. The tool still refuses when no listener is connected, because
 * `cancelNotification` on a null service is either a crash or a silent no-op
 * and neither is an acceptable answer.
 */
class NotificationDismissTool(
    private val listener: () -> LocalNotificationListenerService? = {
        LocalNotificationListenerService.connected
    },
    private val grant: PlatformGrant = PlatformGrant.GRANT_ALL,
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "notifications.dismiss",
        description = "Dismisses (cancels) one notification from the shade by its key.",
        category = "notifications",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Notification key from notifications.list."),
                            )
                        },
                    )
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("key")) })
        },
        risk = ToolRisk.REVERSIBLE,
        tags = setOf(
            "dismiss", "clear", "notification", "remove", "silence", "swipe away",
            "banner",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        return try {
            val rawKey = NotificationArgs.coerceString(args["key"])
            if (rawKey == null) {
                return ToolResult(
                    success = false,
                    observation = "Missing the \"key\" argument. Run notifications.list first and " +
                        "copy a key from it.",
                    error = ToolError.InvalidArguments("key is required"),
                )
            }
            // Platform truth — see the note on notifications.list.
            if (!grant.isGranted(ToolPermissions.NOTIFICATION_LISTENER)) {
                return ToolResult(
                    success = false,
                    observation = PermissionDenial.observation(
                        "notifications.dismiss", ToolPermissions.NOTIFICATION_LISTENER,
                    ) + " Nothing was dismissed.",
                    error = ToolError.PermissionDenied(
                        PermissionDenial.summary(ToolPermissions.NOTIFICATION_LISTENER),
                    ),
                )
            }
            if (context.signal.isCancelled()) return cancelled()

            val service = listener()
            if (service == null || !LocalNotificationListenerService.isConnected()) {
                return ToolResult(
                    success = false,
                    observation = Notifications.notConnectedNotice() + " Nothing was dismissed.",
                    error = ToolError.Unavailable("NotificationListenerService is not bound"),
                )
            }
            if (context.signal.isCancelled()) return cancelled()

            val active = try {
                service.activeNotifications
            } catch (e: SecurityException) {
                return ToolResult(
                    success = false,
                    observation = Notifications.notEnabledNotice() + " Nothing was dismissed.",
                    error = ToolError.PermissionDenied("SecurityException reading activeNotifications"),
                )
            } ?: return ToolResult(
                success = false,
                observation = Notifications.notConnectedNotice() + " Nothing was dismissed.",
                error = ToolError.Unavailable("activeNotifications returned null"),
            )

            val summaries = active.map { sbn -> NotificationProjector.project(sbn, LABELS::labelFor) }
            val target = Notifications.keyMatches(rawKey, summaries)
                ?: return ToolResult(
                    success = false,
                    observation = Notifications.notFoundNotice(rawKey),
                    error = ToolError.NotFound("no active notification for key $rawKey"),
                )

            val app = target.appLabel.ifBlank { target.packageName }
            val cancelled = try {
                service.cancelNotification(target.key)
                true
            } catch (e: SecurityException) {
                return ToolResult(
                    success = false,
                    observation = "The system refused to dismiss the $app notification. " +
                        "It is still showing.",
                    error = ToolError.PermissionDenied("SecurityException cancelling notification"),
                )
            } catch (e: Exception) {
                return ToolResult(
                    success = false,
                    observation = "Could not dismiss the $app notification " +
                        "(${e.javaClass.simpleName}). It may still be showing.",
                    error = ToolError.Unavailable("${e.javaClass.simpleName} cancelling notification"),
                )
            }

            if (!cancelled) {
                return ToolResult(
                    success = false,
                    observation = Notifications.dismissFailedNotice(app),
                    error = ToolError.Unavailable("cancelNotification refused"),
                )
            }

            ToolResult(
                success = true,
                observation = Notifications.dismissedNotice(app, target.title),
                data = buildJsonObject {
                    put("key", JsonPrimitive(Notifications.shortKey(target.key)))
                    put("app", JsonPrimitive(target.packageName))
                },
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                observation = "notifications.dismiss failed unexpectedly " +
                    "(${e.javaClass.simpleName}). The notification may still be showing.",
                error = ToolError.Internal("${e.javaClass.simpleName} in NotificationDismissTool"),
            )
        }
    }

    private fun cancelled() = ToolResult(
        success = false,
        observation = "Cancelled before the notification was dismissed. Nothing was dismissed.",
        error = ToolError.Cancelled("signal raised during notifications.dismiss"),
    )
}

// ----------------------------------------------------------------------------
// Ambient context
// ----------------------------------------------------------------------------

/**
 * The application Context, captured once.
 *
 * `NotificationListenerService` has no `getApplicationContext` of its own that
 * is usable from a static tool, and threading a Context through every tool
 * constructor would put an Android type in the middle of the pure logic this
 * file is built around. The app sets this once in `Application.onCreate`.
 */
object AndroidContextHolder {
    @Volatile
    @JvmStatic
    var current: android.content.Context? = null
        private set

    fun install(context: android.content.Context) {
        current = context.applicationContext
    }
}

/**
 * Application labels, resolved once per package.
 *
 * Bounded at 64 packages. A tool that caches every label it has ever seen is a
 * leak on a device that has 400 apps; 64 covers every app that can plausibly
 * have a notification showing at once, and beyond that an unknown label simply
 * renders as the package name.
 */
internal object LABELS {

    private const val MAX_CACHED = 64
    private val cache = HashMap<String, String>()

    fun labelFor(pkg: String): String {
        if (pkg.isBlank()) return ""
        synchronized(cache) {
            cache[pkg]?.let { return it }
        }
        val label = try {
            val context = AndroidContextHolder.current
            val pm = context?.packageManager
            if (pm == null) {
                pkg
            } else {
                val app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                app.ifBlank { pkg }.take(40)
            }
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            // An uninstalled app keeps its notifications for a moment. The
            // package name is an acceptable label, and the caller's list is
            // about to be stale anyway.
            pkg
        } catch (e: Exception) {
            // Any other platform failure (a revoked package visibility, a dead
            // binder) degrades to the package name rather than failing the read.
            pkg
        }
        synchronized(cache) {
            if (cache.size >= MAX_CACHED) cache.clear()
            cache[pkg] = label
        }
        return label
    }
}


// =====================================================================================
// The tool set
// =====================================================================================

/**
 * All three notification tools.
 *
 * WHY this takes no [Context] while the other factories do: these tools talk to
 * [LocalNotificationListenerService], which is bound by the system rather than
 * reached through a resolver, and each tool already defaults its `listener` to
 * the live service instance. Passing a context in would suggest the tools hold
 * one, and they deliberately do not — a tool that kept a `Context` would leak
 * whatever the user was doing through the notification shade.
 *
 * The context is threaded through only at REPLY time, via
 * [NotificationProjector], because that is the single call that needs it.
 */
fun notificationTools(grant: PlatformGrant): List<AgentTool> = listOf(
    NotificationListTool(grant = grant),
    NotificationReplyTool(grant = grant),
    NotificationDismissTool(grant = grant),
)
