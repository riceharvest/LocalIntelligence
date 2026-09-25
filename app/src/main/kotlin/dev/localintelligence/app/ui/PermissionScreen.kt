package dev.localintelligence.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * One Android permission, described in the user's terms.
 *
 * Built lazily, from a tool that is actually about to run — never at launch.
 */
data class PermissionRequest(
    val permission: String,
    val title: String,
    /** Why this app wants it, tied to the specific action. */
    val rationale: String,
)

/**
 * The lazy permission prompt.
 *
 * **The rule: no permission is requested until a tool that needs it is actually
 * invoked.** Nothing here runs on first launch. A contact-permission dialog over
 * an empty chat is the single most effective way to make someone deny a
 * permission they were about to have granted thirty seconds later — and a denied
 * contact permission makes `contacts.search` return `ToolError.PermissionDenied`
 * for the rest of the install.
 *
 * So the flow is:
 *
 *  1. the model asks for a tool that declares `requiredPermission`
 *     (`docs/tool-contract.md` — documentation and UI only, the tool never
 *     grants itself anything);
 *  2. the runtime's tool returns a permission-denied observation, or the user
 *     opens this screen from the chat;
 *  3. the prompt appears, names the specific action, and the user decides.
 *
 * `requiredPermission` is a declaration, not a gate: enforcement is the tool's
 * job, and this screen's job is to be honest about why it is being asked.
 */
@Composable
fun PermissionScreen(
    request: PermissionRequest,
    onResult: (granted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val alreadyGranted = remember(request.permission) {
        isGranted(context, request.permission)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = onResult,
    )

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(request.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = request.rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { launcher.launch(request.permission) },
                enabled = !alreadyGranted,
            ) {
                Text(if (alreadyGranted) "Already allowed" else "Allow")
            }
        }
    }
}

/** True when the permission is granted, or is not a dangerous runtime one. */
fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * The permission descriptions, keyed by the string tools put in
 * `ToolDefinition.requiredPermission`.
 *
 * A map rather than a `when` over an enum because the tool contract types this
 * as a `String?` — an Android permission name — and `:app` is the only layer
 * that knows what those names mean to a human. An unknown permission falls back
 * to a generic copy rather than crashing: a tool from a workstream this build
 * has never heard of must not take the app down on a permission dialog.
 */
fun describePermission(permission: String): PermissionRequest =
    when (permission) {
        Manifest.permission.READ_CONTACTS -> PermissionRequest(
            permission = permission,
            title = "Access your contacts",
            rationale = "A tool wants to look up a name or phone number in your " +
                "contacts. Without this it can only tell you it cannot.",
        )

        Manifest.permission.READ_CALENDAR -> PermissionRequest(
            permission = permission,
            title = "Read your calendar",
            rationale = "A tool wants to check your schedule. The app only reads " +
                "events; it never uploads them anywhere.",
        )

        Manifest.permission.WRITE_CALENDAR -> PermissionRequest(
            permission = permission,
            title = "Add events to your calendar",
            rationale = "A tool wants to create an event. You will be asked to " +
                "approve the tool itself before anything is written.",
        )

        Manifest.permission.POST_NOTIFICATIONS -> PermissionRequest(
            permission = permission,
            title = "Show notifications",
            rationale = "Needed so a long-running task can tell you it finished, " +
                "and so alarms and reminders this app sets can appear.",
        )

        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        -> PermissionRequest(
            permission = permission,
            title = "Access your photos and media",
            rationale = "A tool wants to search or attach something from your " +
                "media library.",
        )

        Manifest.permission.SCHEDULE_EXACT_ALARM -> PermissionRequest(
            permission = permission,
            title = "Set exact alarms",
            rationale = "Lets the app schedule an alarm at an exact time. Android " +
                "shows its own confirmation for this one.",
        )

        else -> PermissionRequest(
            permission = permission,
            title = "Additional permission",
            rationale = "A tool needs the '$permission' permission to run. You can " +
                "revoke it from system settings at any time.",
        )
    }
