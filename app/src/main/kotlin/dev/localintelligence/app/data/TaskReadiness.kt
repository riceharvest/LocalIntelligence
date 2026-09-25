package dev.localintelligence.app.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.blockingReason
import dev.localintelligence.app.execution.TaskAlarmScheduler

/**
 * Everything that stands between "the user pressed Create" and "this will
 * actually run at 07:00".
 *
 * ## Why this is not a second model-availability state
 *
 * The temptation was a `ScheduleReady`/`ScheduleBlocked` pair next to
 * [ModelAvailability]. That is how a user ends up being told two different
 * things about the same broken phone on two different screens. So the *model*
 * half of this type is [ModelAvailability] itself, passed through untouched, and
 * the only thing added around it is the facts that are genuinely orthogonal:
 * whether the app may post a notification it needs, and whether the OS is
 * throttling background work. Same vocabulary, extended — not replaced.
 *
 * ## Why these are blockers and not warnings
 *
 * A scheduled task with no model is not "less reliable", it is a row in a list
 * that says a thing will happen and it will not. The whole point of the
 * honesty requirement is that the user finds out *before* 07:00, from this
 * screen, rather than at 07:01 from an absence. So every case here is
 * actionable, names the fix, and returns the Intent that opens the screen where
 * the user takes it.
 */
data class TaskReadiness(
    /** The model half, verbatim. Never re-encoded, never re-derived. */
    val modelAvailability: ModelAvailability,
    /** True when a model is selected and loadable, i.e. a run can produce tokens. */
    val hasModel: Boolean,
    val notificationsAllowed: Boolean,
    val batterySaverOn: Boolean,
    val exactAlarmsAllowed: Boolean,
) {
    /**
     * Everything that stops a run, worst first.
     *
     * Ordered by what the user is most likely to be able to fix without leaving
     * the app: no model is a three-tap fix, notification access is a Settings
     * detour, battery saver is a Settings toggle, and exact-alarm access is the
     * one an OEM may have revoked without the user ever knowing.
     */
    val blockers: List<TaskBlocker> = buildList {
        // Reuse, not reimplement: `blockingReason()` is the single wording for
        // "the model would not load" in this app, and a scheduled task showing
        // a different sentence for the same failure is exactly the drift this
        // type exists to prevent.
        modelAvailability.blockingReason()?.let { reason ->
            add(
                TaskBlocker.NoModel(
                    message = reason,
                    action = TaskAction.OpenModels,
                ),
            )
        }
        if (!notificationsAllowed) {
            add(
                TaskBlocker.NoNotifications(
                    message = "Android will not let this app show notifications, so a " +
                        "scheduled task would finish without ever telling you it ran.",
                    action = TaskAction.OpenNotificationAccess,
                ),
            )
        }
        if (batterySaverOn) {
            add(
                TaskBlocker.BatterySaver(
                    message = "Battery saver is on. It restricts what this app can do in " +
                        "the background, so a scheduled task may be delayed or dropped. " +
                        "The alarm itself will still fire.",
                    action = TaskAction.OpenBatterySettings,
                ),
            )
        }
        if (!exactAlarmsAllowed) {
            add(
                TaskBlocker.NoExactAlarms(
                    message = "Android is not letting this app schedule exact alarms, so a " +
                        "task cannot be set for a specific time. Turn on \"Alarms and " +
                        "reminders\" for this app in system settings.",
                    action = TaskAction.OpenExactAlarmSettings,
                ),
            )
        }
    }

    /** True when a run started right now would genuinely happen. */
    val canRun: Boolean get() = blockers.isEmpty()
}

/** One thing standing in the way, with the settings screen that resolves it. */
sealed interface TaskBlocker {
    val message: String
    val action: TaskAction

    data class NoModel(override val message: String, override val action: TaskAction) : TaskBlocker
    data class NoNotifications(override val message: String, override val action: TaskAction) :
        TaskBlocker

    data class BatterySaver(override val message: String, override val action: TaskAction) :
        TaskBlocker

    data class NoExactAlarms(override val message: String, override val action: TaskAction) :
        TaskBlocker
}

/** Where the user goes to unblock it. */
enum class TaskAction { OpenModels, OpenNotificationAccess, OpenBatterySettings, OpenExactAlarmSettings }

/**
 * Reads the device facts.
 *
 * A function taking [Context] rather than a class constructed once, because
 * every one of these can change while the app is open: the user can grant
 * notification access in Settings and come straight back, and a cached answer
 * would be a lie the moment they do.
 *
 * [hasModel] is a parameter rather than a second read of [ModelAvailability]
 * because it is a genuinely different question. `ModelAvailability.Ready` means
 * a model is *resident*; `ensureModelReady()` can still return
 * [ModelAvailability.None] when nothing is *selected*, and a schedule with
 * nothing selected cannot run at 07:00 even though a model file may be sitting
 * on disk unread.
 */
fun readTaskReadiness(
    context: Context,
    modelAvailability: ModelAvailability,
    hasSelectedModel: Boolean,
): TaskReadiness = TaskReadiness(
    modelAvailability = modelAvailability,
    hasModel = modelAvailability.canRun || hasSelectedModel,
    notificationsAllowed = areNotificationsAllowed(context),
    batterySaverOn = isBatterySaverOn(context),
    exactAlarmsAllowed = TaskAlarmScheduler.canScheduleExactAlarms(context),
)

/**
 * Whether the app may post notifications.
 *
 * `checkSelfPermission` rather than `areNotificationsEnabled`: the latter is
 * also false when the user has the *channel* muted, and this is asking about
 * the app-level permission only. A task whose channel is muted still ran, and
 * saying it could not have run because of a channel the app never created would
 * be a different lie.
 */
fun areNotificationsAllowed(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        // POST_NOTIFICATIONS became a runtime permission on API 33. Before
        // that, declaring it was sufficient and there is nothing to check.
        true
    }

/**
 * Whether the OS is throttling background work.
 *
 * `isPowerSaveMode` is the honest question and it is a snapshot: the user can
 * toggle battery saver independently of this app, and the reading is taken
 * fresh on every composition. This does not claim a scheduled task *will* be
 * dropped — it says the restriction is on, which is the only fact available.
 */
fun isBatterySaverOn(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return power.isPowerSaveMode
}

/**
 * The Intent that opens the screen where [action] is resolved, or null when
 * there is none.
 *
 * ## Why [TaskAction.OpenModels] has no Intent
 *
 * It is an in-app destination, not a system screen, and it is wired through a
 * `onOpenModels` callback because navigation in this app is a NavHost route and
 * a synthetic action string would have to be caught by a filter that nothing
 * else declares. Returning null for it and letting the screen branch is the
 * honest shape: one caller, two mechanisms, no string that could silently fail
 * to match.
 *
 * Null is also a real case for the Settings actions — an OEM build can lack the
 * exact-alarm settings activity entirely — and the screen says so rather than
 * pressing a button that does nothing, which is indistinguishable from a broken
 * app.
 */
fun intentForTaskAction(context: Context, action: TaskAction): Intent? {
    val intent = when (action) {
        TaskAction.OpenModels -> return null

        TaskAction.OpenNotificationAccess ->
            Intent("dev.localintelligence.android.tools.notifications.REQUEST_CONSENT")

        // `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, not an
        // Intent constant: it lives on Settings. This opens the system's own
        // list of apps that may ignore battery optimisations, which is the only
        // place a user can undo a restriction applied by the manufacturer.
        TaskAction.OpenBatterySettings ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        // `canScheduleExactAlarms()` is false and the only way to change that is
        // a system screen. This exact action is the one the platform documents
        // for it; a generic ACTION_SETTINGS would land the user in a list.
        TaskAction.OpenExactAlarmSettings ->
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
    }
    return intent.takeIf { it.resolveActivity(context.packageManager) != null }
}
