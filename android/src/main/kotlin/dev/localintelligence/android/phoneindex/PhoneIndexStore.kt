package dev.localintelligence.android.phoneindex

import android.content.Context
import android.net.Uri
import android.service.notification.StatusBarNotification
import dev.localintelligence.android.data.LocalIntelligenceDatabase
import dev.localintelligence.android.data.PhotoCaptionDao
import dev.localintelligence.android.data.lazyPhotoCaptionDao
import dev.localintelligence.core.phoneindex.PhoneHit
import dev.localintelligence.core.phoneindex.PhoneIndexRecord
import dev.localintelligence.core.phoneindex.PhoneIndexSearch
import dev.localintelligence.core.phoneindex.PhoneIndexSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Search my phone": the local index of the sources the person switched on.
 *
 * ## WHAT IS STORED, AND WHERE
 *
 * Notifications, texts, calendar, contact names and files go into the `phone_index` table of the app's
 * own database. Photo captions are already stored by Photo search, so they are read in place from
 * `photo_captions` and copied nowhere. Nothing here opens a network connection.
 *
 * ## HOW IT STAYS CURRENT
 *
 * Notifications are recorded as they arrive ([onNotificationPosted]), because the phone offers no way
 * to read the past ones. The other sources are read again by [refresh], which the charger job runs
 * ([PhoneIndexJob] in `:app`). A refresh that can read a whole source replaces what it saw and drops
 * the rows it did not see, so a deleted text or event leaves the index. A source that cannot be read
 * (permission refused, role not held) keeps its rows and is not pruned.
 *
 * ## WHY ONE INSTANCE PER PROCESS
 *
 * [get] returns the same store to the listener, the tool, the job and the screen, so the database and
 * the notification flag are not duplicated per caller.
 */
class PhoneIndexStore private constructor(private val app: Context) {

    private val dao: PhoneIndexDao by lazy { LocalIntelligenceDatabase.build(app).phoneIndexDao() }
    private val photos: () -> PhotoCaptionDao = lazyPhotoCaptionDao(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whether a notification that arrives is kept. Set from the settings by the application, and
     * read by the listener, which the system can bind before any screen exists. Off by default.
     */
    @Volatile
    private var notificationsOn: Boolean = false

    /** Turns notification recording on or off. Called whenever the setting changes. */
    fun setNotificationsOn(on: Boolean) {
        notificationsOn = on
    }

    /** Called by the notification listener for each posted notification. Never blocks the caller. */
    fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!notificationsOn) return
        val record = notificationRecord(app, sbn) ?: return
        scope.launch {
            runCatching {
                dao.upsert(listOf(record.toEntity(System.currentTimeMillis())))
                dao.pruneOverflow(PhoneIndexSource.NOTIFICATIONS.id, PhoneIndexSearch.PER_SOURCE_LIMIT)
            }
        }
    }

    /**
     * Reads the switched-on sources that are read in full (texts, calendar, contacts, files) and
     * stores what they show. [messagesAllowed] is false in the Play build's background runs, where
     * texts are for attended use only. Returns the number of items stored per source that was read;
     * a source that could not be read is absent.
     */
    suspend fun refresh(
        enabled: Set<PhoneIndexSource>,
        folder: Uri?,
        messagesAllowed: Boolean,
    ): Map<PhoneIndexSource, Int> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val report = LinkedHashMap<PhoneIndexSource, Int>()
        fun store(source: PhoneIndexSource, records: List<PhoneIndexRecord>?) {
            if (records == null) return
            replaceSource(source, records, now)
            report[source] = records.size
        }
        if (PhoneIndexSource.MESSAGES in enabled && messagesAllowed) {
            store(PhoneIndexSource.MESSAGES, collectTexts(app))
        }
        if (PhoneIndexSource.CALENDAR in enabled) store(PhoneIndexSource.CALENDAR, collectEvents(app, now))
        if (PhoneIndexSource.CONTACTS in enabled) store(PhoneIndexSource.CONTACTS, collectContactNames(app, now))
        if (PhoneIndexSource.FILES in enabled && folder != null) {
            store(PhoneIndexSource.FILES, collectFiles(app, folder))
        }
        report
    }

    private fun replaceSource(source: PhoneIndexSource, records: List<PhoneIndexRecord>, runStart: Long) {
        runCatching {
            if (records.isNotEmpty()) dao.upsert(records.map { it.toEntity(runStart) })
            dao.pruneUnseen(source.id, runStart)
            dao.pruneOverflow(source.id, PhoneIndexSearch.PER_SOURCE_LIMIT)
        }
    }

    /** Items stored per source, for the screen. Photo captions count the rows in `photo_captions`. */
    suspend fun counts(): Map<PhoneIndexSource, Int> = withContext(Dispatchers.IO) {
        val stored = runCatching { dao.counts().associate { it.source to it.n } }.getOrDefault(emptyMap())
        PhoneIndexSource.entries.associateWith { source ->
            if (source == PhoneIndexSource.PHOTOS) runCatching { photos().count() }.getOrDefault(0)
            else stored[source.id] ?: 0
        }
    }

    /** Deletes everything stored for [source]. Photo captions are cleared from Photo search's own table. */
    suspend fun delete(source: PhoneIndexSource) = withContext(Dispatchers.IO) {
        if (source == PhoneIndexSource.PHOTOS) photos().clear()
        else dao.deleteSource(source.id)
    }

    /** The searchable records of the [enabled] sources, newest first per source. */
    fun records(enabled: Set<PhoneIndexSource>): List<PhoneIndexRecord> {
        val out = ArrayList<PhoneIndexRecord>()
        val stored = enabled.filter { it != PhoneIndexSource.PHOTOS }.map { it.id }
        if (stored.isNotEmpty()) {
            dao.forSources(stored).mapNotNullTo(out) { row ->
                PhoneIndexSource.byId(row.source)?.let { source ->
                    PhoneIndexRecord(source, row.sourceKey, row.title, row.text, row.whenAt)
                }
            }
        }
        if (PhoneIndexSource.PHOTOS in enabled) {
            photos().all().mapTo(out) { row ->
                PhoneIndexRecord(PhoneIndexSource.PHOTOS, row.uri, "Photo", row.caption, row.indexedAt)
            }
        }
        return out
    }

    /** The best [limit] items among the [enabled] sources for [query]. */
    fun search(enabled: Set<PhoneIndexSource>, query: String, limit: Int): List<PhoneHit> =
        PhoneIndexSearch.search(records(enabled), query, limit)

    private fun PhoneIndexRecord.toEntity(indexedAt: Long) = PhoneIndexEntity(
        source = source.id,
        sourceKey = key,
        title = title,
        text = text,
        whenAt = whenMillis,
        indexedAt = indexedAt,
    )

    companion object {
        @Volatile
        private var instance: PhoneIndexStore? = null

        /** The process's store. Cheap to call: the database opens on first use, not here. */
        fun get(context: Context): PhoneIndexStore =
            instance ?: synchronized(this) {
                instance ?: PhoneIndexStore(context.applicationContext).also { instance = it }
            }
    }
}
