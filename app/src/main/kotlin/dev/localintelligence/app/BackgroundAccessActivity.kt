package dev.localintelligence.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
import dev.localintelligence.app.data.ScheduledTaskStore
import dev.localintelligence.app.execution.ScheduledRunRegistry
import dev.localintelligence.app.execution.TaskAlarmScheduler
import dev.localintelligence.app.ui.LocalIntelligenceTheme

/**
 * The screen that asks for background capabilities — and, more importantly,
 * the screen that says what each one is *for*.
 *
 * ## Why this exists as its own Activity
 *
 * `LocalNotificationListenerService` can only be bound by the system, and only
 * after a human turns on a switch in Android Settings. Nothing an app ships can
 * grant it, and — the part that actually mattered — nothing in this codebase
 * *asked*. The tools all returned an honest `PermissionDenied` naming the
 * settings screen, which is the correct behaviour for a tool and useless to a
 * user, because "open Settings and find the toggle" is a thing the user has to
 * know how to do.
 *
 * So the request had to have a home, and the natural one is a screen the user
 * can be sent to. It is an Activity rather than a composable inside the chat for
 * two reasons: it is reachable from a `PendingIntent` and a notification, and it
 * is a separate task, so returning from Settings lands the user back on it.
 *
 * ## What changed, and why it was a lie before
 *
 * This screen used to be a single card about notifications, and the chat screen
 * carried a banner that asked for notification access on every single launch
 * (`MainActivity.NotificationAccessBanner`). That pairing told a user two
 * things that were both wrong:
 *
 *  1. **It conflated two unrelated capabilities.** Notification *access*
 *     (a `NotificationListenerService` bound by the system after a human flips
 *     a switch) is needed by exactly three tools — `notifications.list`,
 *     `notifications.reply`, `notifications.dismiss`. It is NOT needed to run a
 *     scheduled task, to create an alarm, or to chat. Presenting it as "the
 *     background permission" made an app whose selling point is on-device and
 *     private look like it wanted to read the user's notifications in order to
 *     do its job.
 *  2. **It was unconditional.** The banner rendered whenever the grant was
 *     absent, which is the state of every install that had never visited
 *     Settings. There was no dismissal, no memory of a dismissal, and no notion
 *     of relevance: a user with no scheduled task and no interest in background
 *     work was asked, on every launch, forever, and the only way to make it stop
 *     was to go and grant a permission they did not want.
 *
 * Both are fixed by the structure below: the two capabilities are separate rows
 * with separate permissions and separate consequences, and asking is gated on
 * [BackgroundConsent] rather than on the absence of a grant.
 *
 * ## The state is re-read on every resume
 *
 * The grant happens in another process, on a screen we do not own. A value read
 * once at composition is wrong the moment the user comes back, and the screen
 * would then tell a user who just enabled it that it is still off — which is the
 * single most credibility-destroying thing a permissions screen can do.
 */
class BackgroundAccessActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalIntelligenceTheme {
                BackgroundAccessScreen()
            }
        }
    }
}

// ===========================================================================
// What the app asks for, and what it does not
// ===========================================================================
//
// TWO capabilities, TWO different mechanisms, and the difference is the whole
// point of this file:
//
//   notifications.* tools  ->  a NotificationListenerService bound by the system
//                              after a human flips a switch in Settings. Not a
//                              runtime permission; `requestPermissions()` cannot
//                              obtain it and there is no `shouldShow…Rationale`.
//   alarm.create +         ->  SCHEDULE_EXACT_ALARM special access, checked
//   scheduled tasks            with `AlarmManager.canScheduleExactAlarms()`. On
//                              API 31+ this is a Settings toggle an OEM can
//                              revoke; below 31 it always returns true.
//
// A third thing is deliberately NOT in this file's two rows, because it is not a
// background capability and conflating it with the other two is its own
// dishonesty: `POST_NOTIFICATIONS`. That is the app being allowed to show *its
// own* notifications. It is what lets a scheduled task tell the user it ran. It
// is not needed for foreground chat, and it is not needed for anything to be
// read. `TaskReadiness` already surfaces it as a blocker, in those words.
//
// ===========================================================================

/**
 * One background capability, and how to ask for it and read it.
 *
 * The enum rather than two near-identical code paths because the two differ
 * only in the platform call — and the moment they are copy-pasted, the copy is
 * what drifts, and a permissions screen that lies about one of its two rows is
 * worse than a permissions screen that says nothing.
 */
enum class BackgroundCapability(
    /** Key in the consent prefs file. Stable: renaming one silently resets it. */
    internal val key: String,
    /** What the app calls this, in the app's own words. */
    internal val title: String,
    /**
     * What turns it on, in one sentence, naming the tools and nothing else.
     *
     * The specificity is the feature. "Background access" is a claim; "the
     * notifications.list / reply / dismiss tools" is a checkable one, and it is
     * what stops a user reading this screen as "the app wants to see my
     * notifications so it can work".
     */
    internal val enables: String,
    /**
     * What is explicitly NOT enabled by turning this on.
     *
     * Every row carries one. A permission screen that lists only what it can do
     * is an advertisement; the sentence next to it is what makes the row
     * answerable, and "this does not let the app run anything on its own" is
     * the sentence that matters most on an app that runs entirely on-device.
     */
    internal val doesNotEnable: String,
    /** What stops working while it is off. Empty means "nothing does". */
    internal val consequence: String,
) {
    Notifications(
        key = "notifications",
        title = "Notification access",
        enables = "Lets the assistant read, reply to and dismiss notifications, " +
            "but only the three notifications.* tools and only when a run asks " +
            "for them.",
        doesNotEnable = "It does not let the app run anything by itself, and it " +
            "is not needed for scheduled tasks, alarms, or chat.",
        consequence = "The assistant cannot read notifications. It says so and " +
            "stops, rather than telling you your phone is quiet. Everything else " +
            "works exactly as before.",
    ),
    ExactAlarms(
        key = "exact_alarms",
        title = "Alarms and reminders",
        enables = "Lets the app set a specific time for a scheduled task, and " +
            "lets the assistant use the alarm.create tool.",
        doesNotEnable = "It does not let the app read notifications, and it is " +
            "not needed to chat.",
        consequence = "Scheduled tasks and alarm.create cannot be armed. " +
            "Chatting, models and every other tool are unaffected. A task you " +
            "try to schedule will say Android refused rather than quietly " +
            "never run.",
    ),
}

/**
 * Whether the app may ask about a capability, and whether the user has already
 * said no.
 *
 * ## The bug this type exists to fix
 *
 * The chat screen's notification banner asked whenever the grant was absent. The
 * grant being absent is the *default state of a fresh install*, not evidence
 * that the user wants the capability, so "not granted" was being used as a
 * proxy for "wants it". A user who never asked for background work, has no
 * scheduled task, and only ever wanted to chat was asked on every launch, with
 * no way to dismiss it, and the only way to silence it was to grant a
 * permission they had declined in spirit.
 *
 * ## The rule now
 *
 * Ask only when all of these hold:
 *
 *  1. the capability is not already granted,
 *  2. the user has not dismissed this capability before, and
 *  3. the user has shown interest in background work at all.
 *
 * Rule 3 is the one that was missing entirely, and it is why "no scheduled
 * task and no ask for background work" now means silence. Interest is recorded
 * by [noteBackgroundInterest], which is called from an explicit user action —
 * never from a launch, a timer, or a tool call.
 *
 * ## Why the dismissal outlives the grant
 *
 * Granting, then revoking in Settings, then being asked again would be the
 * platform's fault played off as the app's. [dismiss] is therefore recorded the
 * moment the user acts — either by pressing "not now", or by being sent to
 * Settings — and is never cleared by the grant. Only [forget] clears it, and
 * the only caller of [forget] is the "Ask me again" button on this screen.
 *
 * ## Why this is SharedPreferences
 *
 * Three booleans, read on a compose that must not do disk IO on the main
 * thread any more than the rest of this app does, and — the deciding reason —
 * the same constraint that put [ScheduledTaskStore] on preferences: `:android`
 * declares Room as `implementation`, so `RoomDatabase` does not resolve from
 * `:app` and a direct database call here would not compile. Three keys in a
 * file the app already has is also three keys a user can find in a bug report.
 */
object BackgroundConsent {

    /** Answers are strings rather than an enum ordinal: a reorder must not
     *  silently turn every previous "dismissed" back into "undecided". */
    const val PREFS_NAME = "background_consent"
    private const val KEY_PREFIX = "dismissed_"
    private const val KEY_INTEREST = "background_interest_v1"

    /** The whole of what is remembered about one capability. */
    enum class Answer {
        /** Never asked, or asked and the user said nothing we stored. */
        Unset,

        /**
         * The user does not want to be asked again.
         *
         * Set by [dismiss] and cleared only by [forget]. Survives a grant and
         * a later revoke, on purpose.
         */
        Dismissed,
    }

    fun answer(context: Context, capability: BackgroundCapability): Answer {
        val stored = prefs(context).getString(KEY_PREFIX + capability.key, null)
        return if (stored == Answer.Dismissed.name) Answer.Dismissed else Answer.Unset
    }

    /** True when the platform currently allows this capability. */
    fun granted(context: Context, capability: BackgroundCapability): Boolean =
        when (capability) {
            BackgroundCapability.Notifications ->
                LocalNotificationListenerService.isGranted(context)
            BackgroundCapability.ExactAlarms ->
                TaskAlarmScheduler.canScheduleExactAlarms(context)
        }

    /** True when this app is being *offered* a capability it does not have. */
    fun shouldAsk(context: Context, capability: BackgroundCapability): Boolean =
        !granted(context, capability) &&
            answer(context, capability) == Answer.Unset &&
            hasBackgroundInterest(context)

    /** Has the user ever expressed interest in anything that runs in background? */
    fun hasBackgroundInterest(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INTEREST, false)

    /**
     * Records that the user wants to be told about background capabilities.
     *
     * The only entry point. Called from an explicit tap, so the value in this
     * file is always something a human asked for rather than something the app
     * inferred — which is what makes [shouldAsk] safe to gate a recurring
     * prompt on.
     */
    fun noteBackgroundInterest(context: Context) {
        prefs(context).edit().putBoolean(KEY_INTEREST, true).apply()
    }

    /**
     * "Not now", and also "not now" after a trip to Settings.
     *
     * Deliberately one-way with respect to the grant: nothing in this function
     * looks at [granted], because a dismissal is a statement about being asked,
     * not about the current state of a switch. See the type KDoc.
     */
    fun dismiss(context: Context, capability: BackgroundCapability) {
        prefs(context).edit()
            .putString(KEY_PREFIX + capability.key, Answer.Dismissed.name)
            .apply()
    }

    /**
     * Clears a dismissal.
     *
     * Reached only from the "Ask me again" button, so consent to re-ask is
     * always a fresh, explicit act rather than a side effect of some other
     * action.
     */
    fun forget(context: Context, capability: BackgroundCapability) {
        prefs(context).edit().remove(KEY_PREFIX + capability.key).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

@Composable
private fun BackgroundAccessScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The grant is decided in another process, so it is re-read on every
    // ON_RESUME. See the class doc: composition-time state is a lie the moment
    // the user comes back from Settings.
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // `tick` is the resume counter and it is the cache key for every read
    // below, so the whole block is re-evaluated when the user comes back from
    // Settings rather than serving a permissions screen that is stale by one
    // trip. `remember(tick)` is the idiomatic way to depend on it without
    // pretending it is state.
    val interest = remember(tick) { BackgroundConsent.hasBackgroundInterest(context) }
    val answers = remember(tick) {
        BackgroundCapability.entries.associateWith { BackgroundConsent.answer(context, it) }
    }
    val granted = remember(tick) {
        BackgroundCapability.entries.associateWith { BackgroundConsent.granted(context, it) }
    }
    val hasScheduledTask = remember(tick) {
        runCatching { ScheduledTaskStore(context).all().isNotEmpty() }.getOrDefault(false)
    }
    val runningTaskId = ScheduledRunRegistry.runningTaskId

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Background access", style = MaterialTheme.typography.headlineSmall)

        // The first thing on the screen, and the sentence that was missing
        // entirely before it: none of the switches below are needed to use the
        // app. Everything here is opt-in to a specific capability, and turning
        // every one of them off leaves a working chat.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "None of this is needed to chat",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Chatting, importing a model and running tools all work on " +
                        "this phone with every switch below off. The two switches " +
                        "are separate capabilities, not one \"background\" " +
                        "permission, and turning one on does not turn on the " +
                        "other. Nothing you switch on here leaves the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        BackgroundCapability.entries.forEach { capability ->
            CapabilityCard(
                capability = capability,
                isGranted = granted.getValue(capability),
                answer = answers.getValue(capability),
                onEngage = {
                    // Any deliberate act on this row is an answer, so it stops
                    // the recurring prompt. See the parameter's KDoc for why
                    // "Turn on" counts as well as "Not now".
                    BackgroundConsent.dismiss(context, capability)
                },
            )
        }

        HorizontalDivider()

        // What is off, and what that costs. Rendered from the same
        // `consequence` sentences the rows carry, so the "you turned this off"
        // panel and the offer cannot drift apart.
        val declined = BackgroundCapability.entries.filter {
            !granted.getValue(it) && answers.getValue(it) == BackgroundConsent.Answer.Dismissed
        }
        if (declined.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "What is switched off",
                    style = MaterialTheme.typography.titleSmall,
                )
                declined.forEach { capability ->
                    Text(
                        "• ${capability.title}: ${capability.consequence}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "That is the whole cost. Chat and every tool that does not " +
                        "need these still work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!interest) {
            HorizontalDivider()
            // The only place the app turns asking ON. Until this is tapped
            // there is no recurring prompt for anything, which is the state a
            // user with no scheduled task and no background work should be in.
            Text(
                "Nothing here is being asked for on a schedule. If you want the " +
                    "app to offer these when a capability would be useful, say so " +
                    "below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { BackgroundConsent.noteBackgroundInterest(context) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Offer these when I need them")
            }
        }

        if (hasScheduledTask) {
            HorizontalDivider()
            Text(
                buildString {
                    append("You have scheduled tasks. ")
                    if (runningTaskId != null) {
                        append("One is running right now.")
                    }
                    if (!granted.getValue(BackgroundCapability.ExactAlarms)) {
                        append(
                            " They will not be armed until Android allows exact " +
                                "alarms for this app — open the Scheduled tasks " +
                                "screen for the exact wording and a button that " +
                                "goes to the right place.",
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Android only lets you grant and revoke these from its own Settings " +
                "screens, so the buttons take you there. Come back and this page " +
                "updates. Revoking a switch here does not uninstall anything and " +
                "does not affect the model or your chats.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One capability: what it is, whether it is on, and the three things a user can
 * do about it.
 *
 * The three actions are distinct on purpose and are not collapsed into one
 * button, because they are three different intents:
 *
 *  - **Turn on** — I want this capability. Opens the system screen.
 *  - **Not now** — do not ask me again *about this one*. It does not record
 *    blanket interest: a user who refuses notifications may well still want
 *    alarms, and a refusal of one row is not a statement about the others.
 *  - **Ask me again** — undo the dismissal for this one, explicitly.
 *
 * Revocation is the fourth state and is not a choice: when the capability is
 * already on there is a "Turn off in Settings" and an explanation of why the
 * app cannot do it itself, rather than a button that would appear to work.
 */
@Composable
private fun CapabilityCard(
    capability: BackgroundCapability,
    isGranted: Boolean,
    answer: BackgroundConsent.Answer,
    /**
     * Records that the user has engaged with this capability, which stops the
     * app asking again.
     *
     * Wired to BOTH the positive and the negative button, and that is
     * deliberate. A user who taps "Turn on", changes their mind on the Settings
     * screen and comes back has given their answer already — they just gave it
     * in a different process. Re-prompting them on the next launch would treat
     * "I went to Settings and said no" as "I have never been asked", which is
     * the bug this whole file is about.
     */
    onEngage: () -> Unit,
) {
    val context = LocalContext.current
    val dismissed = answer == BackgroundConsent.Answer.Dismissed

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(capability.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (isGranted) {
                    "On. ${capability.enables}"
                } else {
                    "Off. ${capability.enables}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Kept visible in BOTH states, and stated as a negative. The
            // sentence that stops a user reading a granted row as "the app is
            // now watching everything" is the one about what it does not do.
            Text(
                capability.doesNotEnable,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                isGranted -> {
                    // Revocation. An app cannot revoke its own
                    // NotificationListenerService binding — that is a system
                    // service and the switch is in system Settings — so the
                    // honest offer is the same screen, with the reason named
                    // rather than a button that appears to do nothing.
                    OutlinedButton(
                        onClick = { requestCapability(context, capability) },
                    ) {
                        Text("Turn off in Settings")
                    }
                    Text(
                        "Android will not let the app switch this off itself — " +
                            "only you can, on the screen the button opens. " +
                            "Until you do, it stays on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                dismissed -> {
                    Text(
                        "You asked not to be asked about this again. ${capability.consequence}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    TextButton(onClick = { BackgroundConsent.forget(context, capability) }) {
                        Text("Ask me again")
                    }
                }

                else -> {
                    Button(
                        onClick = {
                            onEngage()
                            requestCapability(context, capability)
                        },
                    ) {
                        Text("Turn on")
                    }
                    TextButton(onClick = { onEngage() }) {
                        Text("Not now — don't ask again")
                    }
                    Text(
                        capability.consequence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Opens the settings screen for [capability].
 *
 * One function for both "turn on" and "turn off" because the destination is
 * genuinely the same: Android exposes the grant and the revoke as one switch
 * on one screen, and an app that pretended otherwise would be offering a
 * button that cannot deliver.
 *
 * The return value is deliberately ignored at the call site. A failed launch is
 * an OEM build without the settings activity; there is nothing better to do
 * than leave the row reading "Off", which is still true.
 */
private fun requestCapability(context: Context, capability: BackgroundCapability) {
    val intent = when (capability) {
        BackgroundCapability.Notifications ->
            LocalNotificationListenerService.settingsIntent(context)

        BackgroundCapability.ExactAlarms ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The same action `intentForTaskAction` uses, so there is one
                // destination for this capability in the app and the two
                // screens cannot send the user to different places.
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            } else {
                // `canScheduleExactAlarms()` is unconditionally true below 31,
                // so this branch is unreachable from a rendered row. It exists
                // so an OEM quirk cannot turn the button into a no-op with no
                // explanation.
                return
            }
    }
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
