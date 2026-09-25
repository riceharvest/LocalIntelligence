package dev.localintelligence.app.document

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * The `ACTION_CREATE_DOCUMENT` bridge: an Activity whose only job is to be
 * launched, disappear, and hand one validated `content://` URI back.
 *
 * ## Why an Activity is unavoidable here
 *
 * `files.write_text` needs to save to a location the user picks, on any API
 * level, through any provider. There is no callback-shaped API for that:
 * `MediaStore.insert` writes only to shared media collections, and the only way to
 * reach an arbitrary user-chosen provider is `ACTION_CREATE_DOCUMENT`, which
 * delivers its result to an Activity via `onActivityResult`. A `Service` cannot
 * receive it. So the bridge is an Activity, and registering an Activity requires
 * a `<activity>` element in the manifest — which is why this file and the
 * manifest entry ship together. See `file_paths.xml` for the other half.
 *
 * ## Why it is invisible
 *
 * The theme is translucent, so the user sees the system file picker and nothing
 * else. There is no blank app window and no flash of our own UI. `noHistory` is
 * deliberately NOT set: it would finish this Activity the instant the picker
 * covers it, and the result would be delivered to a dead Activity, so
 * `files.write_text` would hang instead of returning. Every terminal path calls
 * `finish()` explicitly, which keeps the back stack clean without that.
 *
 * ## The three failure modes this exists to handle correctly
 *
 *  1. **User backs out.** `RESULT_CANCELED`, or a null `data`, is the single most
 *     common outcome of this Activity. It returns [RESULT_CANCELED] immediately
 *     and finishes; it never retries, never shows a dialog, and never treats a
 *     cancel as an error worth surfacing to a model as a failure.
 *  2. **A hostile or broken picker.** A third-party document provider controls
 *     the URI it returns. It is validated by [ProviderPathScope.validateDocumentUri]
 *     before it goes anywhere, and a rejected URI is treated exactly like a
 *     cancel — indistinguishable to the caller, because a caller cannot act on
 *     "a picker returned something we refuse".
 *  3. **Launched with no usable extras.** A `null` or blank MIME type means the
 *     picker has nothing to filter on. Rather than open a picker showing every
 *     file on the device, it cancels.
 *
 * ## Why nothing here is logged
 *
 * The returned URI names a user's private file, and a content URI embeds an
 * authority, a document id and often a display name. Writing one to logcat puts
 * it in a world-readable buffer on pre-API-31 devices and in any bug report the
 * user ever attaches. Failures are reported as a [Rejection] enum constant, which
 * says what went wrong and reveals nothing about the value.
 */
class CreateDocumentActivity : Activity() {

    /**
     * Declared `exported="false"`. No other app may launch this directly.
     *
     * The Activity is reached only from inside this process, and it takes no
     * caller-supplied data that would let a stranger do anything a legitimate
     * caller could not. `exported="true"` here would hand any installed app the
     * ability to raise our picker and harvest the resulting URI, so it is false
     * and there is no intent filter to make it otherwise.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A configuration change re-delivers the same request; the system keeps
        // the result and will call onActivityResult again. Re-launching the picker
        // on every rotation would stack pickers, so only launch on a fresh start.
        if (savedInstanceState != null) return

        val mimeType = intent?.getStringExtra(EXTRA_MIME_TYPE)
        if (mimeType.isNullOrBlank()) {
            cancel()
            return
        }

        val request = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, intent?.getStringExtra(EXTRA_TITLE).orEmpty())
            // Grant the write we need to the URI we hand back. Without this the
            // picker can return a URI that we then cannot write into, and the
            // failure surfaces much later as an opaque openOutputStream error.
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // No picker on this device (a stripped-down ROM, a managed profile). A
        // missing handler is a cancel, not a crash.
        if (request.resolveActivity(packageManager) == null) {
            cancel()
            return
        }

        try {
            startActivityForResult(request, REQUEST_CREATE_DOCUMENT)
        } catch (_: android.content.ActivityNotFoundException) {
            // resolveActivity lied, or the picker was disabled between the two
            // calls. Same outcome, handled without taking down the agent step.
            cancel()
        }
    }

    /**
     * Validates whatever the picker handed back before it is allowed to escape.
     *
     * The ordering here is the security property: validate, and only then
     * [setResult]. A URI that fails validation never reaches the caller, so there
     * is no window in which a downstream tool could open it.
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CREATE_DOCUMENT) return

        if (resultCode != RESULT_OK) {
            cancel()
            return
        }

        val uri: Uri? = data?.data
        if (uri == null) {
            cancel()
            return
        }

        val rejection = ProviderPathScope.validateDocumentUri(uri.toString(), packageName)
        if (rejection != null) {
            // A refused URI is reported as a cancel, never as its own failure mode.
            // The caller learns "you did not get a document" and nothing about the
            // rejected value, which keeps a hostile picker's chosen path out of the
            // agent's context entirely.
            cancel()
            return
        }

        setResult(RESULT_OK, Intent().setData(uri))
        finish()
    }

    /**
     * The clean cancel path.
     *
     * `finish()` before `setResult` is avoided only in the sense that
     * [setResult] is called first, so that an already-pending result is cleared
     * rather than left as a stale OK. Finishing is unconditional and idempotent:
     * a double `finish()` is a no-op, so this is safe from every branch.
     */
    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        /**
         * Request code for the picker. Arbitrary but fixed, so [onActivityResult]
         * can tell our result apart from a result delivered to us by something
         * else.
         */
        const val REQUEST_CREATE_DOCUMENT = 0x0D0C

        /** MIME type to filter the picker by. Required; a blank value cancels. */
        const val EXTRA_MIME_TYPE = "dev.localintelligence.app.document.MIME_TYPE"

        /** Suggested file name. Optional; the user overrides it. */
        const val EXTRA_TITLE = "dev.localintelligence.app.document.TITLE"

        /**
         * Builds the intent that launches this Activity.
         *
         * A factory rather than an inline intent at the call site, so the extra
         * keys are defined once and the two halves cannot drift.
         *
         * The component is set explicitly. An implicit intent with no
         * `CATEGORY_DEFAULT` would not resolve at all, and an explicit component
         * is the only form that survives this Activity remaining
         * `exported="false"` — which it must.
         */
        fun intent(mimeType: String, suggestedName: String? = null): Intent =
            Intent(ACTION_CREATE_DOCUMENT_INTERNAL).apply {
                component = ComponentName(
                    "dev.localintelligence.app",
                    CreateDocumentActivity::class.java.name,
                )
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_TITLE, suggestedName.orEmpty())
            }

        /**
         * Placeholder action, never matched against.
         *
         * WHY a private constant rather than reusing `ACTION_CREATE_DOCUMENT`:
         * that string names the intent the *system picker* answers to. Reusing it
         * on an intent that points back at our own private Activity would mean
         * `resolveActivity` on the wrong side of the boundary could match
         * something unintended. Keeping our own action distinct means the only
         * thing that can ever launch this Activity is this exact component.
         */
        const val ACTION_CREATE_DOCUMENT_INTERNAL = "dev.localintelligence.app.document.CREATE"
    }
}
