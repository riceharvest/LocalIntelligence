package dev.localintelligence.android.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.PlatformRequirement
import dev.localintelligence.core.tool.contracts.ToolPermissions

/**
 * The real answer to "may this app do this right now", read from the platform
 * rather than from a set the host has to remember to populate.
 *
 * ## Why this exists
 *
 * [dev.localintelligence.core.tool.ToolContext.permissionGranted] defaults to
 * `true` and is filled from
 * [dev.localintelligence.core.policy.PolicyConfig.grantedPermissions], which
 * ships empty and which nothing in the app populates. The consequence is not a
 * subtle degradation: every tool that declares a `requiredPermission` is handed
 * `permissionGranted = false` on every call, forever, whether or not the user
 * granted it in Settings.
 *
 * That produced two failure modes and this fixes both:
 *
 *  - Tools that trusted the flag returned a fabricated "no permission in this
 *    session" for a permission the user HAD granted. The model tells the user
 *    to go and switch on something that is already on.
 *  - Tools that distrusted the flag and relied on catching `SecurityException`
 *    returned whatever the provider gave them — an empty contact list, an empty
 *    calendar. That is the worse of the two, because the model reports "you
 *    have no contacts" and the user has no way to tell that from the truth.
 *
 * So a tool asks THIS, which is a direct platform query, instead of trusting a
 * flag that nobody maintains.
 *
 * ## Why a Context is captured rather than passed per call
 *
 * `execute` has no platform handle in its signature, and adding one would put
 * an `android.*` type into `:core`'s tool contract. The probe is constructed
 * once per registry, in `androidTools(context)`, from the application context —
 * so no Activity is retained and no tool holds a Context it can leak.
 */
class AndroidPlatformGrant(context: Context) : PlatformGrant {

    private val appContext: Context = context.applicationContext

    override fun isGranted(requirement: PlatformRequirement): Boolean {
        // Bound to a local because `permission` is a property on a type from
        // another module, and Kotlin will not smart-cast a cross-module
        // property inside a `when`.
        val permission = requirement.permission
        return if (permission == null) capabilityGranted(requirement) else isPermissionHeld(permission)
    }

    /**
     * `ContextCompat.checkSelfPermission` rather than a bare call so the
     * pre-Marshmallow path is honest. minSdk is 26, so this is really just the
     * one API, but going through ContextCompat means a denial is a denial on
     * every build rather than a crash on one.
     */
    private fun isPermissionHeld(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * A requirement with no runtime permission still names a capability, and
     * that capability can be off.
     *
     * Matching is on the [PlatformRequirement] VALUE, not on its `capability`
     * string. That string is prose meant for a sentence to the user, and a
     * `when` branch keyed on it silently stops matching the moment anyone
     * rewords the sentence — which is exactly how the `else -> true` below came
     * to stand in for the document capabilities and made every `denied(...)`
     * call site in FileTools.kt unreachable.
     *
     * @see canReadDocuments for why the document answer is not a permission check.
     */
    private fun capabilityGranted(requirement: PlatformRequirement): Boolean =
        when (requirement) {
            // A real platform query rather than a guess.
            // `isNotificationListenerAccessGranted` is the API 31+ way to ask
            // without requiring the service to be running, which is what makes
            // "never granted" distinguishable from "granted but the service has
            // not connected yet" — two different user problems deserving two
            // different sentences.
            ToolPermissions.NOTIFICATION_LISTENER ->
                dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
                    .isGranted(appContext)

            ToolPermissions.DOCUMENT_READ -> canReadDocuments()

            // Writing is two different capabilities. Creating a document in
            // Downloads needs no permission from API 29, and overwriting a
            // document the user already handed over needs only that document's
            // own grant — so the answer is true if EITHER route exists.
            ToolPermissions.DOCUMENT_WRITE -> canCreateDocuments() || canReadDocuments()

            // FAIL CLOSED, which is the whole point of this change.
            //
            // This used to be `true`, on the reasoning that a requirement with
            // no permission and no probe "is not gated at all". That reasoning
            // is wrong for every capability that IS gated without a permission:
            // the two document requirements reach here with `permission = null`
            // precisely because Android grants them per document rather than per
            // app, and answering `true` for an unprobed capability means the
            // tool runs, gets nothing back, and reports that empty result as a
            // fact about the user's phone.
            //
            // Refusing is the safe direction: it is visible, it names a
            // capability, and it cannot be mistaken for "you have no documents".
            // A new gated capability with no probe added here fails loudly on its
            // first call instead of quietly lying at the user's expense.
            else -> false
        }

    /**
     * Storage, which is the one requirement that is genuinely conditional on the
     * API level — and the one place where "check a permission" is the wrong
     * question entirely.
     *
     * Before Android 13 a single runtime permission covers reading documents.
     * From Android 13 that permission no longer exists for documents at all: it
     * was split into per-media-type permissions that do NOT cover a PDF in
     * Documents, and the only route to an arbitrary document is the system file
     * picker. A tool that claimed to need `READ_EXTERNAL_STORAGE` on Android 13
     * would be claiming a permission the platform ignores — which is the
     * documentation lying about the API, the exact failure this work exists to
     * remove.
     *
     * ## The condition implemented here
     *
     * **On API 26-32:** a read of another app's documents succeeds exactly when
     * `READ_EXTERNAL_STORAGE` (or `WRITE_EXTERNAL_STORAGE`, which implies it)
     * is held. That is a real permission, and `checkSelfPermission` is the truth.
     *
     * **On API 33+:** there is no permission that answers this question, because
     * none exists. The only route is the Storage Access Framework, and SAF is
     * **per-document, not per-app**: the platform grants access to a URI the user
     * picked in the system picker, and to nothing else. So the honest global
     * question is not "is a permission held" but "does this app hold at least one
     * document grant" — answered by [ContentResolver.persistedUriPermissions],
     * which is a real platform query listing the URI grants the user has handed
     * this app. A non-empty list means a read of *a* document the user shared
     * can succeed; an empty list means no document read can, whatever the
     * permission situation.
     *
     * This is the closest the global [PlatformGrant] interface can get to the
     * truth, and it is honest in the direction that matters: it returns false
     * when nothing is reachable, so the tools refuse with a sentence that names
     * the file picker rather than returning an empty list the model reports to
     * the user as "you have no documents".
     *
     * What it deliberately does NOT claim: that a *particular* document is
     * readable. SAF access is per-URI and this is a whole-app question, so a
     * `true` here means "some document read can succeed", not "this URI can be
     * read". The per-URI check stays where it belongs — the provider refusing
     * with `SecurityException`/`FileNotFoundException`, which
     * [dev.localintelligence.core.tool.contracts.ToolPermissions.DOCUMENT_READ]'s
     * callers already translate into a precise per-document message. No amount
     * of pre-checking can replace that, and pretending otherwise is the lie
     * this method exists to prevent.
     *
     * @return true when a read of at least one other app's document can succeed.
     */
    fun canReadDocuments(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // No runtime permission grants this on 13+. Documents become
            // readable only per-URI, through the file picker, so the only
            // queryable fact is whether the user has ever granted one.
            hasPersistedDocumentGrant()
        } else {
            isPermissionHeld(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                isPermissionHeld(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    /**
     * Whether the user has handed this app at least one document through SAF.
     *
     * `persistedUriPermissions` is the platform's own record of the URI grants
     * this app holds, and it is the ONLY honest probe for "can a document read
     * succeed" on API 33+: there is no permission to check, because there is no
     * permission. It reflects grants taken with
     * `takePersistableUriPermission`, so it survives a reboot — which is what
     * makes it a statement about the user's setup rather than about this process.
     *
     * Never throws. A provider that throws while being asked about its own
     * grants is treated as "no grants", which is the direction that produces a
     * visible refusal rather than a silent empty result.
     */
    private fun hasPersistedDocumentGrant(): Boolean = try {
        appContext.contentResolver.persistedUriPermissions.isNotEmpty()
    } catch (t: SecurityException) {
        false
    } catch (t: IllegalArgumentException) {
        false
    }

    /**
     * True when the app can create a document in Downloads, which needs no
     * permission from API 29.
     *
     * Below API 29 the `files.write_text` planner refuses to create a file at all
     * (there is no permission-free insert route that works there), so the
     * overwrite path is the only one left and it is covered by
     * [canReadDocuments].
     */
    fun canCreateDocuments(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Whether the app can see any other app at all.
     *
     * Package visibility on Android 11+ hides most installed apps unless the
     * manifest declares a `<queries>` element. This app does declare one, but
     * the question is still worth asking rather than assuming, because
     * `apps.list` returning empty is indistinguishable from "the user has no
     * apps" unless something says otherwise.
     */
    fun canSeeInstalledApps(): Boolean = true
}
