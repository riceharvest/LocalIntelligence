package dev.localintelligence.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
import dev.localintelligence.app.execution.TaskAlarmScheduler
import dev.localintelligence.core.tool.contracts.PlatformRequirement
import dev.localintelligence.core.tool.contracts.ToolPermissions

/**
 * The screen where a permission the user was told about can actually be granted.
 *
 * ## What this file was
 *
 * A prompt that nothing called. [PermissionScreen] had a single-permission card,
 * a `describePermission()` map, and zero references anywhere in the repository —
 * the only mention of it in the whole app was a KDoc sentence in `MainActivity`
 * saying that permission requests were "gated on a tool actually being invoked".
 * Nothing ever invoked one. A user told "grant Contacts" had exactly one thing
 * they could do with that sentence, which was nothing, and the alternative was
 * hunting through Android Settings for a switch this app had never asked about.
 *
 * The tools were already honest about the refusal. `contacts.search` returns a
 * [dev.localintelligence.core.tool.contracts.PermissionDenial] observation naming
 * `android.permission.READ_CONTACTS` and the exact Settings path. That is
 * correct for a tool and useless to a person, because knowing the permission's
 * name is not the same as being able to grant it. This file is the other half.
 *
 * ## The one rule: derive the permission from the requirement, never restate it
 *
 * Every row below is built from a [PlatformRequirement] constant out of
 * [ToolPermissions] — the same object the nine tool families pass to
 * `PermissionDenial.observation(...)` and to `PlatformGrant.isGranted(...)`.
 * The string handed to `requestPermissions` is `requirement.permission`, read
 * off that object. It is not retyped here.
 *
 * This is the whole reason the previous `describePermission()` was deleted rather
 * than kept: it was a second list of permission names, in a different file, in a
 * different module, that a tool could disagree with. A screen that says "Contacts"
 * while the tool says `READ_CONTACTS` is a bug that only shows up on a user's
 * phone. One source, one name, no drift.
 *
 * ## The second rule: a button must be able to do the thing it says
 *
 * Not every capability on this list can be obtained by a runtime dialog, and
 * pretending otherwise is worse than not offering the button at all. The three
 * routes, and why each is what it is:
 *
 *  - **[Grant.Dialog]** — a real `requestPermissions` call obtains it. Contacts,
 *    calendar read, calendar write, and `POST_NOTIFICATIONS` on API 33+.
 *  - **[Grant.Screen]** — only a human on a system screen can obtain it.
 *    Notification-listener access and exact-alarm access are both *special
 *    access* grants: there is no `requestPermissions` call for either, and
 *    Android does not even show a dialog for them. The button goes to Settings
 *    and the card says which switch to flip.
 *  - **[Grant.NoAction]** — there is nothing to grant. `VIBRATE` and `INTERNET`
 *    are normal permissions granted by being in the manifest, so a card with an
 *    "Allow" button on it would be inviting the user to go looking for a switch
 *    that does not exist. The card says that, in the requirement's own words.
 *
 * ## The three outcomes, and the one that is easy to get wrong
 *
 * A denial is not one thing. See [statusOf] for the full argument; the short
 * version is that `shouldShowRequestPermissionRationale` returns **false both
 * before the first ask and after the user ticks "don't ask again"**, so on its
 * own it cannot tell "never asked" from "can never be asked again". This file
 * records the fact that it asked, in [markAsked], and only then is a false
 * rationale read as permanent. Permanent means exactly one thing: stop showing a
 * dialog and send the user to the app's page in Settings. Re-prompting a
 * permanently-denied permission shows a dialog that the system answers
 * immediately and silently — the button appears broken, which is the exact
 * experience this screen exists to end.
 *
 * ## Why state is re-read on every resume
 *
 * Same reason [dev.localintelligence.app.BackgroundAccessActivity] does it, and
 * for the same reason it matters more here: most of the rows on this screen are
 * granted in **another process**, on a screen this app does not own. A value
 * read once at composition is wrong the moment the user comes back, and a
 * permissions screen that tells someone who just granted something that it is
 * still off is the single most credibility-destroying thing this file could do.
 */
class PermissionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalIntelligenceTheme {
                PermissionScreen(onBack = { finish() })
            }
        }
    }
}

/**
 * The permission screen.
 *
 * @param onBack wired to [ComponentActivity.finish] when hosted by
 *   [PermissionActivity], and to `nav.popBackStack()` when a host prefers to
 *   run this as a NavHost destination instead. Both call shapes work; see the
 *   PR description for the two-line NavHost wiring.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }

    // Bumped on every ON_RESUME. The statuses are re-derived from a token
    // rather than stored, so there is exactly one place that decides what a
    // permission's state is and no cached copy that can disagree with the
    // platform.
    var refreshToken by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val entries = remember { permissionEntries() }
    val asked = remember(refreshToken) { askedPermissions(context) }
    val statuses = remember(refreshToken) {
        entries.associateWith { entry ->
            statusOf(context, entry, asked, activity)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // The map is deliberately ignored. The authoritative answer is a fresh
        // `checkSelfPermission`, and trusting the callback's booleans would mean
        // the screen believed a dialog over the platform — the same class of
        // bug as a tool trusting `ToolContext.permissionGranted`.
        refreshToken++
    }

    // Everything Android will still ask about, in one batch. Permanently
    // denied permissions are excluded here precisely so the "Allow the rest"
    // button cannot include one and show a dialog that does nothing.
    val batchable = remember(refreshToken) {
        entries.mapNotNull { entry ->
            val dialog = entry.grant as? Grant.Dialog ?: return@mapNotNull null
            dialog.permission.takeIf { statusOf(context, entry, asked, activity).canAsk }
        }
    }

    fun ask(permissions: List<String>) {
        if (permissions.isEmpty()) return
        // Marked BEFORE the launch, not after. If the process dies while the
        // dialog is up, the system still remembers that it asked — and a screen
        // that thinks it never did will offer a dialog the system silently
        // refuses. Asking first means that case degrades into "open Settings",
        // which is the correct answer anyway.
        markAsked(context, permissions)
        launcher.launch(permissions.toTypedArray())
    }

    val outstanding = statuses.count { !it.value.isSatisfied }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (outstanding == 0) {
                    "Everything the assistant's tools need is switched on. Nothing to do here."
                } else {
                    "$outstanding of ${entries.size} capabilities are off. Each one below says " +
                        "exactly how to turn it on, and whether Android will ask you or send you " +
                        "to Settings."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (batchable.size > 1) {
                OutlinedButton(
                    onClick = { ask(batchable) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ask for the ${batchable.size} Android can prompt for")
                }
            }

            HorizontalDivider()

            entries.forEach { entry ->
                val status = statuses.getValue(entry)
                PermissionCard(
                    entry = entry,
                    status = status,
                    onAsk = { entry.grant.permissionOrNull()?.let { ask(listOf(it)) } },
                    onOpenSettings = { openAppSettings(context) },
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// What can be granted, and how
// ----------------------------------------------------------------------------

/**
 * The one way a [PlatformRequirement] can be obtained, decided per requirement.
 *
 * This is deliberately a property of the requirement rather than a property of
 * the screen, because "can a dialog get this" is a fact about the capability,
 * not about the UI. Putting it on the entry means a card cannot render a dialog
 * button for a capability that has no dialog, which is the specific dishonesty
 * this screen is required not to have.
 */
sealed interface Grant {

    /**
     * A runtime permission dialog obtains it.
     *
     * [permission] is `PlatformRequirement.permission` verbatim, so this is the
     * same string the tool passes to `ContextCompat.checkSelfPermission` and
     * the same one [dev.localintelligence.core.tool.contracts.PermissionDenial]
     * puts in its refusal message.
     */
    data class Dialog(val permission: String) : Grant

    /**
     * Only a system screen can change it; [open] returns false when that screen
     * is missing on this device.
     *
     * Returning false rather than launching-and-hoping is deliberate: an OEM
     * build can genuinely lack the exact-alarm settings activity, and a button
     * that throws is worse than a sentence that says where to go instead.
     */
    data class Screen(val open: (Context) -> Boolean) : Grant

    /**
     * There is nothing for the user to do. Normal permissions, and states that
     * are not permissions at all.
     */
    data object NoAction : Grant
}

private fun Grant.permissionOrNull(): String? = (this as? Grant.Dialog)?.permission

/**
 * One capability the assistant's tools can need, and the human name for it.
 *
 * The permission is [requirement] and never a separate field, so there is no way
 * to construct a card whose title and whose requested permission disagree.
 */
data class PermissionEntry(
    val requirement: PlatformRequirement,
    val title: String,
    /** Why this app wants it, tied to the action a tool performs. */
    val why: String,
    val grant: Grant,
)

/**
 * `POST_NOTIFICATIONS` has no [ToolPermissions] constant yet.
 *
 * ## Why this is a local definition and not a convenience
 *
 * Every other row on this screen names a `ToolPermissions` constant, and this
 * one does not, which is a real inconsistency and not a stylistic one:
 * `POST_NOTIFICATIONS` is a runtime permission the app declares, the alarm
 * notifier catches `SecurityException` on, and `areNotificationsAllowed` reads
 * in `app/data/TaskReadiness.kt` — and core has no [PlatformRequirement] for
 * it. The honest fix belongs in core, next to the others, and the exact edit is
 * in this PR's description for whoever owns that file.
 *
 * Until then it is defined here from [Manifest.permission.POST_NOTIFICATIONS],
 * the platform constant, rather than a retyped string — so it matches the
 * manifest declaration and `TaskReadiness` by construction and not by luck.
 */
private val NOTIFICATIONS = PlatformRequirement(
    permission = Manifest.permission.POST_NOTIFICATIONS,
    capability = "Permission to post notifications",
    grantInstructions = "Settings > Apps > LocalIntelligence > Notifications, and turn " +
        "notifications on for this app.",
)

/**
 * Every capability the shipped tools can be refused, in the order a person
 * should meet them: things Android will prompt for, then things only a Settings
 * screen can grant, then things with no switch at all.
 *
 * The ordering is deliberate. A screen that opens with three cards whose only
 * action is "there is nothing you can do here" reads, correctly and
 * unhelpfully, as an apology. The rows a user can actually act on come first.
 *
 * ## The one omission worth naming
 *
 * `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` and `READ_MEDIA_AUDIO` are declared in
 * the manifest and have [ToolPermissions] constants — and **no tool declares a
 * requirement on any of them**. The file tools gate on
 * `ToolPermissions.DOCUMENT_READ`, which is deliberately `permission = null`
 * because on Android 13+ none of those media permissions can reach a document.
 *
 * So they are not on this screen. Adding a row that asks for a permission
 * nothing needs would be the same "hunt for a switch" failure as a `VIBRATE`
 * button, only with more taps. If a tool ever grows a real requirement on one of
 * them, the [Grant] belongs here next to the tool that needs it.
 */
fun permissionEntries(sdkInt: Int = Build.VERSION.SDK_INT): List<PermissionEntry> = buildList {
    // ---- Android will show a dialog for these. ----
    add(
        PermissionEntry(
            requirement = ToolPermissions.CONTACTS,
            title = "Contacts",
            why = "Lets the assistant look up a name or phone number in your contacts when " +
                "you ask it a question that needs one. It reads nothing until a tool runs, " +
                "and nothing leaves the phone — the model runs here.",
            grant = Grant.Dialog(ToolPermissions.READ_CONTACTS),
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.CALENDAR_READ,
            title = "Calendar, reading",
            why = "Lets the assistant check your schedule when you ask what is on today. " +
                "Read-only: it cannot change an event with this one.",
            grant = Grant.Dialog(ToolPermissions.READ_CALENDAR),
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.CALENDAR_WRITE,
            title = "Calendar, writing",
            why = "Lets the assistant add an event when you ask it to. The assistant still " +
                "has to approve the specific tool call before anything is written.",
            grant = Grant.Dialog(ToolPermissions.WRITE_CALENDAR),
        ),
    )
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionEntry(
                requirement = NOTIFICATIONS,
                title = "Notifications",
                why = "Lets a finished task, a set alarm, or a reminder reach you. Without " +
                    "it a long task completes in silence and an alarm is indistinguishable " +
                    "from one that was never set.",
                grant = Grant.Dialog(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    } else {
        // Below API 33 there is no such permission. Declaring it in the manifest
        // was enough, and a card offering to request something that cannot be
        // requested would return "denied" forever.
        add(
            PermissionEntry(
                requirement = NOTIFICATIONS,
                title = "Notifications",
                why = "Android ${android.os.Build.VERSION.RELEASE} does not ask for " +
                    "notification permission, so this is already on. From Android 13 the " +
                    "app asks you for it here.",
                grant = Grant.NoAction,
            ),
        )
    }

    // ---- Only a system screen can grant these. ----
    add(
        PermissionEntry(
            requirement = ToolPermissions.NOTIFICATION_LISTENER,
            title = "Notification access",
            why = "Lets the assistant read notifications when you ask it to, and reply to " +
                "ones that offer a reply button. Android has no dialog for this at all — it " +
                "is a switch in the system Settings app, which is why the button takes you " +
                "there instead.",
            grant = Grant.Screen { context ->
                LocalNotificationListenerService.requestConsent(context)
            },
        ),
    )
    if (sdkInt >= Build.VERSION_CODES.S) {
        add(
            PermissionEntry(
                requirement = ToolPermissions.EXACT_ALARM,
                title = "Exact alarms",
                why = "Lets a task be set for a specific minute. Android calls this " +
                    "\"Alarms & reminders\" and treats it as special access, so there is no " +
                    "dialog — the button opens the page for this app where you turn it on.",
                grant = Grant.Screen { context ->
                    openSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                },
            ),
        )
    } else {
        add(
            PermissionEntry(
                requirement = ToolPermissions.EXACT_ALARM,
                title = "Exact alarms",
                why = "Android ${android.os.Build.VERSION.RELEASE} lets any app set an exact " +
                    "alarm, so there is nothing to grant here. Android 12 started asking.",
                grant = Grant.NoAction,
            ),
        )
    }

    // ---- Documents: a dialog before Android 13, the file picker after. ----
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionEntry(
                requirement = ToolPermissions.DOCUMENT_READ,
                title = "Your documents",
                why = "There is no Android dialog for this. A document reaches this app by " +
                    "being handed over — share it to LocalIntelligence from any app's share " +
                    "menu, or pick it with the system file picker. On Android 13 and newer " +
                    "no permission, including the media ones, can list a document you have " +
                    "not shared, so this row stays off no matter what you allow.",
                grant = Grant.NoAction,
            ),
        )
    } else {
        add(
            PermissionEntry(
                requirement = ToolPermissions.DOCUMENT_READ,
                title = "Your documents, reading",
                why = "Lets the assistant list and read documents and downloads on this " +
                    "phone. Android asks you directly.",
                grant = Grant.Dialog(Manifest.permission.READ_EXTERNAL_STORAGE),
            ),
        )
    }
    add(
        PermissionEntry(
            requirement = ToolPermissions.DOCUMENT_WRITE,
            title = "Your documents, writing",
            why = "From Android 10 the assistant can create files in Downloads without " +
                "asking, and can overwrite only documents you have shared with it. On older " +
                "phones it needs the storage permission, which Android asks for directly.",
            grant = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Grant.NoAction
            } else {
                Grant.Dialog(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            },
        ),
    )

    // ---- Nothing to grant. Said plainly rather than dressed up. ----
    add(
        PermissionEntry(
            requirement = ToolPermissions.CLIPBOARD_BACKGROUND_READ,
            title = "Clipboard in the background",
            why = "Not a permission — there is no switch anywhere. Android only lets an app " +
                "read the clipboard while it has the screen's focus, and you are looking at " +
                "this page, so it can read it right now. It stops the moment you switch away.",
            grant = Grant.NoAction,
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.VIBRATE_REQUIREMENT,
            title = "Vibration",
            why = "Granted when the app was installed and cannot be taken away at runtime. " +
                "If a tool ever says this is missing, the app was installed without it, " +
                "which is a bug in the app rather than something to change in Settings.",
            grant = Grant.NoAction,
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.INTERNET_REQUIREMENT,
            title = "Network",
            why = "Granted at install, for the one tool that fetches a web page. Everything " +
                "else — the model, your chats, your files — stays on this phone. There is " +
                "no switch for it, because there is no switch for it.",
            grant = Grant.NoAction,
        ),
    )
}

/**
 * Launches [intent] if this device has a screen for it.
 *
 * Returning false instead of throwing is the point: a manufacturer build can
 * genuinely omit the exact-alarm settings activity, and the caller needs to be
 * able to say so rather than crash or pretend the button worked.
 */
private fun openSettings(context: Context, intent: Intent): Boolean {
    if (intent.resolveActivity(context.packageManager) == null) return false
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }
}

// ----------------------------------------------------------------------------
// The three outcomes
// ----------------------------------------------------------------------------

/**
 * What is actually true of one capability right now.
 *
 * Four states rather than three because "not granted" genuinely splits: asking
 * again works in one case, and is a no-op that shows an instantly-dismissed
 * dialog in the other. Collapsing them is what produces a button that appears
 * broken.
 */
enum class PermissionStatus {
    /** The platform says yes. */
    GRANTED,

    /** Never asked. A dialog is still available. */
    NOT_ASKED,

    /** Asked, said no, and Android will ask again. */
    DENIED,

    /**
     * Asked, said no, and told Android not to ask again. The only honest next
     * action is the app's page in Settings.
     */
    DENIED_FOREVER,

    /**
     * No runtime permission applies, so there is nothing to ask about. Rendered
     * from the requirement's own [PlatformRequirement.grantInstructions].
     */
    NOT_A_PERMISSION,
    ;

    val isSatisfied: Boolean get() = this == GRANTED || this == NOT_A_PERMISSION

    /** True when a runtime dialog would still do something. */
    val canAsk: Boolean get() = this == NOT_ASKED || this == DENIED
}

/**
 * Decides one capability's state, from the platform and from the record of
 * whether this app has ever asked.
 *
 * ## Why `shouldShowRequestPermissionRationale` alone cannot answer this
 *
 * The platform's answer is false in two different situations: before the app has
 * ever asked, and after the user has ticked "don't ask again". Those want
 * opposite behaviour — one should offer a dialog, the other must go to Settings
 * — and there is no API that separates them. `ActivityCompat` does not expose
 * it, the manifest does not record it, and reading the app-ops state is not
 * possible from an app.
 *
 * So this records the fact that it asked, in [markAsked], and treats a false
 * rationale as permanent only when a request is known to have been made. When
 * [activity] is null — the composable is hosted somewhere with no Activity to
 * ask, which should not happen but is not worth crashing over — it falls back
 * to the optimistic reading, because offering a dialog that might be refused is
 * strictly better than asserting a permanence it cannot prove.
 */
private fun statusOf(
    context: Context,
    entry: PermissionEntry,
    asked: Set<String>,
    activity: Activity?,
): PermissionStatus {
    val permission = entry.grant.permissionOrNull()
        ?: return if (probeSatisfied(context, entry)) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.NOT_A_PERMISSION
        }
    if (isPermissionHeld(context, permission)) return PermissionStatus.GRANTED
    if (activity == null) return PermissionStatus.DENIED
    val rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    return when {
        !asked.contains(permission) -> PermissionStatus.NOT_ASKED
        rationale -> PermissionStatus.DENIED
        else -> PermissionStatus.DENIED_FOREVER
    }
}

/**
 * Reads the truth for a capability that has no runtime permission.
 *
 * Every case here is a real platform query rather than a guess, for the same
 * reason `AndroidPlatformGrant` exists: a tool that assumes a capability is off
 * reports an empty list, and the model tells the user they have no documents.
 */
private fun probeSatisfied(context: Context, entry: PermissionEntry): Boolean {
    val permission = entry.requirement.permission
    return when (entry.requirement) {
        ToolPermissions.NOTIFICATION_LISTENER -> LocalNotificationListenerService.isGranted(context)
        ToolPermissions.EXACT_ALARM -> TaskAlarmScheduler.canScheduleExactAlarms(context)

        // A SAF grant is per-URI, not a device-wide switch, so "satisfied" has no
        // single answer and the row says so instead. Reporting false here is what
        // keeps the card in its honest "there is no dialog for this" state rather
        // than claiming a capability the tools do not have.
        ToolPermissions.DOCUMENT_READ -> false
        ToolPermissions.DOCUMENT_WRITE -> false

        // The clipboard while this screen is on it. `LocalContext` is this app's
        // Activity, so the app demonstrably has focus, and the answer to "can the
        // clipboard be read" right now is yes.
        ToolPermissions.CLIPBOARD_BACKGROUND_READ -> true

        // Everything else with no runtime permission is a normal permission:
        // VIBRATE and INTERNET are granted by being in the manifest, and the
        // platform agrees. A null here means a requirement with neither a
        // permission nor a probe, which `AndroidPlatformGrant` also treats as
        // ungated.
        else -> permission != null && isPermissionHeld(context, permission)
    }
}

/** `ContextCompat.checkSelfPermission`, which is honest on every API level. */
private fun isPermissionHeld(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

// ----------------------------------------------------------------------------
// The record of having asked
// ----------------------------------------------------------------------------

private const val PREFS_NAME = "permission_prompt_state"
private const val KEY_ASKED = "asked_permissions"

/**
 * The permissions this app has put a dialog in front of the user for.
 *
 * Persisted rather than held in a `remember` block, and that is the whole
 * design. A rotation, a process death, or a trip to Settings and back all
 * destroy composition state; without this, the screen forgets it asked, reads a
 * false rationale as "never asked", and offers a dialog Android will answer
 * instantly and silently. The user's next impression of this screen would be
 * that its button does nothing.
 */
private fun askedPermissions(context: Context): Set<String> =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(KEY_ASKED, emptySet())
        .orEmpty()

private fun markAsked(context: Context, permissions: List<String>) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(KEY_ASKED, askedPermissions(context) + permissions)
        .apply()
}

// ----------------------------------------------------------------------------
// Rendering
// ----------------------------------------------------------------------------

@Composable
private fun PermissionCard(
    entry: PermissionEntry,
    status: PermissionStatus,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // `Arrangement.Top` pins the status label to the top of the row
            // instead of centring it, so a two-line capability name and a
            // one-word status do not end up visually centred against each
            // other. `horizontalArrangement` does the spacing between the two.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = when (status) {
                        PermissionStatus.GRANTED,
                        PermissionStatus.NOT_A_PERMISSION,
                        -> MaterialTheme.colorScheme.onSurfaceVariant

                        PermissionStatus.DENIED_FOREVER -> MaterialTheme.colorScheme.error
                        PermissionStatus.NOT_ASKED, PermissionStatus.DENIED ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text(
                text = entry.why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (status) {
                PermissionStatus.GRANTED -> Unit

                PermissionStatus.NOT_A_PERMISSION -> Text(
                    // The requirement's own words, so this screen and the
                    // sentence a tool hands the model cannot say different
                    // things about the same capability.
                    text = entry.requirement.grantInstructions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PermissionStatus.NOT_ASKED -> {
                    Text(
                        "Android will ask you, with its own dialog, the first time you tap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAsk) { Text("Allow") }
                }

                PermissionStatus.DENIED -> {
                    Text(
                        "You said no. Android will ask again if you tap — nothing is " +
                            "permanently off yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAsk) { Text("Ask again") }
                }

                PermissionStatus.DENIED_FOREVER -> {
                    Text(
                        "You told Android not to ask about this again, so tapping would only " +
                            "flash a dialog and dismiss it. The button opens this app's page " +
                            "in Settings, where the permission is still listed — or removed " +
                            "entirely, if you want it gone for good.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onOpenSettings) {
                        Text("Open app settings")
                    }
                }
            }
        }
    }
}

private val PermissionStatus.label: String
    get() = when (this) {
        PermissionStatus.GRANTED -> "On"
        PermissionStatus.NOT_ASKED -> "Off"
        PermissionStatus.DENIED -> "Off"
        PermissionStatus.DENIED_FOREVER -> "Blocked"
        PermissionStatus.NOT_A_PERMISSION -> "Nothing to grant"
    }

/**
 * The app's own page in Android Settings, where a permanently-denied permission
 * can be re-enabled.
 *
 * The per-permission deep link (`ACTION_APP_NOTIFICATION_SETTINGS` and friends)
 * is not usable here: there is no single documented one for an arbitrary
 * dangerous permission, and guessing a package-specific extra produces a screen
 * that opens on some builds and throws on others. The app-info page is the one
 * route the platform actually guarantees, and it lists every permission.
 */
private fun openAppSettings(context: Context) {
    openSettings(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null)),
    )
}

// ----------------------------------------------------------------------------
// Activity plumbing
// ----------------------------------------------------------------------------

/**
 * Unwraps the Activity out of whatever [androidx.compose.ui.platform.LocalContext]
 * happens to be.
 *
 * `shouldShowRequestPermissionRationale` needs a real Activity and a
 * `ContextWrapper` is not one, and the wrapper chain is a platform detail
 * nobody should have to know about at the call site.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
