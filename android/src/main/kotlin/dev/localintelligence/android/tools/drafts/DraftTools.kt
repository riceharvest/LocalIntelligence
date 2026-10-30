package dev.localintelligence.android.tools.drafts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// =====================================================================================
// Drafts: message.draft and email.draft.
//
// Both only OPEN the user's messaging or email app with the text already filled in.
// Nothing is sent by this code, so both are REVERSIBLE rather than
// EXTERNAL_COMMUNICATION: the user reviews the draft and taps send themselves. The
// observations say so, so the model never tells the user a message went out.
// =====================================================================================

private const val MAX_BODY_CHARS = 2_000
private const val MAX_SUBJECT_CHARS = 200
private const val MAX_PHONE_DIGITS = 20
private const val MIN_PHONE_DIGITS = 3

private val MESSAGE_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("to") {
            put("type", "string")
            put("maxLength", 40)
            put("description", "Phone number of the recipient, e.g. +31612345678. Optional.")
        }
        putJsonObject("body") {
            put("type", "string")
            put("maxLength", MAX_BODY_CHARS)
            put("description", "The text of the message.")
        }
    }
    putJsonArray("required") { add("body") }
}

private val EMAIL_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("to") {
            put("type", "string")
            put("maxLength", 120)
            put("description", "Email address of the recipient. Optional.")
        }
        putJsonObject("subject") {
            put("type", "string")
            put("maxLength", MAX_SUBJECT_CHARS)
            put("description", "The subject line. Optional.")
        }
        putJsonObject("body") {
            put("type", "string")
            put("maxLength", MAX_BODY_CHARS)
            put("description", "The text of the email.")
        }
    }
    putJsonArray("required") { add("body") }
}

class MessageDraftTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "message.draft",
        description = "Open the messaging app with a text message drafted for the user to review " +
            "and send. Nothing is sent. Use when the user asks to text or message someone.",
        category = "drafts",
        schema = MESSAGE_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        // Dutch: the scorer reads only the tool's English text, so a Dutch request needs these to count as asked.
        tags = setOf(
            "text", "sms", "message", "text message", "draft a text", "send a text",
            "bericht", "stuur", "appje", "berichtje",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val body = when (val parsed = ArgCoerce.string(args, "body")) {
            is ArgResult.Present -> parsed.value.trim()
            is ArgResult.Missing -> return invalid("body is required: the text of the message.")
            is ArgResult.Unusable -> return invalid("body must be text, but was ${parsed.got}.")
        }
        if (body.isEmpty()) return invalid("body is empty. Send the text of the message.")
        if (body.length > MAX_BODY_CHARS) {
            return invalid("body is ${body.length} characters; the limit is $MAX_BODY_CHARS. Shorten it.")
        }

        val rawTo = (ArgCoerce.string(args, "to") as? ArgResult.Present)?.value?.trim().orEmpty()
        val number = rawTo.filter { it.isDigit() || it == '+' }
        val digits = number.count { it.isDigit() }
        val plusPlacedOk = number.count { it == '+' } <= 1 && number.indexOf('+') <= 0
        if (rawTo.isNotEmpty() && (digits !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS || !plusPlacedOk)) {
            return invalid("to must be a phone number such as +31612345678, but was \"$rawTo\".")
        }

        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the draft was opened.",
                error = ToolError.Cancelled("cancelled before message.draft"),
            )
        }

        val uri = Uri.parse(if (number.isEmpty()) "smsto:" else "smsto:$number")
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val to = if (number.isEmpty()) "a new message with no recipient yet" else number
        return launch(
            intent = intent,
            success = "Opened a text draft to $to in the messaging app. Nothing was sent; " +
                "the user must review it and tap send.",
            failure = "No messaging app on this phone could open a text draft, so nothing was opened. " +
                "Tell the user to set a default messaging app.",
            tool = "message.draft",
        )
    }

    private fun launch(intent: Intent, success: String, failure: String, tool: String): ToolResult =
        try {
            appContext.startActivity(intent)
            ToolResult(success = true, observation = success)
        } catch (e: ActivityNotFoundException) {
            ToolResult(success = false, observation = failure, error = ToolError.Unavailable("no app for $tool"))
        } catch (e: SecurityException) {
            // Background starts can be refused by the platform; say so instead of claiming success.
            ToolResult(success = false, observation = failure, error = ToolError.Unavailable("startActivity refused for $tool"))
        }

    private fun invalid(message: String): ToolResult = ToolResult(
        success = false,
        observation = message,
        error = ToolError.InvalidArguments(message),
    )
}

class EmailDraftTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "email.draft",
        description = "Open the email app with an email drafted for the user to review and send. " +
            "Nothing is sent. Use when the user asks to email someone or write an email.",
        category = "drafts",
        schema = EMAIL_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("email", "e-mail", "mail", "write an email", "draft an email", "send an email"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val body = when (val parsed = ArgCoerce.string(args, "body")) {
            is ArgResult.Present -> parsed.value.trim()
            is ArgResult.Missing -> return invalid("body is required: the text of the email.")
            is ArgResult.Unusable -> return invalid("body must be text, but was ${parsed.got}.")
        }
        if (body.isEmpty()) return invalid("body is empty. Send the text of the email.")
        if (body.length > MAX_BODY_CHARS) {
            return invalid("body is ${body.length} characters; the limit is $MAX_BODY_CHARS. Shorten it.")
        }

        val to = (ArgCoerce.string(args, "to") as? ArgResult.Present)?.value?.trim().orEmpty()
        if (to.isNotEmpty() && !isEmailAddress(to)) {
            return invalid("to must be an email address such as name@example.com, but was \"$to\".")
        }
        val subject = (ArgCoerce.string(args, "subject") as? ArgResult.Present)?.value
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_SUBJECT_CHARS)
            .orEmpty()

        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the draft was opened.",
                error = ToolError.Cancelled("cancelled before email.draft"),
            )
        }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (to.isNotEmpty()) intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        if (subject.isNotEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, subject)

        return try {
            appContext.startActivity(intent)
            val recipient = if (to.isEmpty()) "a new email with no recipient yet" else "an email to $to"
            ToolResult(
                success = true,
                observation = "Opened a draft of $recipient in the email app. Nothing was sent; " +
                    "the user must review it and tap send.",
            )
        } catch (e: ActivityNotFoundException) {
            noEmailApp()
        } catch (e: SecurityException) {
            // Background starts can be refused by the platform; say so instead of claiming success.
            noEmailApp()
        }
    }

    private fun noEmailApp(): ToolResult = ToolResult(
        success = false,
        observation = "No email app on this phone could open a draft, so nothing was opened. " +
            "Tell the user to set a default email app.",
        error = ToolError.Unavailable("no app for email.draft"),
    )

    /** Shape check only: something@something.tld. Real validity is the mail app's job. */
    private fun isEmailAddress(value: String): Boolean {
        val at = value.indexOf('@')
        return at > 0 && value.indexOf('@', at + 1) < 0 && value.substringAfter('@').contains('.') &&
            !value.any { it.isWhitespace() }
    }

    private fun invalid(message: String): ToolResult = ToolResult(
        success = false,
        observation = message,
        error = ToolError.InvalidArguments(message),
    )
}

/** The tool set for the drafts family. */
fun draftTools(context: Context): List<AgentTool> {
    val app = context.applicationContext
    return listOf(MessageDraftTool(app), EmailDraftTool(app))
}
