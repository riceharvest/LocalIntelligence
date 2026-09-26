package dev.localintelligence.android.tools.contacts

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.catalogue.ToolMeta
import dev.localintelligence.core.tool.catalogue.ToolArgumentBounds
import dev.localintelligence.core.tool.catalogue.ToolSchemas
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure, android-free contact logic: argument coercion, dedup, capping and rendering.
 *
 * The shape of this problem is entirely about NOT lying to the model with a short
 * observation: a search must collapse the 3 rows a contact occupies in the phone table
 * into one line, and a detail lookup must fit a 12-phone contact inside the observation
 * budget. All of that is decided here, off-device, where it can be tested.
 */

/** One phone-table row. A contact with 3 phone numbers appears here 3 times. */
internal data class ContactPhoneRow(
    val contactId: Long,
    val displayName: String,
    val number: String,
)

/** One contact after dedup: the unit the model actually wants. */
internal data class ContactSummary(
    val contactId: Long,
    val displayName: String,
    val numbers: List<String>,
)

/** Full detail for one contact id. */
internal data class ContactDetail(
    val contactId: Long,
    val displayName: String,
    val organization: String?,
    val phones: List<String>,
    val emails: List<String>,
)

internal sealed interface ParsedContactId {
    data class Ok(val id: Long) : ParsedContactId
    data class Invalid(val message: String) : ParsedContactId
}

// ---------------------------------------------------------------------------- args --

internal object ContactsArgs {

    // Aliased from :core's ToolArgumentBounds: these numbers appear in this
    // tool's JSON Schema, which :core owns, and in its `execute()`, below.
    // Aliasing rather than repeating is what stops the advertised bound and
    // the enforced bound from drifting apart.
    const val DEFAULT_LIMIT = ToolArgumentBounds.CONTACTS_DEFAULT_LIMIT
    const val MAX_LIMIT = ToolArgumentBounds.CONTACTS_MAX_LIMIT

    const val MAX_QUERY_CHARS = 64

    /** How many phone numbers a single search line may show before it says "+n more". */
    const val PHONES_PER_SEARCH_LINE = 2

    /** Detail caps. A contact with 12 phones must still fit the observation budget. */
    const val MAX_PHONES = 6
    const val MAX_EMAILS = 4
    const val MAX_NAME_CHARS = 64
    const val MAX_ORGANIZATION_CHARS = 64
    const val MAX_VALUE_CHARS = 48
    const val MAX_EMAIL_CHARS = 80

    /**
     * Models send "8", 8.7, -4 and "banana" for an integer. All of them are survivable.
     */
    fun coerceLimit(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return DEFAULT_LIMIT
        val value = text.toIntOrNull()
            ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
            ?: return DEFAULT_LIMIT
        return when {
            value < 1 -> DEFAULT_LIMIT
            value > MAX_LIMIT -> MAX_LIMIT
            else -> value
        }
    }

    /** Contact ids are opaque; anything non-numeric or non-positive is a model error. */
    fun parseContactId(raw: String?): ParsedContactId {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) {
            return ParsedContactId.Invalid("'id' is required; pass a contact id from contacts.search")
        }
        val id = text.toLongOrNull()
            ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
            ?: return ParsedContactId.Invalid("'id' must be a numeric contact id, got \"$text\"")
        return if (id > 0) {
            ParsedContactId.Ok(id)
        } else {
            ParsedContactId.Invalid("'id' must be a positive contact id, got $id")
        }
    }

    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun clean(raw: String?, max: Int): String? {
        val text = raw?.trim().orEmpty().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        return if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
    }

    private fun isPhoneLike(value: String): Boolean =
        value.isNotEmpty() && value.any { it.isDigit() }

    private fun isEmailLike(value: String): Boolean =
        value.isNotEmpty() && value.contains('@') && value.substringAfter('@').contains('.')

    /**
     * Collapses the one-row-per-phone-number shape of the contacts provider into one
     * entry per contact id, preserving first-seen order and de-duplicating the numbers
     * themselves (a number synced from two providers appears twice).
     */
    fun dedupe(rows: List<ContactPhoneRow>, limit: Int): DedupedContacts {
        val byId = LinkedHashMap<Long, MutableList<String>>()
        val names = HashMap<Long, String>()
        var extraRows = 0

        for (row in rows) {
            val numbers = byId.getOrPut(row.contactId) { ArrayList(2) }
            val number = row.number.trim()
            if (number.isNotEmpty() && !numbers.contains(number)) {
                numbers += number
            } else if (number.isNotEmpty()) {
                extraRows++
            }
            if (row.displayName.isNotBlank() && !names.containsKey(row.contactId)) {
                names[row.contactId] = row.displayName.trim()
            }
        }

        val capped = limit.coerceIn(1, MAX_LIMIT)
        val all = byId.map { (id, numbers) ->
            ContactSummary(
                contactId = id,
                displayName = names[id]?.takeIf { it.isNotBlank() } ?: "(no name)",
                numbers = numbers.toList(),
            )
        }
        val shown = all.take(capped)
        return DedupedContacts(
            contacts = shown,
            total = all.size,
            truncated = all.size > shown.size,
            duplicateRowsCollapsed = extraRows,
        )
    }

    /**
     * Splits raw Data rows into a detail record, dropping empty and junk values.
     *
     * The shape check runs on the RAW value, before any truncation: a long email cut to
     * 48 characters can lose its TLD, and validating afterwards would silently discard a
     * perfectly good address. Truncation is presentation, not a filter.
     */
    fun buildDetail(
        contactId: Long,
        displayName: String,
        phones: List<String>,
        emails: List<String>,
        organization: String?,
    ): ContactDetail = ContactDetail(
        contactId = contactId,
        displayName = displayName.trim().ifEmpty { "(no name)" },
        organization = clean(organization, MAX_ORGANIZATION_CHARS),
        phones = phones.filter(::isPhoneLike).map { it.trim() }.distinct().take(MAX_PHONES)
            .mapNotNull { clean(it, MAX_VALUE_CHARS) },
        emails = emails.filter(::isEmailLike).map { it.trim() }.distinct().take(MAX_EMAILS)
            .map { shortenEmail(it) },
    )

    /**
     * Emails are shortened from the LEFT of the local part, never the right of the whole
     * address. Cutting `verylongname@a-corporate-domain.example.com` at 48 characters
     * produces `verylongname@a-corporate-dom…`, which has lost the part that tells two
     * addresses apart — and the TLD. `veryl…@a-corporate-domain.example.com` is still
     * actionable. 80 chars is the cap; a real address is almost always under it.
     */
    fun shortenEmail(raw: String): String {
        val text = raw.trim()
        if (text.length <= MAX_EMAIL_CHARS) return text
        val at = text.indexOf('@')
        if (at <= 0) return clean(text, MAX_EMAIL_CHARS) ?: text
        val domain = text.substring(at)
        val keep = MAX_EMAIL_CHARS - domain.length - 1
        if (keep >= 2) return text.substring(0, keep).trimEnd('.', '+') + "…" + domain
        return clean(text, MAX_EMAIL_CHARS) ?: text
    }
}

/** The result of dedup: what is shown, what was there, and what was collapsed. */
internal data class DedupedContacts(
    val contacts: List<ContactSummary>,
    val total: Int,
    val truncated: Boolean,
    val duplicateRowsCollapsed: Int,
)

// ------------------------------------------------------------------------- rendering --

internal object ContactsText {

    fun search(
        deduped: DedupedContacts,
        query: String,
    ): String {
        if (deduped.contacts.isEmpty()) return "No contacts matched \"$query\"."

        val header = if (deduped.truncated) {
            "Showing ${deduped.contacts.size} of ${deduped.total} contacts matching \"$query\":"
        } else {
            "${deduped.contacts.size} contact${if (deduped.contacts.size == 1) "" else "s"} " +
                "matching \"$query\":"
        }

        val body = deduped.contacts.mapIndexed { index, contact ->
            val name = ContactsArgs.clean(contact.displayName, ContactsArgs.MAX_NAME_CHARS) ?: "(no name)"
            val shown = contact.numbers.take(ContactsArgs.PHONES_PER_SEARCH_LINE)
            val extra = contact.numbers.size - shown.size
            val numbers = if (shown.isEmpty()) {
                "no number"
            } else {
                shown.joinToString(", ") + if (extra > 0) " (+$extra more)" else ""
            }
            "${index + 1}. $name — $numbers"
        }

        return ObservationTruncator.truncate((listOf(header) + body).joinToString("\n"))
    }

    /**
     * The budget-critical one. 12 phones, long names, 4 emails, an organisation: this
     * must stay under ObservationTruncator.DEFAULT_BUDGET_CHARS and still say what was
     * dropped, because a silent cap is a lie to the model.
     */
    fun detail(detail: ContactDetail, phonesTotal: Int, emailsTotal: Int): String {
        val name = ContactsArgs.clean(detail.displayName, ContactsArgs.MAX_NAME_CHARS) ?: "(no name)"
        val lines = ArrayList<String>(4)
        lines += "$name (id ${detail.contactId})"
        detail.organization?.let { lines += "$it" }

        lines += if (detail.phones.isEmpty()) {
            "phones: none saved"
        } else {
            "phones: " + detail.phones.joinToString(", ") + dropped(phonesTotal, detail.phones.size)
        }

        lines += if (detail.emails.isEmpty()) {
            "emails: none saved"
        } else {
            "emails: " + detail.emails.joinToString(", ") + dropped(emailsTotal, detail.emails.size)
        }

        return ObservationTruncator.truncate(lines.joinToString("\n"))
    }

    /**
     * The dropped count is [total] minus [shown] — the number the model can go and ask
     * for with another call. Printing the total instead is the classic "cap that lies":
     * the model reads "12 more" when there are only 6, and stops looking.
     */
    private fun dropped(total: Int, shown: Int): String {
        val hidden = total - shown
        return if (hidden > 0) " (+$hidden more)" else ""
    }
}

// -------------------------------------------------------------------------- helpers --

/** The JsonPrimitive behind [key], treating an explicit JSON null as absent. */
internal fun primitive(args: ToolArgs, key: String): JsonPrimitive? {
    val value = args[key] as? JsonPrimitive ?: return null
    return if (value.content.equals("null", ignoreCase = true)) null else value
}

internal fun optionalString(args: ToolArgs, key: String, max: Int): String? =
    ContactsArgs.clean(primitive(args, key)?.content, max)

// ----------------------------------------------------------------------- the seam --

/**
 * The one thing these tools need from the Android platform.
 *
 * Same rationale as `CalendarProvider`: it lets the REAL `execute()` path — query
 * construction, row mapping, dedup, the 12-phone worst case, the close-in-finally
 * contract — be executed on a plain JVM against a fake provider and a fake cursor.
 * A leaked cursor on a phone is a real memory leak (docs/architecture.md §16), and
 * "I wrote a finally block" is not evidence.
 */
internal interface ContactsProvider {
    /** Returns a cursor the caller MUST close, or null when no contacts provider exists. */
    fun queryPhones(pattern: String, maxRows: Int): Cursor?

    /** Returns a cursor the caller MUST close over the contact's phone/email/org rows. */
    fun queryContactData(contactId: Long): Cursor?
}

/** The production implementation. The only place in this file that touches ContentResolver. */
internal class ResolverContactsProvider(private val resolver: ContentResolver) : ContactsProvider {

    override fun queryPhones(pattern: String, maxRows: Int): Cursor? = resolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        PHONE_PROJECTION,
        "(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\' " +
            "OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? ESCAPE '\\')",
        arrayOf(pattern, pattern),
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC " +
            "LIMIT $maxRows",
    )

    override fun queryContactData(contactId: Long): Cursor? = resolver.query(
        ContactsContract.Data.CONTENT_URI,
        DATA_PROJECTION,
        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (?,?,?)",
        arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        ),
        null,
    )

    private companion object {
        val PHONE_PROJECTION = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val DATA_PROJECTION = arrayOf(
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
    }
}

// ----------------------------------------------------------------------- the tools --

/**
 * Finds contacts by name or phone number.
 *
 * READ_ONLY. The provider returns one row per phone number, so the dedup here is
 * load-bearing: without it "dario" returns the same person three times and the model
 * reads that as three different people.
 */
class ContactsSearchTool internal constructor(
    private val provider: ContactsProvider,
    private val grant: PlatformGrant,
) : AgentTool {

    /** Production wiring: `ContactsSearchTool(context.contentResolver)`. */
        constructor(resolver: ContentResolver, grant: PlatformGrant) :
            this(ResolverContactsProvider(resolver), grant)

    override val definition: ToolDefinition = ToolMeta.CONTACTS_SEARCH.define(
        schema = ToolSchemas.contactsSearch,
        risk = ToolRisk.READ_ONLY,
        requiredPermission = "android.permission.READ_CONTACTS",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            // The permission question is asked of the PLATFORM, not of
            // `context.permissionGranted`. That flag is filled from a set the
            // app never populates, so it is false on every call; asking it
            // would fabricate a denial for a user who had granted contacts,
            // and skipping it would return an empty list for a user who had
            // not. Both are lies, and the second is the one that makes the
            // model say "you have no contacts".
            if (!grant.isGranted(ToolPermissions.CONTACTS)) return@withContext denied()
            if (context.signal.isCancelled()) return@withContext cancelled()

            val query = optionalString(args, "query", ContactsArgs.MAX_QUERY_CHARS)
                ?: return@withContext invalid("'query' is required and must not be blank")
            val limit = ContactsArgs.coerceLimit(primitive(args, "limit")?.content)

            // Fetch a little more than we show: the dedup happens after the query, so
            // limit rows can collapse to far fewer contacts.
            val fetch = (limit * 4).coerceIn(limit, ContactsArgs.MAX_LIMIT * 4)
            val pattern = "%${ContactsArgs.escapeLike(query)}%"

            val rows = ArrayList<ContactPhoneRow>(fetch)
            var wasCancelled = false
            var overflow = false

            try {
                val cursor: Cursor? = provider.queryPhones(pattern, fetch)
                if (cursor == null) {
                    return@withContext ToolResult(
                        success = false,
                        observation = "No contacts provider is available on this device, so no contacts " +
                            "could be read. Tell the user contacts access is unavailable.",
                        error = ToolError.Unavailable("no contacts provider"),
                    )
                }
                try {
                    while (cursor.moveToNext()) {
                        if (context.signal.isCancelled()) {
                            wasCancelled = true
                            break
                        }
                        if (rows.size >= fetch) {
                            overflow = true
                            break
                        }
                        val name = cursor.stringOrEmpty(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val number = cursor.stringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val id = cursor.longOrZero(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                        if (id > 0) rows += ContactPhoneRow(id, name, number)
                    }
                } finally {
                    cursor.close()
                }
            } catch (security: SecurityException) {
                return@withContext denied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                return@withContext ToolResult(
                    success = false,
                    observation = "Reading contacts failed (${t.javaClass.simpleName}). " +
                        "Do not retry; report that contacts could not be read.",
                    error = ToolError.Internal("contacts.query failed: ${t.javaClass.simpleName}"),
                )
            }

            if (wasCancelled) return@withContext cancelled()

            val deduped = ContactsArgs.dedupe(rows, limit).let {
                if (overflow && !it.truncated) it.copy(truncated = true) else it
            }

            ToolResult(
                success = true,
                observation = ContactsText.search(deduped, query),
                data = buildJsonObject {
                    put("query", query)
                    put("count", deduped.contacts.size)
                    put("total", deduped.total)
                    put("truncated", deduped.truncated)
                    put("contactIds", deduped.contacts.map { it.contactId }.joinToString(","))
                },
            )
        }

    private fun denied(): ToolResult = ToolResult(
        success = false,
        observation = PermissionDenial.observation("contacts.search", ToolPermissions.CONTACTS),
        error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.CONTACTS)),
    )

    private fun invalid(message: String): ToolResult = ToolResult(
        success = false,
        observation = "contacts.search was called with unusable arguments: $message",
        error = ToolError.InvalidArguments(message),
    )

    private fun cancelled(): ToolResult = ToolResult(
        success = false,
        observation = "The contact search was cancelled before it finished.",
        error = ToolError.Cancelled("cancelled during contacts.search"),
    )
}

/**
 * Full detail for one contact: every name, phone number, email and organisation.
 *
 * READ_ONLY. This is the tool that can blow the context budget, so every list is capped
 * and every cap says how much was dropped.
 */
class ContactsGetTool internal constructor(
    private val provider: ContactsProvider,
    private val grant: PlatformGrant,
) : AgentTool {

    /** Production wiring: `ContactsGetTool(context.contentResolver)`. */
        constructor(resolver: ContentResolver, grant: PlatformGrant) :
            this(ResolverContactsProvider(resolver), grant)

    override val definition: ToolDefinition = ToolMeta.CONTACTS_GET.define(
        schema = ToolSchemas.contactsGet,
        risk = ToolRisk.READ_ONLY,
        requiredPermission = "android.permission.READ_CONTACTS",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            // Asked of the platform, not of `context.permissionGranted` — see
            // the note on contacts.search. Without this the notFound() branch
            // below is reached for a user who simply was not allowed to look,
            // and the model reports that the contact does not exist.
            if (!grant.isGranted(ToolPermissions.CONTACTS)) return@withContext denied()
            if (context.signal.isCancelled()) return@withContext cancelled()

            val id = when (val parsed = ContactsArgs.parseContactId(primitive(args, "id")?.content)) {
                is ParsedContactId.Invalid -> return@withContext invalid(parsed.message)
                is ParsedContactId.Ok -> parsed.id
            }

            var name = ""
            var organization: String? = null
            val phones = ArrayList<String>(8)
            val emails = ArrayList<String>(4)
            var rowsSeen = 0
            var wasCancelled = false

            // One query over the Data table with the contact's mimetypes: a single cursor
            // instead of three, which is both faster and one less thing to leak.
            try {
                val cursor: Cursor? = provider.queryContactData(id)
                if (cursor == null) {
                    return@withContext notFound(id)
                }
                try {
                    while (cursor.moveToNext()) {
                        if (context.signal.isCancelled()) {
                            wasCancelled = true
                            break
                        }
                        rowsSeen++
                        val mimetype = cursor.stringOrEmpty(ContactsContract.Data.MIMETYPE)
                        val value = cursor.stringOrEmpty(ContactsContract.Data.DATA1)
                        if (name.isEmpty()) {
                            name = cursor.stringOrEmpty(ContactsContract.Contacts.DISPLAY_NAME)
                        }
                        when (mimetype) {
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones += value
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails += value
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                                if (organization.isNullOrBlank() && value.isNotBlank()) organization = value
                            }
                        }
                        // Hard stop: a single contact with hundreds of rows must not be
                        // read to the end just to be truncated afterwards.
                        if (rowsSeen >= ROW_SCAN_CAP) break
                    }
                } finally {
                    cursor.close()
                }
            } catch (security: SecurityException) {
                return@withContext denied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                return@withContext ToolResult(
                    success = false,
                    observation = "Reading contact $id failed (${t.javaClass.simpleName}). " +
                        "Do not retry; report that the contact could not be read.",
                    error = ToolError.Internal("contacts data query failed: ${t.javaClass.simpleName}"),
                )
            }

            if (wasCancelled) return@withContext cancelled()
            if (rowsSeen == 0) return@withContext notFound(id)

            val detail = ContactsArgs.buildDetail(id, name, phones, emails, organization)
            // The "+n more" counts must reflect the provider, not the post-filter list.
            val phoneTotal = phones.size.coerceAtLeast(detail.phones.size)
            val emailTotal = emails.size.coerceAtLeast(detail.emails.size)

            ToolResult(
                success = true,
                observation = ContactsText.detail(detail, phoneTotal, emailTotal),
                data = buildJsonObject {
                    put("id", id)
                    put("displayName", detail.displayName)
                    put("phoneCount", phoneTotal)
                    put("emailCount", emailTotal)
                },
            )
        }

    private fun denied(): ToolResult = ToolResult(
        success = false,
        observation = PermissionDenial.observation("contacts.get", ToolPermissions.CONTACTS),
        error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.CONTACTS)),
    )

    private fun invalid(message: String): ToolResult = ToolResult(
        success = false,
        observation = "contacts.get was called with unusable arguments: $message",
        error = ToolError.InvalidArguments(message),
    )

    private fun notFound(id: Long): ToolResult = ToolResult(
        success = false,
        observation = "No contact with id $id exists, or it has no phone, email or organisation saved. " +
            "Call contacts.search again to get a current contact id.",
        error = ToolError.NotFound("contact $id not found"),
    )

    private fun cancelled(): ToolResult = ToolResult(
        success = false,
        observation = "The contact lookup was cancelled before it finished.",
        error = ToolError.Cancelled("cancelled during contacts.get"),
    )

    private companion object {
        /** No real contact has this many rows; this is the RAM ceiling, not a guess. */
        const val ROW_SCAN_CAP = 200
    }
}

// -------------------------------------------------------------- cursor null-safety --

/** A column the provider did not return reads as empty, never as an exception. */
private fun Cursor.stringOrEmpty(column: String): String {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return ""
    return getString(index)?.trim().orEmpty()
}

private fun Cursor.longOrZero(column: String): Long {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0L else getLong(index)
}

// =====================================================================================
// The tool set
// =====================================================================================

/**
 * Both contacts tools, wired to a real [Context].
 *
 * WHY a factory: see the note on `calendarTools`. The composition root is the
 * single place that knows the shipped tool set, and this is what it calls.
 */
fun contactsTools(context: Context, grant: PlatformGrant): List<AgentTool> {
    val resolver = context.applicationContext.contentResolver
    return listOf(
        ContactsSearchTool(resolver, grant),
        ContactsGetTool(resolver, grant),
    )
}
