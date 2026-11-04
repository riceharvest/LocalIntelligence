package dev.localintelligence.android.phoneindex

import android.Manifest
import android.app.Notification
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import dev.localintelligence.android.tools.messages.DefaultAssistant
import dev.localintelligence.core.phoneindex.PhoneIndexRecord
import dev.localintelligence.core.phoneindex.PhoneIndexSearch
import dev.localintelligence.core.phoneindex.PhoneIndexSource
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

// =====================================================================================
// One reader per source. Each returns the items it can see now, or null when it cannot read
// at all (permission refused, role not held, folder gone). A null never prunes the index: a
// denied permission must not look like "the phone has no texts any more".
//
// Every reader is capped by PhoneIndexSearch.PER_SOURCE_LIMIT and reads only the columns it
// needs. Nothing here opens a network connection.
// =====================================================================================

/** Calendar window: 30 days back, a year ahead. */
private val CALENDAR_BACK_MILLIS = TimeUnit.DAYS.toMillis(30)
private val CALENDAR_AHEAD_MILLIS = TimeUnit.DAYS.toMillis(365)

/** A file longer than this is searched by its first part only. */
private const val FILE_MAX_BYTES = 256 * 1024
private const val FILE_MAX_FILES = 300
private const val FILE_MAX_DEPTH = 3
private val TEXT_EXTENSIONS = listOf(".txt", ".md", ".csv", ".json", ".log", ".xml", ".html", ".htm", ".yaml", ".yml")

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Texts, newest first. Refused unless LocalIntelligence is the default assistant (checked on every
 * read, as the texts tools do) and READ_SMS is granted.
 */
internal fun collectTexts(context: Context): List<PhoneIndexRecord>? {
    if (!granted(context, Manifest.permission.READ_SMS) || !DefaultAssistant.isHeld(context)) return null
    return try {
        val out = ArrayList<PhoneIndexRecord>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddress = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (out.size < PhoneIndexSearch.PER_SOURCE_LIMIT && c.moveToNext()) {
                val body = PhoneIndexSearch.clean(c.getString(iBody).orEmpty())
                if (body.isEmpty()) continue
                val sender = c.getString(iAddress).orEmpty().ifBlank { "unknown number" }
                out += PhoneIndexRecord(
                    source = PhoneIndexSource.MESSAGES,
                    key = c.getLong(iId).toString(),
                    title = "Text with $sender",
                    text = body,
                    whenMillis = c.getLong(iDate),
                )
            }
        }
        out
    } catch (e: SecurityException) {
        null
    }
}

/** Calendar instances in the window: title, location and description. */
internal fun collectEvents(context: Context, now: Long): List<PhoneIndexRecord>? {
    if (!granted(context, Manifest.permission.READ_CALENDAR)) return null
    return try {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - CALENDAR_BACK_MILLIS)
            ContentUris.appendId(it, now + CALENDAR_AHEAD_MILLIS)
        }.build()
        val out = ArrayList<PhoneIndexRecord>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
            ),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (out.size < PhoneIndexSearch.PER_SOURCE_LIMIT && c.moveToNext()) {
                val title = c.getString(2).orEmpty().ifBlank { "Event" }
                val text = PhoneIndexSearch.clean("$title ${c.getString(3).orEmpty()} ${c.getString(4).orEmpty()}")
                out += PhoneIndexRecord(
                    source = PhoneIndexSource.CALENDAR,
                    key = "${c.getLong(0)}@${c.getLong(1)}",
                    title = PhoneIndexSearch.clean(title),
                    text = text,
                    whenMillis = c.getLong(1),
                )
            }
        }
        out
    } catch (e: SecurityException) {
        null
    }
}

/** Contact display names only. Numbers and addresses are never read into the index. */
internal fun collectContactNames(context: Context, now: Long): List<PhoneIndexRecord>? {
    if (!granted(context, Manifest.permission.READ_CONTACTS)) return null
    return try {
        val out = ArrayList<PhoneIndexRecord>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null,
        )?.use { c ->
            while (out.size < PhoneIndexSearch.PER_SOURCE_LIMIT && c.moveToNext()) {
                val name = PhoneIndexSearch.clean(c.getString(1).orEmpty())
                if (name.isEmpty()) continue
                out += PhoneIndexRecord(
                    source = PhoneIndexSource.CONTACTS,
                    key = c.getLong(0).toString(),
                    title = "Contact",
                    text = name,
                    whenMillis = now,
                )
            }
        }
        out
    } catch (e: SecurityException) {
        null
    }
}

/**
 * Text files in the folder the person picked, down to [FILE_MAX_DEPTH] levels. Reads through the
 * persisted tree grant, so a folder the person removed access to returns null.
 */
internal fun collectFiles(context: Context, folder: Uri): List<PhoneIndexRecord>? {
    val rootId = try {
        DocumentsContract.getTreeDocumentId(folder)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val out = ArrayList<PhoneIndexRecord>()
    return try {
        walkFolder(context, folder, rootId, depth = 0, out = out)
        out
    } catch (e: SecurityException) {
        null
    }
}

private fun walkFolder(context: Context, tree: Uri, parentId: String, depth: Int, out: MutableList<PhoneIndexRecord>) {
    if (depth > FILE_MAX_DEPTH) return
    val folders = ArrayList<String>()
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    context.contentResolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        ),
        null,
        null,
        null,
    )?.use { c ->
        while (out.size < FILE_MAX_FILES && c.moveToNext()) {
            val id = c.getString(0) ?: continue
            val name = c.getString(1).orEmpty()
            val mime = c.getString(2).orEmpty()
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                folders += id
                continue
            }
            if (!isTextLike(name, mime)) continue
            val text = readText(context, DocumentsContract.buildDocumentUriUsingTree(tree, id)) ?: continue
            out += PhoneIndexRecord(
                source = PhoneIndexSource.FILES,
                key = id,
                title = PhoneIndexSearch.clean(name).ifEmpty { "File" },
                text = PhoneIndexSearch.clean(text),
                whenMillis = c.getLong(3),
            )
        }
    }
    for (folder in folders) {
        if (out.size >= FILE_MAX_FILES) return
        walkFolder(context, tree, folder, depth + 1, out)
    }
}

private fun isTextLike(name: String, mime: String): Boolean {
    val lower = name.lowercase()
    return mime.startsWith("text/") || mime == "application/json" ||
        TEXT_EXTENSIONS.any { lower.endsWith(it) }
}

/** The first [FILE_MAX_BYTES] of a file as UTF-8, or null when it is not text (a NUL byte). */
private fun readText(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(FILE_MAX_BYTES)
            var read = 0
            while (read < FILE_MAX_BYTES) {
                val n = stream.read(buffer, read, FILE_MAX_BYTES - read)
                if (n < 0) break
                read += n
            }
            if ((0 until read).any { buffer[it] == 0.toByte() }) null
            else String(buffer, 0, read, StandardCharsets.UTF_8)
        }
    } catch (e: java.io.IOException) {
        null
    }
}

/**
 * One notification as a record, or null when it is not worth keeping: the app's own, an ongoing
 * one (a player, a download), or one with no text. The title names the app that posted it.
 */
internal fun notificationRecord(context: Context, sbn: StatusBarNotification): PhoneIndexRecord? {
    if (sbn.packageName == context.packageName || sbn.isOngoing) return null
    val extras = sbn.notification.extras ?: return null
    val title = runCatching { extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }.getOrNull().orEmpty()
    val body = runCatching {
        (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
    }.getOrNull().orEmpty()
    val text = PhoneIndexSearch.clean("$title $body")
    if (text.isEmpty()) return null
    val app = runCatching {
        val info = context.packageManager.getApplicationInfo(sbn.packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(sbn.packageName)
    return PhoneIndexRecord(
        source = PhoneIndexSource.NOTIFICATIONS,
        key = "${sbn.key}@${sbn.postTime}",
        title = PhoneIndexSearch.clean("$app: $title"),
        text = text,
        whenMillis = sbn.postTime,
    )
}
