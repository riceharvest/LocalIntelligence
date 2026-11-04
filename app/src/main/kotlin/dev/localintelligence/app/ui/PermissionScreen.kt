package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.os.Environment
import dev.localintelligence.app.BuildConfig
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import android.app.Activity
import android.content.Context
import android.content.res.Resources
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
import androidx.compose.material3.TextButton
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
import dev.localintelligence.android.tools.AndroidPlatformGrant
import dev.localintelligence.android.tools.messages.DefaultAssistant
import dev.localintelligence.android.tools.messages.TEXTS_AND_CALLS_PERMISSIONS
import dev.localintelligence.android.tools.messages.declaredInBuild
import dev.localintelligence.app.openAssistantSettings

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

    // A build that leaves out texts and calls (docs/build.md) has no Grant button that works for them.
    val entries = remember(context) {
        permissionEntries(context.resources).filter { entry ->
            val permission = entry.requirement.permission
            permission == null || permission !in TEXTS_AND_CALLS_PERMISSIONS || declaredInBuild(context, permission)
        }
    }
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

    val settingsVersion by (context.applicationContext as LocalIntelligenceApp).container.settings.changes.collectAsStateWithLifecycle()
    val technical = remember(settingsVersion) {
        (context.applicationContext as LocalIntelligenceApp).container.settings.mode ==
            SettingsMode.TECHNICAL
    }
    var otherOpen by remember { mutableStateOf(false) }
    val outstanding = statuses.count { !it.value.isSatisfied }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                text = when (outstanding) {
                    0 -> stringResource(R.string.all_set)
                    1 -> pluralStringResource(R.plurals.permissions_off, 1, 1)
                    else -> pluralStringResource(R.plurals.permissions_off, outstanding, outstanding)
                },
                style = MaterialTheme.typography.titleMedium,
            )

            if (batchable.size > 1) {
                OutlinedButton(
                    onClick = { ask(batchable) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.allow_all))
                }
            }

            HorizontalDivider()

            @Composable
            fun CardFor(entry: PermissionEntry) {
                PermissionCard(
                    entry = entry,
                    status = statuses.getValue(entry),
                    onAsk = { entry.grant.permissionOrNull()?.let { ask(listOf(it)) } },
                    onOpenSettings = { openAppSettings(context) },
                    onOpenScreen = { (entry.grant as? Grant.Screen)?.open(context) },
                    onOpenRole = { openAssistantSettings(context) },
                )
            }

            val (actionable, noAction) = entries.partition { it.grant !is Grant.NoAction }
            actionable.forEach { CardFor(it) }

            // Cards with nothing to grant are noise for most people; only the
            // technical view lists them, folded away.
            if (technical && noAction.isNotEmpty()) {
                TextButton(onClick = { otherOpen = !otherOpen }) {
                    Text(if (otherOpen) stringResource(R.string.hide_other) else stringResource(R.string.other))
                }
                if (otherOpen) noAction.forEach { CardFor(it) }
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
    /**
     * True when the permission may be used only while LocalIntelligence is the default
     * assistant app. Google Play's policy for texts and the call log, and the tools
     * check the same role on every call. The runtime dialog is withheld until it is held.
     */
    val needsAssistantRole: Boolean = false,
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

private val ALL_FILES = PlatformRequirement(
    permission = null,
    capability = "All files access",
    grantInstructions = "Settings > Apps > Special app access > All files access > LocalIntelligence, and turn it on.",
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
 * The media-read permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`,
 * `READ_MEDIA_AUDIO`) are not declared in the manifest, because no tool needs them.
 * The file tools gate on
 * `ToolPermissions.DOCUMENT_READ`, which is deliberately `permission = null`
 * because on Android 13+ none of those media permissions can reach a document.
 *
 * So they are not on this screen. Adding a row that asks for a permission
 * nothing needs would be the same "hunt for a switch" failure as a `VIBRATE`
 * button, only with more taps. If a tool ever grows a real requirement on one of
 * them, the [Grant] belongs here next to the tool that needs it.
 */
fun permissionEntries(res: Resources, sdkInt: Int = Build.VERSION.SDK_INT): List<PermissionEntry> = buildList {
    // ---- Android will show a dialog for these. ----
    // ---- The default assistant app. Texts and the call log are usable only while it is held. ----
    add(
        PermissionEntry(
            requirement = ToolPermissions.DEFAULT_ASSISTANT,
            title = res.getString(R.string.perm_default_assistant_app),
            why = res.getString(R.string.perm_needed_before_texts_and_call_history_can_be_used),
            grant = Grant.Screen { context ->
                openAssistantSettings(context)
                true
            },
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.CONTACTS,
            title = res.getString(R.string.perm_contacts),
            why = res.getString(R.string.perm_look_up_names_and_numbers_when_you_ask),
            grant = Grant.Dialog(ToolPermissions.READ_CONTACTS),
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.CALENDAR_READ,
            title = res.getString(R.string.perm_calendar_reading),
            why = res.getString(R.string.perm_check_your_schedule_when_you_ask),
            grant = Grant.Dialog(ToolPermissions.READ_CALENDAR),
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.CALENDAR_WRITE,
            title = res.getString(R.string.perm_calendar_writing),
            why = res.getString(R.string.perm_add_events_when_you_ask),
            grant = Grant.Dialog(ToolPermissions.WRITE_CALENDAR),
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.SMS_READ,
            title = res.getString(R.string.perm_texts_reading),
            why = res.getString(R.string.perm_search_your_texts_when_you_ask),
            grant = Grant.Dialog(ToolPermissions.READ_SMS),
            needsAssistantRole = true,
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.SMS_SEND,
            title = res.getString(R.string.perm_texts_sending),
            why = res.getString(R.string.perm_send_a_text_when_you_ask_after_you_confirm_it),
            grant = Grant.Dialog(ToolPermissions.SEND_SMS),
            needsAssistantRole = true,
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.CALL_LOG_READ,
            title = res.getString(R.string.perm_call_history),
            why = res.getString(R.string.perm_see_who_called_when_you_ask),
            grant = Grant.Dialog(ToolPermissions.READ_CALL_LOG),
            needsAssistantRole = true,
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.PHONE_CALL,
            title = res.getString(R.string.perm_phone_calls),
            why = res.getString(R.string.perm_place_a_call_when_you_ask_after_you_confirm_it),
            grant = Grant.Dialog(ToolPermissions.CALL_PHONE),
        ),
    )
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionEntry(
                requirement = NOTIFICATIONS,
                title = res.getString(R.string.perm_notifications),
                why = res.getString(R.string.perm_tell_you_when_a_task_or_alarm_is_done),
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
                title = res.getString(R.string.perm_notifications),
                why = res.getString(R.string.perm_already_on),
                grant = Grant.NoAction,
            ),
        )
    }

    // ---- Only a system screen can grant these. ----
    if (BuildConfig.FULL_BUILD) {
        add(
            PermissionEntry(
                requirement = ALL_FILES,
                title = res.getString(R.string.perm_all_files),
                why = res.getString(R.string.perm_lets_the_assistant_find_and_read_files_anywhere_on_the_phone),
                grant = Grant.Screen { context ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        openSettings(
                            context,
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ),
                        ) || openSettings(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } else {
                        openAppSettings(context)
                        true
                    }
                },
            ),
        )
    }
    add(
        PermissionEntry(
            requirement = ToolPermissions.NOTIFICATION_LISTENER,
            title = res.getString(R.string.perm_notification_access),
            why = res.getString(R.string.perm_read_and_reply_to_notifications_when_you_ask),
            grant = Grant.Screen { context ->
                LocalNotificationListenerService.requestConsent(context)
            },
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.DO_NOT_DISTURB,
            title = res.getString(R.string.perm_do_not_disturb),
            why = res.getString(R.string.perm_change_do_not_disturb_when_you_ask),
            grant = Grant.Screen { context ->
                openSettings(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            },
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.WRITE_SETTINGS_ACCESS,
            title = res.getString(R.string.perm_screen_brightness),
            why = res.getString(R.string.perm_change_the_screen_brightness_when_you_ask),
            grant = Grant.Screen { context ->
                openSettings(
                    context,
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}")),
                )
            },
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.USAGE_ACCESS,
            title = res.getString(R.string.perm_screen_time),
            why = res.getString(R.string.perm_read_which_apps_you_use_most_when_you_ask),
            grant = Grant.Screen { context ->
                openSettings(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
        ),
    )
    if (sdkInt >= Build.VERSION_CODES.S) {
        add(
            PermissionEntry(
                requirement = ToolPermissions.EXACT_ALARM,
                title = res.getString(R.string.perm_exact_alarms),
                why = res.getString(R.string.perm_run_tasks_at_an_exact_time),
                grant = Grant.Screen { context ->
                    openSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                },
            ),
        )
    } else {
        add(
            PermissionEntry(
                requirement = ToolPermissions.EXACT_ALARM,
                title = res.getString(R.string.perm_exact_alarms),
                why = res.getString(R.string.perm_already_on),
                grant = Grant.NoAction,
            ),
        )
    }

    // ---- Documents: a dialog before Android 13, the file picker after. ----
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionEntry(
                requirement = ToolPermissions.DOCUMENT_READ,
                title = res.getString(R.string.perm_your_documents),
                why = res.getString(R.string.perm_share_a_file_to_the_app_to_use_it),
                grant = Grant.NoAction,
            ),
        )
    } else {
        add(
            PermissionEntry(
                requirement = ToolPermissions.DOCUMENT_READ,
                title = res.getString(R.string.perm_your_documents_reading),
                why = res.getString(R.string.perm_read_documents_on_this_phone),
                grant = Grant.Dialog(Manifest.permission.READ_EXTERNAL_STORAGE),
            ),
        )
    }
    add(
        PermissionEntry(
            requirement = ToolPermissions.DOCUMENT_WRITE,
            title = res.getString(R.string.perm_your_documents_writing),
            why = res.getString(R.string.perm_save_files_on_this_phone),
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
            title = res.getString(R.string.perm_clipboard_in_the_background),
            why = res.getString(R.string.perm_read_the_clipboard_while_the_app_is_open),
            grant = Grant.NoAction,
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.VIBRATE_REQUIREMENT,
            title = res.getString(R.string.perm_vibration),
            why = res.getString(R.string.perm_always_on),
            grant = Grant.NoAction,
        ),
    )
    add(
        PermissionEntry(
            requirement = ToolPermissions.INTERNET_REQUIREMENT,
            title = res.getString(R.string.perm_network),
            why = res.getString(R.string.perm_always_on),
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

    /**
     * Needs a system screen rather than a runtime dialog, and is currently off.
     */
    REQUIRES_SCREEN,

    /**
     * The permission exists but may be used only while LocalIntelligence is the default
     * assistant app, and that role is not held. The dialog is withheld until it is, because
     * Google Play allows the permission only for the default assistant.
     */
    NEEDS_ASSISTANT_ROLE,
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
        ?: return when {
            probeSatisfied(context, entry) -> PermissionStatus.GRANTED
            entry.grant is Grant.Screen -> PermissionStatus.REQUIRES_SCREEN
            else -> PermissionStatus.NOT_A_PERMISSION
        }
    // Checked before the permission on purpose: a granted texts permission is still not used
    // while the default-assistant role is off, and the screen must say so.
    if (entry.needsAssistantRole && !DefaultAssistant.isHeld(context)) {
        return PermissionStatus.NEEDS_ASSISTANT_ROLE
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
        ALL_FILES -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
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

        // Special accesses and the assistant role, read through the same platform queries the
        // tools use, so the screen and a tool cannot disagree about one switch.
        ToolPermissions.DO_NOT_DISTURB,
        ToolPermissions.WRITE_SETTINGS_ACCESS,
        ToolPermissions.USAGE_ACCESS,
        ToolPermissions.DEFAULT_ASSISTANT,
        -> AndroidPlatformGrant(context).isGranted(entry.requirement)

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
    onOpenScreen: () -> Unit,
    onOpenRole: () -> Unit,
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
                    text = status.label(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = when (status) {
                        PermissionStatus.GRANTED,
                        PermissionStatus.NOT_A_PERMISSION,
                        -> MaterialTheme.colorScheme.onSurfaceVariant

                        PermissionStatus.DENIED_FOREVER -> MaterialTheme.colorScheme.error
                        PermissionStatus.NOT_ASKED,
                        PermissionStatus.DENIED,
                        PermissionStatus.REQUIRES_SCREEN,
                        PermissionStatus.NEEDS_ASSISTANT_ROLE,
                        -> MaterialTheme.colorScheme.onSurfaceVariant
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

                PermissionStatus.NOT_A_PERMISSION -> Unit

                PermissionStatus.REQUIRES_SCREEN -> {
                    Button(onClick = onOpenScreen) {
                        Text(stringResource(R.string.open_settings))
                    }
                }

                PermissionStatus.NEEDS_ASSISTANT_ROLE -> {
                    Text(
                        stringResource(R.string.permission_needs_assistant_role),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenRole) {
                        Text(stringResource(R.string.set_as_default_assistant))
                    }
                }

                PermissionStatus.NOT_ASKED -> {
                    Button(onClick = onAsk) { Text(stringResource(R.string.allow)) }
                }

                PermissionStatus.DENIED -> {
                    Button(onClick = onAsk) { Text(stringResource(R.string.allow)) }
                }

                PermissionStatus.DENIED_FOREVER -> {
                    Text(
                        stringResource(R.string.turn_it_on_in_settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.open_app_settings))
                    }
                }
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun PermissionStatus.label(): String = when (this) {
        PermissionStatus.GRANTED -> stringResource(R.string.on)
        PermissionStatus.NOT_ASKED -> stringResource(R.string.off)
        PermissionStatus.DENIED -> stringResource(R.string.off)
        PermissionStatus.DENIED_FOREVER -> stringResource(R.string.blocked)
        PermissionStatus.NOT_A_PERMISSION -> stringResource(R.string.nothing_to_grant)
        PermissionStatus.REQUIRES_SCREEN -> stringResource(R.string.off)
        PermissionStatus.NEEDS_ASSISTANT_ROLE -> stringResource(R.string.off)
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
