package dev.localintelligence.android.tools.email

import dev.localintelligence.android.tools.requireCatalogueAgreement
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.mode
import dev.localintelligence.core.tool.AgentTool

// ===========================================================================
// Email over IMAP and SMTP, with the user's own account and an app password.
// Off by default. Technical mode only: an address and an app password are
// setup, not something a Simple user can supply.
//
// Off, Simple mode, or no usable address, password and server: [EmailPlugin.tools]
// returns nothing and no socket is opened. Connections are made per tool call and
// closed when the call ends; nothing syncs in the background.
//
// What leaves the phone: the login, and the search, read or message the model
// asked for, sent to the user's own provider over TLS. TLS is always on: IMAP is
// always imaps (993 or the Custom port), and SMTP is implicit TLS on 465 or
// STARTTLS that must succeed on any other port. Plain IMAP and SMTP are never used.
// ===========================================================================

/**
 * The server pair for one provider, or null for Custom.
 *
 * Each preset's addresses come from that provider's official setup page, cited on the
 * entry. The Custom entry reads the Custom rows instead.
 */
class ServerPair(
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
)

/** A provider the Email settings can be set to. [passwordAdvice] is what to tell the user when the password is refused. */
enum class EmailPreset(
    val id: String,
    val label: String,
    val servers: ServerPair?,
    val passwordAdvice: String,
) {
    /**
     * Setup page: https://support.google.com/mail/answer/78892 (Choose your IMAP email client settings
     * for Gmail). Its text was not readable when this was written, so the values are the ones Google
     * documents for imap.gmail.com and smtp.gmail.com; check them against that page.
     * App password: https://support.google.com/accounts/answer/185833 (read from Google's help page).
     */
    GMAIL(
        "gmail", "Gmail",
        ServerPair("imap.gmail.com", 993, "smtp.gmail.com", 465),
        "Gmail needs an app password, not your normal password. Make one at " +
            "https://support.google.com/accounts/answer/185833.",
    ),

    /**
     * Setup page: https://support.microsoft.com/office/pop-imap-and-smtp-settings-8361e398-8af4-4e97-b147-6c6c4ac95353
     * It lists outlook.office365.com:993 (IMAP, SSL/TLS) and smtp-mail.outlook.com:587 (SMTP, STARTTLS)
     * for Outlook.com. The same page says Outlook.com uses OAuth2, so an app password may be refused.
     */
    OUTLOOK(
        "outlook", "Outlook / Hotmail",
        ServerPair("outlook.office365.com", 993, "smtp-mail.outlook.com", 587),
        "Outlook.com may need an app password and may refuse one, because Microsoft moves it to " +
            "OAuth2. See https://support.microsoft.com/office/pop-imap-and-smtp-settings-8361e398-8af4-4e97-b147-6c6c4ac95353.",
    ),

    /**
     * Setup page: https://support.apple.com/en-us/102525 (iCloud Mail settings: imap.mail.me.com:993 SSL,
     * smtp.mail.me.com:587 TLS). App-specific password: https://support.apple.com/en-us/102654.
     */
    ICLOUD(
        "icloud", "iCloud",
        ServerPair("imap.mail.me.com", 993, "smtp.mail.me.com", 587),
        "iCloud needs an app-specific password, not your Apple ID password. Make one at " +
            "https://support.apple.com/en-us/102654.",
    ),

    /**
     * Setup page: https://www.fastmail.help/hc/en-us/articles/1500000278342 (Server names and ports:
     * imap.fastmail.com:993 SSL, smtp.fastmail.com:465 SSL). App password: https://www.fastmail.help/hc/en-us/articles/360058752854.
     */
    FASTMAIL(
        "fastmail", "Fastmail",
        ServerPair("imap.fastmail.com", 993, "smtp.fastmail.com", 465),
        "Fastmail needs an app password, not your normal password. Make one at " +
            "https://www.fastmail.help/hc/en-us/articles/360058752854.",
    ),

    CUSTOM("custom", "Custom", null, "Check the password. Some providers need an app password."),
}

/** The settings rows this plugin adds. Keys are prefixed with the plugin id. */
object EmailSettings {
    const val PLUGIN_ID = "email.imap"

    val PRESET = SettingDef.Choice(
        "email.imap.preset", "Provider",
        "Fills in the mail servers for a common provider. Choose Custom to type your own server rows.",
        SettingSection.PLUGINS, default = EmailPreset.GMAIL.id,
        options = EmailPreset.entries.map { SettingDef.Choice.Option(it.id, it.label) },
    )

    val ADDRESS = SettingDef.Text(
        "email.imap.address", "Email address",
        "The address of the mailbox, for example you@example.com. Empty means nothing is connected.",
        SettingSection.PLUGINS, default = "", maxLength = 254,
    )

    val PASSWORD = SettingDef.Secret(
        "email.imap.password", "App password",
        "An app password for this account, not your normal password. Stored encrypted and never shown back.",
        SettingSection.PLUGINS,
    )

    // No per-row visibility exists in SettingDef, so these rows are always listed. They are read only
    // when the provider is Custom.
    val CUSTOM_IMAP_HOST = SettingDef.Text(
        "email.imap.custom_imap_host", "Custom IMAP server",
        "Only for Custom. The IMAP server name, for example imap.example.com. Connects over TLS on the port below.",
        SettingSection.PLUGINS, default = "", maxLength = 253,
    )

    val CUSTOM_IMAP_PORT = SettingDef.IntValue(
        "email.imap.custom_imap_port", "Custom IMAP port",
        "Only for Custom. The TLS port for IMAP, usually 993.",
        SettingSection.PLUGINS, default = 993, min = 1, max = 65535,
    )

    val CUSTOM_SMTP_HOST = SettingDef.Text(
        "email.imap.custom_smtp_host", "Custom SMTP server",
        "Only for Custom. The SMTP server name, for example smtp.example.com.",
        SettingSection.PLUGINS, default = "", maxLength = 253,
    )

    val CUSTOM_SMTP_PORT = SettingDef.IntValue(
        "email.imap.custom_smtp_port", "Custom SMTP port",
        "Only for Custom. 465 uses TLS from the start. Any other port must offer STARTTLS, or sending fails.",
        SettingSection.PLUGINS, default = 465, min = 1, max = 65535,
    )

    val all: List<SettingDef<*>> = listOf(
        PRESET, ADDRESS, PASSWORD, CUSTOM_IMAP_HOST, CUSTOM_IMAP_PORT, CUSTOM_SMTP_HOST, CUSTOM_SMTP_PORT,
    )
}

/**
 * Where the mailbox password comes from. Every keyed integration reads its credential through one
 * of these, so a managed credential can later be supplied here without touching the tool code.
 * Today the only source is the user's own app password.
 */
fun interface EmailCredentialSource {
    /** The password to send, or null when none is set. */
    fun password(): String?
}

/** The app password the user pasted into Settings. */
class UserPasswordSource(private val reader: SettingsReader) : EmailCredentialSource {
    override fun password(): String? = reader[EmailSettings.PASSWORD].trim().takeIf { it.isNotEmpty() }
}

/**
 * Everything one call needs: the mailbox, the password and the server pair. [label] and
 * [passwordAdvice] are for the sentence shown when the password is refused.
 */
internal class MailAccount(
    val address: String,
    val password: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val label: String,
    val passwordAdvice: String,
)

private val ADDRESS_SHAPE = Regex("[^@\\s]{1,64}@[^@\\s]{1,253}")
private val HOST_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")

/** The account to use now, read from the live settings, or null when it is not set up. */
internal fun mailAccount(reader: SettingsReader, credentials: EmailCredentialSource): MailAccount? {
    val address = reader[EmailSettings.ADDRESS].trim()
    if (!ADDRESS_SHAPE.matches(address)) return null
    val password = credentials.password() ?: return null
    val preset = EmailPreset.entries.firstOrNull { it.id == reader[EmailSettings.PRESET] } ?: EmailPreset.CUSTOM
    val servers = preset.servers ?: ServerPair(
        imapHost = reader[EmailSettings.CUSTOM_IMAP_HOST].trim(),
        imapPort = reader[EmailSettings.CUSTOM_IMAP_PORT],
        smtpHost = reader[EmailSettings.CUSTOM_SMTP_HOST].trim(),
        smtpPort = reader[EmailSettings.CUSTOM_SMTP_PORT],
    )
    if (!HOST_SHAPE.matches(servers.imapHost) || !HOST_SHAPE.matches(servers.smtpHost)) return null
    return MailAccount(
        address = address,
        password = password,
        imapHost = servers.imapHost,
        imapPort = servers.imapPort,
        smtpHost = servers.smtpHost,
        smtpPort = servers.smtpPort,
        label = preset.label,
        passwordAdvice = preset.passwordAdvice,
    )
}

/**
 * Off by default. Offers `email.search`, `email.read` and `email.send` while it is switched on,
 * the app is in Technical mode, and an address, password and server are set.
 *
 * [reader] is the live settings store, read when a run builds its tool list and again when a tool
 * runs, so a change takes effect at the next message.
 */
class EmailPlugin(
    private val reader: SettingsReader,
    private val credentials: EmailCredentialSource = UserPasswordSource(reader),
) : AgentPlugin {
    override val id: String = EmailSettings.PLUGIN_ID
    override val title: String = "Email"
    override val summary: String =
        "Search, read and send email from your own mail account, with an app password. " +
            "Mail goes to your provider's servers."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = EmailSettings.all

    private val built: List<AgentTool> by lazy {
        requireCatalogueAgreement(emailTools { mailAccount(reader, credentials) })
    }

    override fun tools(): List<AgentTool> {
        // Simple mode never offers these: they need an address and a password, which are Technical settings.
        if (reader.mode != SettingsMode.TECHNICAL) return emptyList()
        return if (mailAccount(reader, credentials) == null) emptyList() else built
    }

    /** Every tool this plugin ships, configured or not, for the catalogue check at startup. */
    fun shippedTools(): List<AgentTool> = built
}
