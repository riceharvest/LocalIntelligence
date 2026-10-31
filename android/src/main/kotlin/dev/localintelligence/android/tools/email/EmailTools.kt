package dev.localintelligence.android.tools.email

import android.text.Html
import com.sun.mail.imap.IMAPFolder
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.FolderNotFoundException
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

// ===========================================================================
// The three email tools. Their definitions come from V0ToolCatalogue, so the risk
// tier here is the catalogue's by construction.
//
// email.search and email.read are READ_ONLY: the mailbox is opened with EXAMINE, so
// nothing is marked read or changed. email.send is EXTERNAL_COMMUNICATION and refuses
// to run unless a human approved this exact call (context.userConfirmed).
//
// Mail text is written by whoever sent the mail, so the tool definitions are NETWORK
// origin and every observation is fenced as untrusted text. Bodies are cut with
// ObservationTruncator. Passwords and server replies are never put in an observation.
// ===========================================================================

private const val CONNECT_MS = "15000"
private const val IO_MS = "30000"
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val SNIPPET_CHARS = 200
private const val BODY_BUDGET = 1300
private const val MAX_BODY_CHARS = 10_000
private const val MAX_SUBJECT_CHARS = 200
private const val MAX_ADDRESS_CHARS = 254
private val RECIPIENT_SHAPE = Regex("[^@\\s]{1,64}@[^@\\s]{1,253}")

/** Tools for the given account. [account] is read at each call, so a settings change applies at once. */
internal fun emailTools(account: () -> MailAccount?): List<AgentTool> = listOf(
    EmailSearchTool(account),
    EmailReadTool(account),
    EmailSendTool(account),
)

// ---------------------------------------------------------------------------
// email.search
// ---------------------------------------------------------------------------

/** IMAP SEARCH for words in the subject or body, from one sender, in the last few days. */
private class EmailSearchTool(private val account: () -> MailAccount?) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("email.search")) { "email.search missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val tool = definition.name
            try {
                val query = textArg(args, "query")?.take(200)
                val from = textArg(args, "from")?.take(120)
                val sinceDays = intArg(args, "since_days", default = 14, min = 1, max = 365)
                val maxResults = intArg(args, "max_results", default = 10, min = 1, max = 25)
                val folderName = folderArg(args) ?: return@withContext invalid(tool, "folder must be a mailbox name, such as INBOX.")
                val acct = account() ?: return@withContext notSetUp(tool)
                if (context.signal.isCancelled()) return@withContext cancelled(tool)

                val words = query?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
                val terms = mutableListOf<SearchTerm>(
                    ReceivedDateTerm(ComparisonTerm.GE, Date(System.currentTimeMillis() - sinceDays * DAY_MS)),
                )
                for (word in words) terms += OrTerm(SubjectTerm(word), BodyTerm(word))
                if (from != null) terms += FromStringTerm(from)
                val term = AndTerm(terms.toTypedArray())

                val wanted = listOfNotNull(
                    query?.let { "'$it'" },
                    from?.let { "from $it" },
                    "in the last $sinceDays days",
                ).joinToString(" ")

                val found = withFolder(acct, folderName, Folder.READ_ONLY) { folder ->
                    val matches = folder.search(term)
                    // IMAP message numbers grow with arrival, so the newest are at the end.
                    val newest = matches.toList().takeLast(maxResults).reversed()
                    if (newest.isNotEmpty()) {
                        folder.fetch(newest.toTypedArray(), FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
                    }
                    newest.map { message -> Row(uid = folder.getUID(message), message = message) }
                }

                if (found.isEmpty()) {
                    return@withContext ToolResult(
                        success = true,
                        observation = "No email matches $wanted.",
                    )
                }
                val text = StringBuilder("${found.size} email${plural(found.size)} match $wanted:")
                for (row in found) {
                    val message = row.message
                    val date = dateText(message.receivedDate ?: message.sentDate)
                    val sender = senderText(message.from)
                    val subject = oneLine(message.subject.orEmpty(), 120).ifEmpty { "(no subject)" }
                    text.append("\n- uid ${row.uid}, $date, from $sender, subject \"$subject\"")
                    val snippet = snippetOf(message)
                    if (snippet.isNotEmpty()) text.append("\n  $snippet")
                }
                ToolResult(success = true, observation = ObservationTruncator.truncate(text.toString()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mailFailure(tool, e, account())
            }
        }
}

private class Row(val uid: Long, val message: Message)

// ---------------------------------------------------------------------------
// email.read
// ---------------------------------------------------------------------------

/** One message by uid: headers, the plain-text body cut to budget, and attachments by name and size. */
private class EmailReadTool(private val account: () -> MailAccount?) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("email.read")) { "email.read missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val tool = definition.name
            try {
                val uid = longArg(args, "uid")
                    ?: return@withContext invalid(tool, "email.read needs uid, the number from email.search.")
                val folderName = folderArg(args) ?: return@withContext invalid(tool, "folder must be a mailbox name, such as INBOX.")
                val acct = account() ?: return@withContext notSetUp(tool)
                if (context.signal.isCancelled()) return@withContext cancelled(tool)

                val observation = withFolder(acct, folderName, Folder.READ_ONLY) { folder ->
                    val message = folder.getMessageByUID(uid)
                        ?: return@withFolder null
                    val parts = BodyParts()
                    collect(message, parts)
                    val text = normalize(parts.plain ?: parts.html?.let(::htmlToText).orEmpty())
                    val body = ObservationTruncator.truncate(text.ifEmpty { "(this email has no text)" }, BODY_BUDGET)
                    val attachments = if (parts.attachments.isEmpty()) {
                        "Attachments: none."
                    } else {
                        "Attachments: " + parts.attachments.joinToString("; ") { "${it.name} (${sizeText(it.size)})" } + "."
                    }
                    listOf(
                        "From: ${senderText(message.from)}",
                        "Date: ${dateText(message.receivedDate ?: message.sentDate)}",
                        "Subject: ${oneLine(message.subject.orEmpty(), 200).ifEmpty { "(no subject)" }}",
                        "",
                        body,
                        "",
                        attachments,
                    ).joinToString("\n")
                } ?: return@withContext ToolResult(
                    success = false,
                    observation = "No email with uid $uid in $folderName. Search again with email.search.",
                    error = ToolError.NotFound("no message with that uid"),
                )
                ToolResult(success = true, observation = ObservationTruncator.truncate(observation))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mailFailure(tool, e, account())
            }
        }
}

// ---------------------------------------------------------------------------
// email.send
// ---------------------------------------------------------------------------

/**
 * Sends one plain-text email over SMTP. With [reply_to_uid] it first reads that message's
 * Message-ID and References, so the reply threads in the recipient's client.
 */
private class EmailSendTool(private val account: () -> MailAccount?) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("email.send")) { "email.send missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val tool = definition.name
            try {
                val to = textArg(args, "to")
                    ?.takeIf { it.length <= MAX_ADDRESS_CHARS && RECIPIENT_SHAPE.matches(it) }
                    ?: return@withContext invalid(tool, "email.send needs to, one recipient address.")
                val subject = textArg(args, "subject")?.take(MAX_SUBJECT_CHARS)
                    ?: return@withContext invalid(tool, "email.send needs subject.")
                if (subject.any { it == '\r' || it == '\n' }) {
                    return@withContext invalid(tool, "The subject must be one line.")
                }
                val body = (args["body"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@withContext invalid(tool, "email.send needs body, the text of the email.")
                if (body.length > MAX_BODY_CHARS) {
                    return@withContext invalid(tool, "The body is longer than $MAX_BODY_CHARS characters. Shorten it.")
                }
                val replyUid = if (args["reply_to_uid"] == null) null
                else longArg(args, "reply_to_uid") ?: return@withContext invalid(tool, "reply_to_uid must be a uid from email.search.")
                val acct = account() ?: return@withContext notSetUp(tool)

                // The approval is the only thing that lets this run. The runtime sets userConfirmed
                // once a person has approved this exact call, so nothing is sent without it.
                if (!context.userConfirmed) {
                    return@withContext ToolResult(
                        success = false,
                        observation = "Nothing was sent: the user has not approved this email. Show them the " +
                            "recipient, subject and text, and ask.",
                        error = ToolError.PermissionDenied("the user has not approved this email"),
                    )
                }
                if (context.signal.isCancelled()) return@withContext cancelled(tool)

                val (session, protocol) = smtpSession(acct)
                val message = MimeMessage(session)
                message.setFrom(InternetAddress(acct.address))
                message.setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(to, true)))
                message.setSubject(subject, "UTF-8")
                message.setText(body, "UTF-8")
                message.sentDate = Date()
                if (replyUid != null) {
                    val threaded = withFolder(acct, "INBOX", Folder.READ_ONLY) { folder ->
                        folder.getMessageByUID(replyUid)?.let { original ->
                            val id = original.getHeader("Message-ID")?.firstOrNull()?.trim()
                            val refs = original.getHeader("References")?.firstOrNull()?.trim()
                            if (id != null) {
                                message.setHeader("In-Reply-To", id)
                                message.setHeader("References", listOfNotNull(refs, id).joinToString(" "))
                            }
                            true
                        } ?: false
                    }
                    if (!threaded) {
                        return@withContext ToolResult(
                            success = false,
                            observation = "Nothing was sent: no email with uid $replyUid in INBOX. Search again with email.search.",
                            error = ToolError.NotFound("no message with that uid"),
                        )
                    }
                }

                val transport = session.getTransport(protocol)
                try {
                    transport.connect(acct.smtpHost, acct.smtpPort, acct.address, acct.password)
                    transport.sendMessage(message, message.allRecipients)
                } finally {
                    closeQuietly(transport)
                }
                ToolResult(success = true, observation = "Sent to $to with the subject \"${oneLine(subject, 120)}\".")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mailFailure(tool, e, account())
            }
        }
}

// ---------------------------------------------------------------------------
// Connections
// ---------------------------------------------------------------------------

/**
 * Runs [block] on [name] in an open folder, with a fresh IMAP connection that is closed afterwards.
 * IMAP is always implicit TLS (imaps) with the server identity checked.
 */
private fun <T> withFolder(acct: MailAccount, name: String, mode: Int, block: (IMAPFolder) -> T): T {
    val props = Properties().apply {
        put("mail.imaps.host", acct.imapHost)
        put("mail.imaps.port", acct.imapPort.toString())
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.ssl.checkserveridentity", "true")
        put("mail.imaps.connectiontimeout", CONNECT_MS)
        put("mail.imaps.timeout", IO_MS)
    }
    val store = Session.getInstance(props).getStore("imaps")
    store.connect(acct.imapHost, acct.imapPort, acct.address, acct.password)
    try {
        val folder = store.getFolder(name)
        if (!folder.exists()) throw FolderNotFoundException(folder, "no mailbox named $name")
        folder.open(mode)
        try {
            return block(folder as IMAPFolder)
        } finally {
            runCatching { folder.close(false) }
        }
    } finally {
        runCatching { store.close() }
    }
}

/**
 * The SMTP session and protocol for this account. Port 465 is TLS from the first byte ("smtps").
 * Any other port must accept STARTTLS, and the send fails rather than fall back to plain text.
 */
private fun smtpSession(acct: MailAccount): Pair<Session, String> {
    val implicit = acct.smtpPort == 465
    val protocol = if (implicit) "smtps" else "smtp"
    val props = Properties().apply {
        put("mail.$protocol.host", acct.smtpHost)
        put("mail.$protocol.port", acct.smtpPort.toString())
        put("mail.$protocol.auth", "true")
        put("mail.$protocol.ssl.checkserveridentity", "true")
        put("mail.$protocol.connectiontimeout", CONNECT_MS)
        put("mail.$protocol.timeout", IO_MS)
        if (!implicit) {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
        }
    }
    return Session.getInstance(props) to protocol
}

private fun closeQuietly(transport: Transport) {
    runCatching { transport.close() }
}

// ---------------------------------------------------------------------------
// Reading message parts
// ---------------------------------------------------------------------------

private class Attachment(val name: String, val size: Int)

private class BodyParts {
    var plain: String? = null
    var html: String? = null
    val attachments = mutableListOf<Attachment>()
}

/** Walks the MIME tree: the first text/plain and text/html bodies, and every attachment by name and size. */
private fun collect(part: Part, out: BodyParts) {
    if (part.isMimeType("multipart/*")) {
        val multi = part.content as? Multipart ?: return
        for (i in 0 until multi.count) collect(multi.getBodyPart(i), out)
        return
    }
    val name = part.fileName
    if (name != null || Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) {
        out.attachments += Attachment(name ?: "unnamed", part.size)
        return
    }
    if (out.plain == null && part.isMimeType("text/plain")) {
        out.plain = part.content as? String
    } else if (out.html == null && part.isMimeType("text/html")) {
        out.html = part.content as? String
    }
}

/** The first plain-text body, or the HTML body stripped to text. Empty when there is neither. */
private fun bodyText(message: Message): String = runCatching {
    val parts = BodyParts()
    collect(message, parts)
    parts.plain ?: parts.html?.let(::htmlToText).orEmpty()
}.getOrDefault("")

private fun snippetOf(message: Message): String =
    oneLine(normalize(bodyText(message)), SNIPPET_CHARS)

/**
 * HTML to plain text: script, style and head blocks removed, line breaks where block tags were,
 * other tags dropped, and entities decoded by the platform parser.
 */
internal fun htmlToText(html: String): String {
    val noBlocks = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
    val lines = noBlocks.replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6])>"), "\n")
    val noTags = lines.replace(Regex("<[^>]+>"), " ")
    return Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString()
}

private fun normalize(text: String): String =
    text.replace(' ', ' ')
        .lines()
        .map { it.trim() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** Collapses all whitespace to single spaces and cuts to [max] characters. */
private fun oneLine(text: String, max: Int): String =
    text.replace(Regex("\\s+"), " ").trim().take(max)

private fun senderText(from: Array<Address>?): String {
    val first = from?.firstOrNull() ?: return "unknown sender"
    val text = (first as? InternetAddress)?.let { addr ->
        addr.personal?.takeIf { it.isNotBlank() }?.let { "$it <${addr.address}>" } ?: addr.address
    } ?: first.toString()
    return oneLine(text, 100)
}

private fun dateText(date: Date?): String =
    if (date == null) "no date" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(date)

private fun sizeText(bytes: Int): String = when {
    bytes < 0 -> "size unknown"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(Locale.ROOT, bytes / 1048576.0)} MB"
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

// ---------------------------------------------------------------------------
// Arguments and failures
// ---------------------------------------------------------------------------

private fun textArg(args: ToolArgs, key: String): String? =
    (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** A whole number from a number or a numeric string, clamped to [min]..[max]. [default] when absent or not a number. */
private fun intArg(args: ToolArgs, key: String, default: Int, min: Int, max: Int): Int {
    val number = (args[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }
        ?: return default
    return number.toInt().coerceIn(min, max)
}

/** A positive whole number, or null when the argument is missing or not one. */
private fun longArg(args: ToolArgs, key: String): Long? {
    val text = (args[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
    return text.toLongOrNull()?.takeIf { it > 0 }
        ?: text.toDoubleOrNull()?.takeIf { it > 0 && it == Math.floor(it) && it < Long.MAX_VALUE }?.toLong()
}

/** The mailbox name: INBOX when absent, null when it is not a short plain name. */
private fun folderArg(args: ToolArgs): String? {
    val name = textArg(args, "folder") ?: return "INBOX"
    if (name.length > 100 || name.any { it.isISOControl() }) return null
    return name
}

private fun invalid(tool: String, what: String) = ToolResult(
    success = false,
    observation = what,
    error = ToolError.InvalidArguments(tool),
)

private fun notSetUp(tool: String) = ToolResult(
    success = false,
    observation = "Email is not set up. Add the address and app password in Settings, Plugins, Email.",
    error = ToolError.Unavailable("email is not set up"),
)

private fun cancelled(tool: String) = ToolResult(
    success = false,
    observation = "Cancelled before anything was sent.",
    error = ToolError.Cancelled("cancelled"),
)

/**
 * One plain sentence for a mail failure. A refused password names the provider's app-password
 * page; everything else says what could not be reached. The server's own text is not shown.
 */
private fun mailFailure(tool: String, e: Exception, acct: MailAccount?): ToolResult = when (e) {
    is AuthenticationFailedException -> ToolResult(
        success = false,
        observation = "The mail server refused the password for ${acct?.address ?: "this account"}. " +
            (acct?.passwordAdvice ?: "Check the password in Settings, Plugins, Email."),
        error = ToolError.PermissionDenied("the mail server refused the password"),
    )
    is FolderNotFoundException -> ToolResult(
        success = false,
        observation = "That mailbox does not exist on this account. Check the folder name.",
        error = ToolError.NotFound("no such mailbox"),
    )
    is MessagingException, is IOException -> ToolResult(
        success = false,
        observation = "Could not connect to the mail server securely. Check the server, port and connection, " +
            "then try again.",
        error = ToolError.Unavailable("mail server unreachable"),
    )
    else -> ToolResult(
        success = false,
        observation = "Email failed for an unexpected reason ($tool).",
        error = ToolError.Internal(e.javaClass.simpleName),
    )
}
