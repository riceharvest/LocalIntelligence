package dev.localintelligence.android.tools.phonesearch

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Telephony
import dev.localintelligence.core.phoneindex.PhoneHit
import dev.localintelligence.core.phoneindex.PhoneIndexSource
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingsReader

// =====================================================================================
// The tappable rows under a phone.search answer. Each hit can open its original: the calendar event,
// the contact, the file, the photo, the text's conversation, or the app that posted a notification.
//
// The intents are built here, from the key each collector stored (see PhoneIndexCollectors), and
// never reach the model: the observation names the source and the title only.
// =====================================================================================

/**
 * One tappable row: the source (its name is a string resource, built by the chat), the item's own
 * text for that source, and the intent that opens the item.
 */
data class PhoneSearchLink(val source: PhoneIndexSource, val detail: String, val intent: Intent)

/**
 * The rows the last successful `phone.search` in this process found, so the chat can show them under
 * the answer. WHY A HOLDER AND NOT THE OBSERVATION: an intent or a content URI in the model's prompt is
 * a string it could echo or invent. The tool clears this at the top of every call, so a failed or empty
 * search leaves nothing for the next answer to show.
 */
object PhoneSearchLinks {
    /** The latest search's rows, best hit first. Written by the tool, read by the chat. */
    @Volatile
    var latest: List<PhoneSearchLink> = emptyList()
        internal set
}

/**
 * The row that opens [hit], or null when the phone offers no way to open that kind of item (the
 * permission is gone, the record was removed, or no app takes it). Every lookup here may throw on a
 * phone that has changed under the index, so each failure becomes "no row", never a failed search.
 */
internal fun phoneSearchLink(app: Context, settings: SettingsReader, hit: PhoneHit): PhoneSearchLink? {
    val intent = try {
        intentFor(app, settings, hit)
    } catch (e: RuntimeException) {
        // SecurityException included: a revoked permission is the usual cause.
        null
    } ?: return null
    // A contact's and a photo's title is only a kind ("Contact", "Photo"); their text says what it is.
    val detail = when (hit.source) {
        PhoneIndexSource.CONTACTS, PhoneIndexSource.PHOTOS -> hit.passage
        else -> hit.title
    }
    return PhoneSearchLink(hit.source, detail, intent)
}

private fun intentFor(app: Context, settings: SettingsReader, hit: PhoneHit): Intent? = when (hit.source) {
    // The key is "<event id>@<begin millis>" (see collectEvents). The begin time opens that instance.
    PhoneIndexSource.CALENDAR -> {
        val eventId = hit.key.substringBefore('@').toLongOrNull() ?: return null
        val begin = hit.key.substringAfter('@', "").toLongOrNull()
        Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
            .apply { if (begin != null) putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin) }
    }

    // The key is the contact's _ID (see collectContactNames).
    PhoneIndexSource.CONTACTS -> {
        val contactId = hit.key.toLongOrNull() ?: return null
        Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId))
    }

    // The key is the document id inside the folder the person chose (see collectFiles). The folder
    // is read from settings, so a folder changed since the index was built opens nothing.
    PhoneIndexSource.FILES -> {
        val folder = settings[CoreSettings.PHONE_INDEX_FILES_FOLDER].takeIf { it.isNotBlank() } ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(folder), hit.key)
        Intent(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // The key is the MediaStore URI the photo was indexed from (see the photo index).
    PhoneIndexSource.PHOTOS -> Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(hit.key), "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    // The key is the SMS _ID. The conversation opens at the sender's number: the index does not keep
    // the thread id, so this opens a message to that number in the default messaging app.
    PhoneIndexSource.MESSAGES -> {
        val smsId = hit.key.toLongOrNull() ?: return null
        val address = app.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms._ID} = ?",
            arrayOf(smsId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (address.isNullOrBlank()) return null
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", address, null))
    }

    // The key is "<sbn key>@<post time>", and the sbn key is "<user>|<package>|<id>|<tag>|<uid>". The
    // notification itself cannot be reopened, so the row opens the app that posted it.
    PhoneIndexSource.NOTIFICATIONS -> {
        val pkg = hit.key.substringBefore('@').split('|').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        app.packageManager.getLaunchIntentForPackage(pkg)
    }
}
