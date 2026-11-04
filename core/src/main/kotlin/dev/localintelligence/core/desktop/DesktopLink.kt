package dev.localintelligence.core.desktop

import java.net.URI

/**
 * One coding agent on the user's desktop, as the desklink bridge lists it (`desktop-bridge/desklink.py`).
 *
 * [target] is the id the bridge expects back on every call. [stateSeq] is the number the phone sends
 * with a key press: if the agent has moved on since the screen was read, the bridge refuses the key.
 */
data class DesktopAgent(
    val target: String,
    val name: String,
    val status: String,
    val stateSeq: Long,
    val cwd: String,
)

/**
 * The keys the phone may send. This list must match `KEY_ALLOWLIST` in `desktop-bridge/desklink.py`:
 * the bridge refuses anything else, so a key not listed here is a key the desktop will never see.
 *
 * WHY no ctrl+c and no typed characters: a key press is a stop or an answer, and an unknown key could
 * interrupt the agent. Longer replies go through [DesktopRules.validMessage] as a message instead.
 */
object DesktopKeys {
    /** Answers to a question or an approval dialog: "1", "2", "3", "y", "n". */
    val APPROVALS: List<String> = listOf("1", "2", "3", "y", "n")

    /** Moving in a dialog and confirming it: "enter", "esc", "up", "down". */
    val NAVIGATION: List<String> = listOf("enter", "esc", "up", "down")

    val ALL: Set<String> = (APPROVALS + NAVIGATION).toSet()
}

/** Limits and rules the app and the bridge share. Keep the numbers in step with `desklink.py`. */
object DesktopRules {
    /** Longest message the bridge accepts, in characters. */
    const val MAX_TEXT_CHARS = 32 * 1024

    /** Messages waiting for the desktop. A full queue refuses new messages instead of dropping old ones. */
    const val MAX_QUEUED = 50

    /**
     * The address Tailscale serve prints, for example `https://desk.tailnet.ts.net`. Only https is accepted:
     * the link carries the user's agent text and replies, so it must not cross a network in clear text.
     * Returns the address with no trailing slash, or null when it is not one.
     */
    fun normalizeServeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 300) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed.trimEnd('/')
    }

    /** A message the bridge will accept: not blank, within the limit, and with no NUL character. */
    fun validMessage(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_TEXT_CHARS && '\u0000' !in text

    fun canQueue(queued: Int): Boolean = queued < MAX_QUEUED

    /**
     * A key press is allowed only while the screen shows live text from the desktop. Cached text is what
     * the user read earlier, so a tap on it could answer a question that is no longer on the desktop.
     */
    fun keysAllowed(online: Boolean, showingLive: Boolean): Boolean = online && showingLive

    fun isBlocked(status: String): Boolean = status == "blocked"
}
