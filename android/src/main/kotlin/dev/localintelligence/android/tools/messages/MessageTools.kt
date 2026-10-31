package dev.localintelligence.android.tools.messages

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.guarded
import dev.localintelligence.android.tools.phone.PhoneNumber
import dev.localintelligence.android.tools.phone.cancelledBefore
import dev.localintelligence.android.tools.phone.invalidArgument
import dev.localintelligence.android.tools.phone.textArg
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformRequirement
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// =====================================================================================
// Texts and calls: search and send texts, and read the recent call log.
//
// Every tool here is gated twice, in this order: the default-assistant role first
// (DefaultAssistant), then the runtime permission (PlatformGrant). The role is read on
// every call, so the access stops the moment the person changes the default app.
//
// The text of a message and the name on a call are written by someone outside the phone,
// so these results are NETWORK origin and the controller fences them as untrusted.
// =====================================================================================

/** Texts searched per call. Scanning is capped so a long inbox cannot stall a turn. */
private const val SCAN_LIMIT: Int = 500
private const val DEFAULT_RESULTS: Int = 10
private const val MAX_RESULTS: Int = 20
private const val MAX_QUERY_CHARS: Int = 200
private const val MAX_FROM_CHARS: Int = 100
private const val MAX_TEXT_CHARS: Int = 1000
private const val SNIPPET_CHARS: Int = 140

/** `Telephony.TextBasedSmsColumns.MESSAGE_TYPE_SENT`, written out so the query does not depend on the constant's home. */
private const val SMS_TYPE_SENT: Int = 2

/**
 * The display name for a phone number from the contacts, or null when none resolves.
 *
 * Null is also the answer when READ_CONTACTS is not granted, so a text or call still shows
 * its number rather than failing: the name is an improvement, never a requirement.
 */
internal object CallerNames {

    fun nameFor(context: Context, number: String): String? {
        if (number.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, ToolPermissions.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Short local date and time, such as "Oct 30, 6:02 PM", in the phone's own format. */
private fun stamp(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
)

/** The permission refusal for a tool, in the shared wording. */
private fun permissionRefusal(tool: String, requirement: PlatformRequirement): ToolResult =
    ToolResult(
        success = false,
        observation = PermissionDenial.observation(tool, requirement),
        error = ToolError.PermissionDenied(PermissionDenial.summary(requirement)),
    )

// =====================================================================================
// Schemas
// =====================================================================================

private val SMS_SEARCH_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("query") {
            put("type", "string")
            put("maxLength", MAX_QUERY_CHARS)
            put("description", "Words to find in the text. Optional.")
        }
        putJsonObject("from") {
            put("type", "string")
            put("maxLength", MAX_FROM_CHARS)
            put("description", "Sender name or number. Optional.")
        }
        putJsonObject("max_results") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", MAX_RESULTS)
            put("description", "How many texts to return. Default $DEFAULT_RESULTS.")
        }
    }
    putJsonArray("required") { }
}

private val SMS_SEND_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("to") {
            put("type", "string")
            put("maxLength", PhoneNumber.MAX_CHARS)
            put("description", "Phone number to text, e.g. +31612345678. Look a name up with contacts.search first.")
        }
        putJsonObject("text") {
            put("type", "string")
            put("maxLength", MAX_TEXT_CHARS)
            put("description", "The full text message.")
        }
    }
    putJsonArray("required") { add("to"); add("text") }
}

private val CALLS_RECENT_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("max_results") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", MAX_RESULTS)
            put("description", "How many calls to return. Default $DEFAULT_RESULTS.")
        }
        putJsonObject("type") {
            put("type", "string")
            putJsonArray("enum") { add("missed"); add("incoming"); add("outgoing") }
            put("description", "Only missed, incoming or outgoing calls. Optional.")
        }
    }
    putJsonArray("required") { }
}

// =====================================================================================
// sms.search
// =====================================================================================

/** NETWORK. Reads the texts on the phone, newest first, with an optional word and sender filter. */
class SmsSearchTool(private val appContext: Context, private val grant: PlatformGrant) : AgentTool {

    override val definition = ToolDefinition(
        name = "sms.search",
        description = "Search the texts on this phone, newest first. Use when the user asks what a text said or whether someone texted.",
        category = "messages",
        schema = SMS_SEARCH_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        // WHY NETWORK: the text bodies were written by the people who sent them.
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf("sms", "text", "texts", "messages", "text from", "did he text", "sms search"),
        requiredPermission = ToolPermissions.READ_SMS,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val query = when (val r = textArg(args, "query", MAX_QUERY_CHARS, optional = true)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> null
            is ArgResult.Unusable -> return invalidArgument("query must be one short piece of text, but was ${r.got}.")
        }
        val from = when (val r = textArg(args, "from", MAX_FROM_CHARS, optional = true)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> null
            is ArgResult.Unusable -> return invalidArgument("from must be one short name or number, but was ${r.got}.")
        }
        // A count the model garbled falls back to the default: a read changes nothing.
        val max = when (val r = ArgCoerce.intIn(args, "max_results", 1, MAX_RESULTS)) {
            is ArgResult.Present -> r.value
            else -> DEFAULT_RESULTS
        }

        DefaultAssistant.refusalUnlessHeld(appContext, "Reading and sending texts")?.let { return it }
        if (!grant.isGranted(ToolPermissions.SMS_READ)) return permissionRefusal("sms.search", ToolPermissions.SMS_READ)
        if (context.signal.isCancelled()) return cancelledBefore("sms.search")

        return withContext(Dispatchers.IO) {
            guarded("sms.search") { search(query, from, max, context.signal) }
        }
    }

    private fun search(query: String?, from: String?, max: Int, signal: CancellationSignal): ToolResult {
        val selection = query?.let { "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'" }
        val selectionArgs = query?.let { arrayOf("%${escapeLike(it)}%") }
        val cursor = appContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return ToolResult(
            success = false,
            observation = "Android did not open the texts, so nothing was read.",
            error = ToolError.Unavailable("sms provider returned no cursor"),
        )

        val fromDigits = from?.filter(Char::isDigit).orEmpty()
        val names = HashMap<String, String?>()
        val lines = mutableListOf<String>()
        var scanned = 0
        cursor.use { c ->
            while (c.moveToNext() && lines.size < max && scanned < SCAN_LIMIT) {
                if (signal.isCancelled()) return cancelledBefore("sms.search")
                scanned++
                val address = c.getString(0).orEmpty()
                val body = c.getString(1).orEmpty()
                val date = c.getLong(2)
                val sent = c.getInt(3) == SMS_TYPE_SENT
                val name = if (names.containsKey(address)) names[address] else {
                    CallerNames.nameFor(appContext, address).also { names[address] = it }
                }
                if (from != null && !matchesFrom(from, fromDigits, name, address)) continue
                val who = name ?: address.ifBlank { "unknown" }
                val snippet = ArgCoerce.ellipsize(body.replace(Regex("\\s+"), " ").trim(), SNIPPET_CHARS)
                lines += "${stamp(appContext, date)} ${if (sent) "to" else "from"} $who: \"$snippet\""
            }
        }

        val described = listOfNotNull(query?.let { "'$it'" }, from?.let { "from '$it'" }).joinToString(" ")
        if (lines.isEmpty()) {
            return ToolResult(
                success = true,
                observation = if (described.isEmpty()) "No texts on this phone."
                else "No texts matched $described.",
            )
        }
        val note = if (scanned >= SCAN_LIMIT) " Only the newest $SCAN_LIMIT texts were checked." else ""
        val header = if (described.isEmpty()) "${lines.size} text(s), newest first."
        else "${lines.size} text(s) matching $described, newest first."
        return ToolResult(
            success = true,
            observation = ObservationTruncator.truncate(
                (listOf("$header$note") + lines).joinToString("\n"),
            ),
            data = buildJsonObject { put("count", lines.size) },
        )
    }

    /** A sender matches by contact name, or by the last digits of a number. */
    private fun matchesFrom(from: String, fromDigits: String, name: String?, address: String): Boolean {
        if (name != null && name.contains(from, ignoreCase = true)) return true
        val addressDigits = address.filter(Char::isDigit)
        val n = minOf(fromDigits.length, addressDigits.length, 9)
        return n >= 6 && addressDigits.takeLast(n) == fromDigits.takeLast(n)
    }

    /** Escapes the LIKE wildcards so the model's words match literally. */
    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

// =====================================================================================
// sms.send
// =====================================================================================

/**
 * EXTERNAL_COMMUNICATION. Sends a text now, so the policy asks every time, and the
 * "send without asking" setting never covers it (see `PolicyConfig.neverAutoAccept`).
 * The confirmation names the recipient, and the text is in the arguments the person can open.
 */
class SmsSendTool(private val appContext: Context, private val grant: PlatformGrant) : AgentTool {

    override val definition = ToolDefinition(
        name = "sms.send",
        description = "Sends a text message directly to a phone number, after the user confirms. Use " +
            "when the user asks to text someone now; message.draft opens a draft instead.",
        category = "messages",
        schema = SMS_SEND_SCHEMA,
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("send a text", "send sms", "sms", "text someone", "stuur een sms"),
        requiredPermission = ToolPermissions.SEND_SMS,
    )

    /** "Maria (+31612345678)" when the number is a contact, so the confirmation names who it is. */
    override suspend fun describeTarget(args: ToolArgs): String? {
        val raw = (args["to"] as? JsonPrimitive)?.content ?: return null
        val number = PhoneNumber.clean(raw) ?: return null
        val name = withContext(Dispatchers.IO) { CallerNames.nameFor(appContext, number) } ?: return null
        return "$name ($number)"
    }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val to = when (val r = textArg(args, "to", PhoneNumber.MAX_CHARS * 2)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> return invalidArgument("to is required: the phone number to text, e.g. +31612345678.")
            is ArgResult.Unusable -> return invalidArgument("to must be one piece of text, but was ${r.got}.")
        }
        val number = PhoneNumber.clean(to)
            ?: return invalidArgument(
                "\"${ArgCoerce.ellipsize(to, 24)}\" is not a phone number. Send digits with an " +
                    "optional leading +, e.g. +31612345678. Look up a name with contacts.search first.",
            )
        val text = when (val r = textArg(args, "text", MAX_TEXT_CHARS)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> return invalidArgument("text is required: the full message to send.")
            is ArgResult.Unusable -> return invalidArgument(
                "text must be one piece of text under $MAX_TEXT_CHARS characters, but was ${r.got}.",
            )
        }

        DefaultAssistant.refusalUnlessHeld(appContext, "Reading and sending texts")?.let { return it }
        if (!grant.isGranted(ToolPermissions.SMS_SEND)) return permissionRefusal("sms.send", ToolPermissions.SMS_SEND)
        if (context.signal.isCancelled()) return cancelledBefore("sms.send")

        return withContext(Dispatchers.IO) {
            guarded("sms.send") { send(number, text) }
        }
    }

    private fun send(number: String, text: String): ToolResult {
        val sms = smsManager() ?: return ToolResult(
            success = false,
            observation = "This phone's messaging service is not available, so nothing was sent.",
            error = ToolError.Unavailable("no SmsManager"),
        )
        // A long text is split by the platform's own rule and sent as one multipart message.
        val parts = sms.divideMessage(text)
        if (parts.size > 1) {
            sms.sendMultipartTextMessage(number, null, parts, null, null)
        } else {
            sms.sendTextMessage(number, null, text, null, null)
        }
        val name = CallerNames.nameFor(appContext, number)
        val who = if (name != null) "$name ($number)" else number
        return ToolResult(
            success = true,
            observation = "Text handed to the phone to send to $who. Delivery is not confirmed.",
        )
    }

    /** `SmsManager.getDefault()` is deprecated from API 31, where the service is fetched by class. */
    private fun smsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
}

// =====================================================================================
// calls.recent
// =====================================================================================

private val CALL_TYPE_BY_ARG: Map<String, Int> = mapOf(
    "missed" to CallLog.Calls.MISSED_TYPE,
    "incoming" to CallLog.Calls.INCOMING_TYPE,
    "outgoing" to CallLog.Calls.OUTGOING_TYPE,
)

/** NETWORK. Reads the call log, newest first, optionally one kind of call. */
class CallsRecentTool(private val appContext: Context, private val grant: PlatformGrant) : AgentTool {

    override val definition = ToolDefinition(
        name = "calls.recent",
        description = "List recent calls, newest first, with who they were with. Use when the user " +
            "asks who called, missed calls or who they called.",
        category = "messages",
        schema = CALLS_RECENT_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        // WHY NETWORK: a caller's name can come from the network, not from the person's own contacts.
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf("call log", "missed call", "missed calls", "who called", "recent calls", "call history"),
        requiredPermission = ToolPermissions.READ_CALL_LOG,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val max = when (val r = ArgCoerce.intIn(args, "max_results", 1, MAX_RESULTS)) {
            is ArgResult.Present -> r.value
            else -> DEFAULT_RESULTS
        }
        val typeArg = when (val r = ArgCoerce.string(args, "type")) {
            is ArgResult.Present -> r.value.trim().lowercase().ifEmpty { null }
            else -> null
        }
        if (typeArg != null && typeArg !in CALL_TYPE_BY_ARG) {
            return invalidArgument("type must be one of ${CALL_TYPE_BY_ARG.keys.joinToString(", ")}, but was \"${ArgCoerce.ellipsize(typeArg, 24)}\".")
        }

        DefaultAssistant.refusalUnlessHeld(appContext, "Reading call history")?.let { return it }
        if (!grant.isGranted(ToolPermissions.CALL_LOG_READ)) {
            return permissionRefusal("calls.recent", ToolPermissions.CALL_LOG_READ)
        }
        if (context.signal.isCancelled()) return cancelledBefore("calls.recent")

        return withContext(Dispatchers.IO) {
            guarded("calls.recent") { recent(max, typeArg, context.signal) }
        }
    }

    private fun recent(max: Int, typeArg: String?, signal: CancellationSignal): ToolResult {
        val typeCode = typeArg?.let { CALL_TYPE_BY_ARG[it] }
        val cursor = appContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            typeCode?.let { "${CallLog.Calls.TYPE} = ?" },
            typeCode?.let { arrayOf(it.toString()) },
            "${CallLog.Calls.DATE} DESC",
        ) ?: return ToolResult(
            success = false,
            observation = "Android did not open the call log, so nothing was read.",
            error = ToolError.Unavailable("call log provider returned no cursor"),
        )

        val lines = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext() && lines.size < max) {
                if (signal.isCancelled()) return cancelledBefore("calls.recent")
                val number = c.getString(0).orEmpty()
                val cached = c.getString(1)?.takeIf { it.isNotBlank() }
                val kind = when (c.getInt(2)) {
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    else -> "other"
                }
                val date = c.getLong(3)
                val seconds = c.getLong(4)
                val who = cached ?: CallerNames.nameFor(appContext, number) ?: number.ifBlank { "unknown" }
                val length = if (seconds > 0) ", ${seconds / 60} min ${seconds % 60} s" else ""
                lines += "${stamp(appContext, date)} $kind $who$length"
            }
        }

        if (lines.isEmpty()) {
            return ToolResult(
                success = true,
                observation = if (typeArg == null) "No calls in the call log." else "No $typeArg calls in the call log.",
            )
        }
        val header = if (typeArg == null) "${lines.size} call(s), newest first."
        else "${lines.size} $typeArg call(s), newest first."
        return ToolResult(
            success = true,
            observation = ObservationTruncator.truncate((listOf(header) + lines).joinToString("\n")),
            data = buildJsonObject { put("count", lines.size) },
        )
    }
}

/** The texts and calls tools, wired to a real [Context]. */
fun messageTools(context: Context, grant: PlatformGrant): List<AgentTool> {
    val app = context.applicationContext
    return listOf(
        SmsSearchTool(app, grant),
        SmsSendTool(app, grant),
        CallsRecentTool(app, grant),
    )
}
