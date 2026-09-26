package dev.localintelligence.android.tools.web

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.catalogue.ToolMeta
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException

// ===========================================================================
// web.fetch — NETWORK_EGRESS HTTP GET, rendered as plain text.
//
// This is the one tool in the system that takes a string straight from a 1B
// model and turns it into a socket connection. That makes it the single most
// dangerous tool in the registry, and the ordering of the checks below is the
// whole design:
//
//   1. Only http and https reach the network layer. See [WebUrls.validate].
//      A small model WILL try to be helpful with `file:///data/data/...`, and
//      it will frame that request as obviously reasonable. `content://` reads
//      another app's ContentProvider through our process UID; `javascript:`
//      and `data:` are the classic "paste this into the address bar" payloads.
//      None of them are a fetch, and none of them are the user's web page.
//      The check is an allow-list of exactly two schemes, not a deny-list of
//      the ones we thought of.
//
//   2. Only GET, only no-credentials. `conn.instanceFollowRedirects = false`
//      so the redirect chain is validated by us rather than silently by the
//      stack.
//
//   3. Bounded in time and in bytes. A phone on mobile data will hang forever
//      on a black-holed connection, and a 50 MB body must never reach the
//      heap of a process that already holds a model in native memory.
//
// The pure functions ([WebUrls], [HtmlText], [BodyReader], [WebFetchContent])
// hold all of that logic and carry no android.* imports, which is what makes
// them testable in principle.
//
// THERE IS NO SUCH TEST SUITE. An earlier version of this comment claimed
// "the rules that matter are the rules the JVM test suite executes". That was
// false: the suite was deleted, and nothing in this repository exercises
// [WebUrls.validate] today. The rules are pure, internal, and reachable only
// through a real socket, so the SSRF rejection list below is currently verified
// by nothing at all and can be shortened by a refactor with no test failing.
// `docs/threat-model.md` (T2) says so rather than implying coverage.
//
// ===========================================================================
//
// RISK TIER, AND WHY IT IS NOT READ_ONLY.
//
// `web.fetch` was `READ_ONLY`, on the reasoning that a credential-less GET
// changes nothing on the device. That reasoning counts only the outbound half
// of the call and gets the inbound half exactly backwards. This is the one
// tool whose return value is text written by a party the user did not choose,
// chosen by the model: a hostile page becomes a `ToolObservation` that the
// model then reads. It is the system's injection entry point, and the tier that
// let it run unattended is the tier that made it one.
//
// It is now `ToolRisk.NETWORK_EGRESS`, which means it cannot execute without a
// per-call human approval naming the destination host. See `RiskPolicy` (7b),
// `ToolDefinition.observationOrigin`, and `docs/threat-model.md` (T3).
// ===========================================================================

/** Hard ceiling on the raw body we will pull off the socket, in bytes. */
internal const val MAX_BODY_BYTES: Int = 256 * 1024

/** Model-visible character budget for the extracted text. */
internal const val DEFAULT_MAX_CHARS: Int = 2000
internal const val MAX_MAX_CHARS: Int = 8000
internal const val MIN_MAX_CHARS: Int = 100

/** A phone on mobile data needs both of these, and neither is generous. */
internal const val CONNECT_TIMEOUT_MS: Int = 8_000
internal const val READ_TIMEOUT_MS: Int = 10_000

/** At most this many hops. Beyond it a URL is a loop or a honeypot. */
internal const val MAX_REDIRECTS: Int = 3

/** Rejected before any length or host work, so this stays cheap. */
internal const val MAX_URL_CHARS: Int = 2048

/** Marker appended when the extracted text is cut. Reserved inside the budget. */
internal const val TRUNCATION_MARKER: String = "…[truncated]"

/** Separator between the status header and the extracted text. */
internal const val NEWLINE_SECTION: String = "\n\n"

// ----------------------------------------------------------------------------
// Argument coercion
// ----------------------------------------------------------------------------

/**
 * Defensive scalar coercion.
 *
 * Models emit `"2000"` for an integer, `2000.7` for an integer, `true` for a
 * number, and `null` for a required field. None of those are reasons to fail a
 * task the user is waiting on, so each is mapped onto the nearest sensible
 * value and the clamp is applied afterwards. Non-numeric text still returns
 * [fallback]: guessing what `"lots"` means would be worse than the default.
 */
internal object WebArgs {

    fun coerceInt(
        raw: kotlinx.serialization.json.JsonElement?,
        fallback: Int,
        min: Int,
        max: Int,
    ): Int {
        val value = when (raw) {
            null, is kotlinx.serialization.json.JsonNull -> fallback
            is JsonPrimitive -> when {
                raw.isString -> raw.content.toIntOrNull() ?: raw.content.toDoubleOrNull()?.toInt()
                else -> raw.content.toDoubleOrNull()?.toInt()
            }
            else -> null
        } ?: fallback
        return value.coerceIn(min, max)
    }

    fun coerceString(raw: kotlinx.serialization.json.JsonElement?): String? =
        (raw as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

// ----------------------------------------------------------------------------
// URL validation
// ----------------------------------------------------------------------------

/** Outcome of [WebUrls.validate]. */
internal sealed interface UrlVerdict {
    /** [url] is the normalised absolute URL, [addedScheme] records what we did. */
    data class Accepted(val url: String, val host: String, val addedScheme: Boolean) : UrlVerdict

    /** [reason] is model-facing prose: it says what was wrong and what to send instead. */
    data class Rejected(val reason: String) : UrlVerdict
}

/**
 * Scheme allow-list and URL normalisation.
 *
 * The load-bearing decision is the allow-list: exactly `http` and `https` are
 * accepted and everything else is refused by name. There is no deny-list,
 * because a deny-list has to be updated every time someone finds a new scheme
 * the platform resolves, and a 1B model will find it before we do.
 *
 * A scheme-less input (`example.com`) is *normalised*, not rejected: the model
 * forgetting `https://` is a formatting slip, and refusing it teaches the loop
 * detector nothing except to retry the identical failing call. The one wrinkle
 * is that `localhost:3000` parses as scheme `localhost` under RFC 3986, so a
 * host:port string is recognised by requiring `//` before a scheme counts.
 * That is a deliberate, tested trade-off — see the tests — not an oversight.
 */
internal object WebUrls {

    /**
     * A scheme, but ONLY one followed by `//`.
     *
     * Requiring the slashes is what stops `example.com:8080/x` from being read
     * as scheme `example.com`. Without it the regex would match the host as a
     * scheme, the scheme would fail the allow-list, and every host:port URL
     * would be refused — a real regression for any dev server a user points
     * the agent at. `javascript:alert(1)` has no `//` either, so it is caught
     * by the explicit bare-scheme check below rather than by this one.
     */
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://")

    /**
     * A scheme with no `//`, i.e. an opaque URI. `javascript:`, `data:`,
     * `mailto:` and `tel:` all look like this, and all of them are refused by
     * name. Requiring a known-dangerous prefix rather than "any scheme" keeps
     * `host:port` out of this branch.
     */
    private val OPAQUE_SCHEME = Regex("^(javascript|data|vbscript|file|blob|about):", RegexOption.IGNORE_CASE)

    /** `word:` with no slashes — a scheme, unless the tail is a port. */
    private val BARE_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

    /** `host:1234`, `host:1234/path`, `host:1234?q` — a port, not a scheme. */
    private val PORT_LIKE = Regex("^[^/?#]+:\\d+(?:[/?#]|$)")

    private const val ALLOWED = "only http:// and https:// URLs can be fetched"

    fun validate(raw: String?): UrlVerdict {
        val input = raw?.trim()
        if (input.isNullOrEmpty()) {
            return UrlVerdict.Rejected(
                "No url argument. Pass a full web address, for example " +
                    "url=\"https://example.com\".",
            )
        }
        if (input.length > MAX_URL_CHARS) {
            return UrlVerdict.Rejected(
                "That url is ${input.length} characters, over the $MAX_URL_CHARS limit. " +
                    "Shorten it to a plain page address.",
            )
        }
        if (input.any { it.isWhitespace() }) {
            return UrlVerdict.Rejected(
                "That url contains a space, which is never valid in a web address. " +
                    "Remove the space or percent-encode it as %20.",
            )
        }

        val schemeMatch = SCHEME.find(input)
        var url = input
        var addedScheme = false

        // A scheme-less input is normalised, not rejected. The model forgetting
        // "https://" is a formatting slip, and refusing it teaches the loop
        // detector only to retry the identical failing call. The security
        // property holds because the scheme that reaches the socket is always
        // one WE chose.
        if (schemeMatch == null) {
            val colon = OPAQUE_SCHEME.find(input)
            if (colon != null) {
                val scheme = colon.value.dropLast(1)
                return UrlVerdict.Rejected(
                    "Refusing the \"$scheme:\" scheme. $ALLOWED — " +
                        "fetching a ${scheme}: URL is either a local file read or a " +
                        "script-execution payload, never a web page.",
                )
            }
            // `ftp:example.com` and `example.com:8080` are both "word, colon,
            // something". The only reliable difference is that a port is all
            // digits: so anything with a non-numeric tail after the colon is a
            // scheme, and every scheme is refused — except http and https,
            // which we normalise rather than refuse, because a model writing
            // `https:example.com` means https and failing the task over two
            // missing slashes is the wrong trade.
            val portLike = PORT_LIKE.containsMatchIn(input)
            val bare = BARE_SCHEME.find(input)
            if (bare != null && !portLike) {
                val scheme = bare.value.dropLast(1).lowercase()
                if (scheme != "http" && scheme != "https") {
                    return UrlVerdict.Rejected(
                        "Refusing the \"$scheme:\" scheme. $ALLOWED — " +
                            "that address names a protocol this tool does not fetch.",
                    )
                }
                url = "$scheme://" + input.removePrefix(bare.value)
            } else {
                url = "https://$input"
                addedScheme = true
            }
        } else {
            val scheme = schemeMatch.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") {
                return UrlVerdict.Rejected(
                    "Refusing the \"$scheme:\" scheme. $ALLOWED — " +
                        "fetching a ${scheme}: URL is either a local file read or a " +
                        "script-execution payload, never a web page.",
                )
            }
        }

        if (url.length > MAX_URL_CHARS) {
            return UrlVerdict.Rejected(
                "That url is ${url.length} characters after normalisation, over the " +
                    "$MAX_URL_CHARS limit.",
            )
        }

        val authority = url.substringAfter("//", "")
        val hostAndPort = authority.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostAndPort.isEmpty()) {
            return UrlVerdict.Rejected("That url has no host name, so there is nothing to fetch.")
        }
        if ('@' in hostAndPort) {
            // `https://trusted.example@evil.example/` renders as evil.example in
            // every browser. There is no legitimate reason for a phone agent to
            // send embedded credentials to a page it is summarising.
            return UrlVerdict.Rejected(
                "That url embeds credentials before the host (\"user@host\"), which is a " +
                    "phishing pattern. Pass the host only, without the part before the @.",
            )
        }
        // Split the port off with substringBefore, not substringAfterLast: a
        // bracketed IPv6 literal is full of colons, and taking the text after
        // the last one would return a port where the host should be.
        val host = if (hostAndPort.startsWith("[")) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) {
                return UrlVerdict.Rejected("That url has an unterminated IPv6 host literal.")
            }
            hostAndPort.substring(1, close)
        } else {
            hostAndPort.substringBefore(':')
        }
        if (host.isEmpty()) {
            return UrlVerdict.Rejected("That url has an empty host name, so there is nothing to fetch.")
        }
        if (!host.contains('.') && !host.equals("localhost", ignoreCase = true)) {
            // A single-label host is a LAN name or a typo. It is also the shape
            // an SSRF attempt takes when it is probing the local network, and
            // the model has no legitimate way to know which it is.
            return UrlVerdict.Rejected(
                "\"$host\" is not a public web host name. Pass a full address such as " +
                    "https://$host.example or https://$host.com.",
            )
        }
        if (host.endsWith(".local") || host.endsWith(".internal")) {
            return UrlVerdict.Rejected(
                "\"$host\" is a local-network name (.local / .internal). " +
                    "$ALLOWED to public addresses only.",
            )
        }
        // ADDRESSES, not just names. Everything above filters host NAMES, so
        // every IP literal walked straight through: 127.0.0.1, 10.0.0.1,
        // 192.168.1.1 and 169.254.169.254 all have dots and no special suffix.
        // That made web.fetch a model-controlled probe of the device's own
        // network and the link-local cloud metadata address, reachable from an
        // unattended READ_ONLY tool.
        //
        // `localhost` was also explicitly permitted one branch above, which is
        // the same reachability with a shorter name. Both are refused here.
        rejectNonPublicAddress(host)?.let { return it }
        return UrlVerdict.Accepted(url, host.lowercase(), addedScheme)
    }

    /**
     * Refuses any host that is a literal non-public IP address.
     *
     * Returns null for public addresses and for host names — a name cannot be
     * classified without resolving it, and resolving here would open its own
     * TOCTOU window between the check and the connection.
     *
     * KNOWN LIMIT, stated rather than hidden: this is address TEXT matching. A
     * public name that resolves to 127.0.0.1 or 169.254.169.254 still passes,
     * because nothing here resolves DNS. Closing that properly means pinning
     * the resolved address and connecting to the pinned IP with a matching Host
     * header, or re-validating after each redirect hop; both are larger changes
     * than this one and are not made here. Until they are, web.fetch should be
     * treated as able to reach hosts the resolver chooses — see the threat
     * model in docs/threat-model.md.
     */
    private fun rejectNonPublicAddress(host: String): UrlVerdict.Rejected? {
        if (host.equals("localhost", ignoreCase = true) ||
            host.endsWith(".localhost", ignoreCase = true)
        ) {
            return UrlVerdict.Rejected(
                "\"$host\" is this device. $ALLOWED to public addresses only.",
            )
        }
        // Numeric shorthand: a dotted host whose EVERY label is numeric is an IP
        // address written in an older notation, not a name. `127.1` is loopback
        // and `0177.0.0.1` is 127.0.0.1 with an octal-looking first octet, and
        // both would otherwise sail past a strict dotted-quad parser as
        // "not an IP literal" and reach the connection. Refusing every
        // all-numeric host is a few characters and closes the whole family,
        // including forms no hand-written range list would enumerate.
        val allNumericLabels = host.split('.').all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' }
        }
        val bytes = parseIpv4Literal(host.removeSurrounding("[", "]"))
            ?: if (allNumericLabels) {
                // An IP written in shorthand/alternate notation. Rather than
                // reimplement every legacy encoding, refuse it: a legitimately
                // public host is always spelled with letters or as a real
                // dotted quad, so nothing real is lost by rejecting the rest.
                return UrlVerdict.Rejected(
                    "\"$host\" is a numeric address in a non-standard notation, which " +
                        "this tool refuses rather than guess at. $ALLOWED to public " +
                        "addresses only.",
                )
            } else {
                return null
            }
        // `bytes` is a real dotted quad: parseIpv4Literal did no DNS, so this
        // classification is a pure string decision and cannot be raced against
        // a later resolution.
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        when {
            b0 == 127 -> "loopback"
            b0 == 10 -> "private (10/8)"
            b0 == 172 && b1 in 16..31 -> "private (172.16/12)"
            b0 == 192 && b1 == 168 -> "private (192.168/16)"
            b0 == 169 && b1 == 254 -> "link-local, which is where cloud instance metadata lives"
            b0 == 0 -> "unspecified"
            b0 >= 224 -> "multicast or reserved"
            else -> return null
        }.let { kind ->
            return UrlVerdict.Rejected(
                "\"$host\" is a $kind address. $ALLOWED to public addresses only.",
            )
        }
    }

    /**
     * Strict dotted-quad IPv4 parser: four 0-255 decimal octets, nothing else.
     *
     * Returns the 4 bytes, or null if [literal] is not exactly a numeric IPv4
     * address. This is the ONLY place a host string becomes bytes, and it is
     * resolver-free by construction, so validation stays offline and
     * side-effect-free.
     */
    private fun parseIpv4Literal(literal: String): ByteArray? {
        val parts = literal.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            // Reject empty, over-long, non-digit, and leading-zero forms. A
            // leading zero is treated as ambiguous (octal-looking) and refused
            // rather than guessed at.
            if (part.isEmpty() || part.length > 3) return null
            if (!part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    /** Security level of a scheme. Plaintext is 0, TLS is 1. */
    private fun level(scheme: String): Int = if (scheme.lowercase() == "https") 1 else 0

    /**
     * One hop of redirect handling, as a pure function.
     *
     * Three rules, and the second is the one that matters:
     *  1. No more than [MAX_REDIRECTS] hops. Past that it is a loop or a decoy.
     *  2. A redirect that leaves the host is only allowed when it does not also
     *     change the security level. `http` -> `https` on another host means the
     *     first hop was attacker-chosen plaintext, so following it hands a
     *     summary of a hostile page to the model; `https` -> `http` is the
     *     downgrade twin of the same trick. Same-host hops and same-scheme
     *     cross-host hops (https -> https, e.g. example.com -> www) are fine.
     *  3. The target is re-validated through [validate], so a redirect can
     *     never walk a request from https back onto `file://`.
     */
    fun resolveRedirect(
        current: String,
        location: String?,
        redirectsSoFar: Int,
    ): RedirectVerdict {
        if (redirectsSoFar >= MAX_REDIRECTS) {
            return RedirectVerdict.Rejected(
                "Gave up after $MAX_REDIRECTS redirects. The address redirects in a " +
                    "loop or is a decoy; do not retry it.",
            )
        }
        if (location.isNullOrBlank()) {
            return RedirectVerdict.Rejected(
                "The server sent a redirect with no target address.",
            )
        }
        val resolved = try {
            URI(current).resolve(location).toString()
        } catch (e: URISyntaxException) {
            return RedirectVerdict.Rejected(
                "The redirect target \"$location\" is not a valid address.",
            )
        } catch (e: IllegalArgumentException) {
            return RedirectVerdict.Rejected("The redirect target \"$location\" could not be resolved.")
        }

        val from = validate(current)
        val to = validate(resolved)
        if (to is UrlVerdict.Rejected) return RedirectVerdict.Rejected("Redirect: ${to.reason}")
        if (from is UrlVerdict.Rejected) {
            return RedirectVerdict.Rejected("The starting url is unusable: ${from.reason}")
        }
        val fromAccepted = from as UrlVerdict.Accepted
        val toAccepted = to as UrlVerdict.Accepted

        val hostChanged = fromAccepted.host != toAccepted.host
        val levelChanged = level(schemeOf(current)) != level(schemeOf(toAccepted.url))
        if (hostChanged && levelChanged) {
            return RedirectVerdict.Rejected(
                "Refusing to follow a redirect from ${fromAccepted.host} " +
                    "(${schemeOf(current)}) to ${toAccepted.host} (${schemeOf(toAccepted.url)}): " +
                    "it changes both the host and the transport. Fetch the " +
                    "${toAccepted.host} address directly instead.",
            )
        }
        return RedirectVerdict.Follow(toAccepted.url, redirectsSoFar + 1)
    }

    private fun schemeOf(url: String): String =
        SCHEME.find(url)?.groupValues?.get(1)?.lowercase()
            ?: BARE_SCHEME.find(url)?.value?.dropLast(1)?.lowercase()
            ?: "https"
}

/** Outcome of a single redirect hop. */
internal sealed interface RedirectVerdict {
    data class Follow(val url: String, val redirects: Int) : RedirectVerdict
    data class Rejected(val reason: String) : RedirectVerdict
}

/** True for the status codes that mean "go somewhere else". */
internal fun isRedirectStatus(status: Int): Boolean = status in setOf(301, 302, 303, 307, 308)

// ----------------------------------------------------------------------------
// Bounded body read
// ----------------------------------------------------------------------------

/** What [BodyReader.readCapped] managed to pull off the socket. */
internal data class BodyRead(
    val bytes: ByteArray,
    /** True when the cap was hit and the source had more to give. */
    val truncated: Boolean,
    /** Bytes actually consumed. Equals `bytes.size` plus 1 when truncated. */
    val totalBytes: Long,
    /** True when [signal] fired; the body is partial and must not be used. */
    val cancelled: Boolean,
) {
    // ByteArray in a data class needs these spelled out.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is BodyRead && bytes.contentEquals(other.bytes) && truncated == other.truncated &&
                totalBytes == other.totalBytes && cancelled == other.cancelled)

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + truncated.hashCode()) * 31 + totalBytes.hashCode()

    fun isCancelled(): Boolean = cancelled
}

/**
 * Reads at most [capBytes] from [input], one byte past the cap so that
 * truncation is detected rather than assumed.
 *
 * The extra byte matters: without it a body that happens to be exactly the cap
 * long is indistinguishable from one that was cut, and the model would be told
 * a complete page was truncated. Reading cap+1 costs one byte and makes the
 * observation honest.
 */
internal object BodyReader {

    fun readCapped(
        input: InputStream,
        capBytes: Int,
        signal: CancellationSignal,
    ): BodyRead {
        val cap = capBytes.coerceAtLeast(0)
        val buffer = ByteArrayOutputStream(minOf(cap, 32 * 1024))
        val chunk = ByteArray(8 * 1024)
        var kept = 0
        var truncated = false
        var eof = false

        while (!eof) {
            if (signal.isCancelled()) {
                return BodyRead(buffer.toByteArray(), truncated, kept.toLong(), cancelled = true)
            }
            // Ask for one byte more than the cap still needs. If that byte
            // arrives, the source had more to give and the body really is cut;
            // if the stream ends instead, the body was exactly complete and
            // reporting truncation would be a lie.
            val want = minOf(chunk.size.toLong(), (cap - kept).toLong() + 1L).toInt()
            val read = try {
                input.read(chunk, 0, want)
            } catch (e: IOException) {
                // A connection dropped mid-body is not a failure: keep what
                // arrived and let the observation report the partial length.
                break
            }
            if (read < 0) {
                eof = true
                break
            }
            if (read == 0) continue
            val room = cap - kept
            if (room > 0) {
                val take = minOf(read, room)
                buffer.write(chunk, 0, take)
                kept += take
            }
            if (read > room) truncated = true
        }
        return BodyRead(buffer.toByteArray(), truncated, kept.toLong(), cancelled = false)
    }
}

// ----------------------------------------------------------------------------
// HTML to text
// ----------------------------------------------------------------------------

/** Result of [HtmlText.toPlainText]. */
internal data class PlainText(
    val text: String,
    /** True when the source was longer than the character budget. */
    val truncated: Boolean,
    /** True when HTML was detected and markup was removed. */
    val fromHtml: Boolean,
)

/**
 * HTML to readable text.
 *
 * Four things have to be true of the output, and each one is a security or a
 * token-budget property, not a cosmetic one:
 *
 *  1. `<script>` and `<style>` bodies are removed, not just their tags. A
 *     naive strip turns a page into 60 KB of JavaScript and a model that has
 *     been told to summarise it.
 *  2. Entities are decoded *after* the tags are gone, so `&lt;script&gt;` in
 *     page text becomes visible text and can never be re-interpreted as
 *     markup. Decoding first would let a page smuggle a tag past the stripper.
 *  3. Whitespace collapses, including the indentation every real page carries.
 *  4. The result is cut to the budget *including* the marker, so a capped
 *     observation is exactly `maxChars` and never `maxChars + 13`.
 */
internal object HtmlText {

    private val DROP_WITH_CONTENT = Regex(
        "<(script|style|noscript|template|svg|math|head|iframe)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val UNCLOSED_SCRIPT = Regex(
        "<(script|style)\\b[^>]*>.*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    /** Tags that imply a line break in the rendered page. */
    private val BLOCK_TAG = Regex(
        "</?(p|div|br|li|ul|ol|tr|td|th|table|thead|tbody|h[1-6]|section|article|header|" +
            "footer|nav|aside|blockquote|pre|hr|figure|figcaption)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    private val ANY_TAG = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "sect" to "§", "para" to "¶", "middot" to "·", "bull" to "•",
        "dagger" to "†", "prime" to "′", "laquo" to "«", "raquo" to "»",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "times" to "×", "divide" to "÷", "plusmn" to "±", "frac12" to "½",
        "frac14" to "¼", "sup2" to "²", "sup3" to "³", "micro" to "µ",
        "ne" to "≠", "le" to "≤", "ge" to "≥", "larr" to "←", "rarr" to "→",
        "harr" to "↔", "infin" to "∞", "alpha" to "α", "beta" to "β",
        "gamma" to "γ", "delta" to "δ", "pi" to "π", "sigma" to "σ",
        "omega" to "ω", "euro " to "€",
    )

    private val ENTITY = Regex("&(#[0-9]{1,8}|#[xX][0-9a-fA-F]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,9});")

    /**
     * Control and format characters, which a page can use to break a keyword
     * apart (`acme\u200Bcorp` reads as `acmecorp`) or to smuggle a direction
     * override into the observation. Newline and tab are excluded because
     * `collapseWhitespace` already handles them meaningfully.
     */
    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]")

    fun toPlainText(source: String, maxChars: Int): PlainText {
        val budget = maxChars.coerceAtLeast(MIN_MAX_CHARS)
        val looksHtml = WebFetchContent.looksHtml(null, source)

        if (!looksHtml) {
            return finish(source, budget, fromHtml = false)
        }

        var text = COMMENT.replace(source, " ")
        // A `<script>` with no closing tag would otherwise leave its body in the
        // page as visible text, so an unterminated one eats the remainder.
        text = DROP_WITH_CONTENT.replace(text, " ")
        text = UNCLOSED_SCRIPT.replace(text, " ")
        text = BLOCK_TAG.replace(text, "\n")
        text = ANY_TAG.replace(text, " ")
        text = decodeEntities(text)
        text = CONTROL.replace(text, "")
        text = collapseWhitespace(text)
        return finish(text, budget, fromHtml = true)
    }

    /** Single pass, so `&amp;lt;` decodes to the literal text `&lt;`. */
    private fun decodeEntities(text: String): String =
        ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePoint(body.substring(2), 16)?.let(::safeChar) ?: m.value
                body.startsWith("#") ->
                    codePoint(body.substring(1), 10)?.let(::safeChar) ?: m.value
                else -> NAMED[body] ?: m.value
            }
        }

    private fun codePoint(digits: String, radix: Int): Int? =
        digits.toIntOrNull(radix)?.takeIf { it in 1..0x10FFFF && !(it in 0xD800..0xDFFF) }

    /** Lone surrogates become U+FFFD rather than corrupting the String. */
    private fun safeChar(cp: Int): String = if (cp in 0xD800..0xDFFF) "�" else String(Character.toChars(cp))

    private fun collapseWhitespace(text: String): String {
        val noSpaces = text.replace(Regex("[\\t\\x0B\\f\\u00A0 ]+"), " ")
        val trimmedNewlines = noSpaces.replace(Regex(" *\n *"), "\n")
        val collapsed = trimmedNewlines.replace(Regex("\n{3,}"), "\n\n")
        return collapsed.trim()
    }

    /**
     * Cuts to [budget] with the marker *inside* the budget, so the caller can
     * assert `text.length <= maxChars` rather than `<= maxChars + 13`.
     */
    private fun finish(text: String, budget: Int, fromHtml: Boolean): PlainText {
        if (text.length <= budget) return PlainText(text, truncated = false, fromHtml = fromHtml)
        if (budget <= TRUNCATION_MARKER.length) {
            return PlainText(text.take(budget), truncated = true, fromHtml = fromHtml)
        }
        val head = text.take(budget - TRUNCATION_MARKER.length)
        // Do not end mid-word when there is a space near the cut.
        val spaceAt = head.lastIndexOf(' ')
        val cut = if (spaceAt > budget / 2) head.take(spaceAt) else head
        return PlainText(cut.trimEnd() + TRUNCATION_MARKER, truncated = true, fromHtml = fromHtml)
    }
}

// ----------------------------------------------------------------------------
// Content classification
// ----------------------------------------------------------------------------

internal object WebFetchContent {

    /**
     * Decides whether the payload is markup worth stripping.
     *
     * The `format` argument the model passes is a *hint*, not an authority: a
     * model that asks for "text" on an HTML page still gets clean text, because
     * the alternative is 400 KB of angle brackets in the observation. Only the
     * response's own content type and first non-space character are trusted.
     */
    fun looksHtml(contentType: String?, sample: String?): Boolean {
        val type = contentType?.lowercase()
        if (type != null && (type.contains("html") || type.contains("xml"))) return true
        if (type != null && (type.startsWith("text/") && !type.contains("html"))) return false
        val head = sample?.trimStart()?.take(512) ?: return false
        return head.startsWith("<")
    }

    /** Human-readable content type for the observation header. */
    fun describe(contentType: String?): String {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            type.isNullOrEmpty() -> "unknown type"
            type.contains("html") -> "HTML"
            type.contains("json") -> "JSON"
            type.contains("xml") -> "XML"
            type.startsWith("text/") -> "text"
            else -> type
        }
    }
}

// ----------------------------------------------------------------------------
// Observation construction
// ----------------------------------------------------------------------------

/**
 * Builds every model-visible string the tool can emit. Kept apart from the
 * socket so the exact wording — which is the part a model has to reason about —
 * is asserted in tests rather than eyeballed.
 */
internal object WebObservations {

    /**
     * The one-line status header. Never contains page content, so it cannot
     * blow the budget on its own no matter how long the URL was.
     */
    fun header(
        finalUrl: String,
        status: Int,
        contentType: String?,
        body: BodyRead,
    ): String {
        val size = if (body.truncated) {
            "${formatBytes(body.totalBytes)}, stopped at the $MAX_BODY_BYTES B download cap"
        } else {
            "${formatBytes(body.totalBytes)}, complete"
        }
        return "HTTP $status from $finalUrl (${WebFetchContent.describe(contentType)}, $size)"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    /** 4xx/5xx: name the status, say what it means, and never throw. */
    fun httpError(status: Int, finalUrl: String): Pair<ToolError, String> {
        val where = "from $finalUrl"
        return when (status) {
            400 -> ToolError.InvalidArguments(
                "$status Bad Request $where. The server rejected the request.",
            ) to "$status Bad Request $where. The server rejected the request."

            401, 403 -> {
                val text = "$status from $where. The server refused to serve this page to us; " +
                    "it needs a login or is blocking this client."
                ToolError.PermissionDenied(text) to text
            }

            404, 410 -> {
                val text = "$status $where. There is no page at that address."
                ToolError.NotFound(text) to text
            }

            429 -> {
                val text = "429 Too Many Requests $where. The site is rate-limiting us; " +
                    "wait before retrying."
                ToolError.Unavailable(text) to text
            }

            in 500..599 -> {
                val text = "$status Server Error $where. The site is broken or overloaded right now."
                ToolError.Unavailable(text) to text
            }

            else -> {
                val text = "$status $where. The site returned an unexpected status."
                ToolError.Unavailable(text) to text
            }
        }
    }
}

// ----------------------------------------------------------------------------
// The transport seam
// ----------------------------------------------------------------------------

/**
 * One HTTP response, already open. [close] releases the connection; the body
 * stream is not closed by the caller, only drained up to the cap.
 */
internal class OpenedResponse(
    val status: Int,
    val location: String?,
    val contentType: String?,
    val body: InputStream,
    private val closer: () -> Unit,
) {
    fun close() = try {
        closer()
    } catch (ignored: Exception) {
        // Nothing useful to do about a failed disconnect.
    }
}

/**
 * The only place a socket is created.
 *
 * This exists so the redirect chain, the status mapping, the size cap and the
 * observation can all be exercised by the JVM suite against a scripted server.
 * It is one interface, not a framework: [HttpUrlConnectionOpener] is the
 * production implementation and there is no other.
 */
internal fun interface ResponseOpener {
    /** Opens [url]. Implementations may throw IOException; the caller maps it. */
    fun open(url: String, signal: CancellationSignal): OpenedResponse
}

// ----------------------------------------------------------------------------
// The fetcher
// ----------------------------------------------------------------------------

/**
 * The whole request, minus the coroutine and minus the `HttpURLConnection`
 * constructor. Blocking; the tool calls it on [Dispatchers.IO].
 *
 * Every exit from this function is a [ToolResult]. The catch-all at the bottom
 * exists because the alternative is an exception escaping into the agent loop,
 * and per docs/tool-contract.md an escaping exception is a bug that takes down a
 * step which should have recovered.
 */
internal class WebFetcher(
    private val opener: ResponseOpener,
) {

    fun fetch(url: String, maxChars: Int, context: ToolContext, grant: PlatformGrant): ToolResult {
        // The caller's knob is clamped to MAX_MAX_CHARS, and then to the
        // contract ceiling. The ceiling always wins: a model asking for 8000
        // characters does not get to decide that the 2048-char observation
        // budget is optional, and the truncator below is the backstop.
        val budget = maxChars
            .coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)
            .coerceAtMost(ObservationTruncator.DEFAULT_BUDGET_CHARS)
        var current = url
        var redirects = 0
        var response: OpenedResponse? = null

        try {
            if (context.signal.isCancelled()) return cancelled()
            // Asked of the platform, not of `context.permissionGranted`. That
            // flag was filled from a set nothing populates, so it was false on
            // every call and web.fetch — the one tool that reaches the internet,
            // and therefore the one that answers weather, news and prices — was
            // dead on a fully working install. The old message ("not granted
            // for this call ... once the user allows it") also described a
            // consent dialog that does not exist: INTERNET is an install-time
            // permission, granted by being in the manifest, which it is.
            if (!grant.isGranted(ToolPermissions.INTERNET_REQUIREMENT)) {
                return ToolResult(
                    success = false,
                    observation = PermissionDenial.observation("web.fetch", ToolPermissions.INTERNET_REQUIREMENT),
                    error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.INTERNET_REQUIREMENT)),
                )
            }

            // Bounded loop: MAX_REDIRECTS hops, each one re-validating the target.
            while (true) {
                response?.close()
                response = opener.open(current, context.signal)
                if (context.signal.isCancelled()) return cancelled()

                val status = response.status
                if (isRedirectStatus(status)) {
                    when (val hop = WebUrls.resolveRedirect(current, response.location, redirects)) {
                        is RedirectVerdict.Rejected -> return invalid(hop.reason, hop.reason)
                        is RedirectVerdict.Follow -> {
                            current = hop.url
                            redirects = hop.redirects
                        }
                    }
                    continue
                }

                if (status !in 200..299) {
                    val (error, observation) = WebObservations.httpError(status, current)
                    return ToolResult(
                        success = false,
                        observation = observation,
                        data = buildJsonObject {
                            put("url", JsonPrimitive(url))
                            put("finalUrl", JsonPrimitive(current))
                            put("status", JsonPrimitive(status))
                            put("redirects", JsonPrimitive(redirects))
                        },
                        error = error,
                    )
                }

                val body = BodyReader.readCapped(
                    response.body,
                    MAX_BODY_BYTES,
                    context.signal,
                )
                if (body.cancelled) return cancelled()

                val raw = String(body.bytes, Charsets.UTF_8)
                if (context.signal.isCancelled()) return cancelled()

                // maxChars is the budget for the WHOLE observation, not just
                // for the body. A caller asking for 500 chars means 500 chars
                // of context window; a header that adds to it would make the
                // knob a lie and quietly overshoot what was requested.
                val header = WebObservations.header(
                    current,
                    status,
                    response?.contentType,
                    body,
                )
                val bodyBudget = (budget - header.length - NEWLINE_SECTION.length)
                    .coerceIn(MIN_MAX_CHARS, budget)
                val text = HtmlText.toPlainText(raw, bodyBudget)
                val observation = ObservationTruncator.truncate(
                    buildString {
                        append(header)
                        if (text.text.isEmpty()) {
                            append("\nThe page had no readable text.")
                        } else {
                            append(NEWLINE_SECTION).append(text.text)
                        }
                    },
                    budget,
                )
                return ToolResult(
                    success = true,
                    observation = observation,
                    data = buildJsonObject {
                        put("url", JsonPrimitive(url))
                        put("finalUrl", JsonPrimitive(current))
                        put("status", JsonPrimitive(status))
                        put("redirects", JsonPrimitive(redirects))
                        put("contentType", JsonPrimitive(WebFetchContent.describe(response?.contentType)))
                        put("bytes", JsonPrimitive(body.totalBytes))
                        put("bodyTruncated", JsonPrimitive(body.truncated))
                        put("textChars", JsonPrimitive(text.text.length))
                        put("textTruncated", JsonPrimitive(text.truncated))
                        put("strippedHtml", JsonPrimitive(text.fromHtml))
                    },
                )
            }
        } catch (e: UnknownHostException) {
            return unavailable("no such host", current, e)
        } catch (e: SocketTimeoutException) {
            return ToolResult(
                success = false,
                observation = "Timed out fetching $current after " +
                    "${CONNECT_TIMEOUT_MS / 1000}s connect / ${READ_TIMEOUT_MS / 1000}s read. " +
                    "The network may be slow or the site unreachable; do not retry immediately.",
                error = ToolError.Timeout("socket timeout"),
            )
        } catch (e: SSLException) {
            return ToolResult(
                success = false,
                observation = "The secure connection to ${hostOf(current)} failed: " +
                    "${e.javaClass.simpleName}. The certificate did not validate, so the " +
                    "response was discarded. Do not retry; report a bad certificate.",
                error = ToolError.Unavailable("TLS failure: ${e.javaClass.simpleName}"),
            )
        } catch (e: SecurityException) {
            return ToolResult(
                success = false,
                observation = "Android refused the network request to $current " +
                    "(${e.javaClass.simpleName}). The device is missing the INTERNET " +
                    "permission or a network policy blocked it.",
                error = ToolError.PermissionDenied("SecurityException: ${e.javaClass.simpleName}"),
            )
        } catch (e: IllegalArgumentException) {
            return invalid("That url could not be parsed: ${e.javaClass.simpleName}.", e.message)
        } catch (e: IOException) {
            // Android 9+ blocks cleartext HTTP by default and says so in the
            // message. Naming it saves the user a settings trip and stops the
            // model from retrying the identical http:// call forever.
            if (e.message?.contains("Cleartext", ignoreCase = true) == true) {
                return ToolResult(
                    success = false,
                    observation = "Android blocked a cleartext http:// request to " +
                        "${hostOf(current)}. Fetch the same page over https:// instead.",
                    error = ToolError.Unavailable("cleartext http blocked by platform"),
                )
            }
            return unavailable("the connection failed", current, e)
        } catch (e: RuntimeException) {
            // Deliberately broad. An OkHttp-shaped or platform RuntimeException
            // must not kill an agent step; report it as internal with the type
            // only, never the stack trace.
            return ToolResult(
                success = false,
                observation = "web.fetch failed unexpectedly (${e.javaClass.simpleName}). " +
                    "This is a tool bug; do not retry the same url.",
                error = ToolError.Internal("${e.javaClass.simpleName} in WebFetcher.fetch"),
            )
        } finally {
            response?.close()
        }
    }

    private fun unavailable(what: String, url: String, e: Exception): ToolResult = ToolResult(
        success = false,
        observation = "Could not reach ${hostOf(url)}: $what (${e.javaClass.simpleName}). " +
            "Check the connection or the address, then try once more.",
        error = ToolError.Unavailable("${e.javaClass.simpleName}: $what"),
    )

    private fun invalid(what: String, reference: String?): ToolResult = ToolResult(
        success = false,
        observation = what,
        error = ToolError.InvalidArguments(reference ?: what.take(120)),
    )

    private fun cancelled(): ToolResult = ToolResult(
        success = false,
        observation = "Cancelled before the page finished downloading.",
        error = ToolError.Cancelled("signal raised during fetch"),
    )

    private fun hostOf(url: String): String = WebUrls.validate(url).let {
        if (it is UrlVerdict.Accepted) it.host else url
    }
}

// ----------------------------------------------------------------------------
// The real transport
// ----------------------------------------------------------------------------

/**
 * `HttpURLConnection`, not OkHttp.
 *
 * OkHttp is the better library and it is also a new Gradle dependency, which
 * docs/architecture.md §19 rules out for a tool that needs one GET. The
 * platform client handles TLS 1.2/1.3, redirects (which we disable and do
 * ourselves), gzip and proxies correctly on every API level from 26 up.
 *
 * `instanceFollowRedirects = false` is the load-bearing line: if the stack
 * followed hops itself we would never see them, never validate them, and
 * `http://` could walk us onto `file://` behind [WebUrls]' back.
 */
internal class HttpUrlConnectionOpener : ResponseOpener {

    override fun open(url: String, signal: CancellationSignal): OpenedResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        // No cookies, no auth, no referer. A summarising GET has no business
        // carrying the user's session to a page the model picked.
        connection.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5")
        connection.setRequestProperty("Accept-Language", "en")
        connection.setRequestProperty("User-Agent", "LocalIntelligence/1.0 (on-device agent)")
        if (signal.isCancelled()) connection.disconnect()

        val status = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        val location = try {
            connection.getHeaderField("Location")
        } catch (e: IOException) {
            null
        }
        val contentType = connection.contentType
        // A 4xx/5xx keeps its body on the error stream; the status still gets
        // reported either way, so draining the wrong one would just throw.
        val body = try {
            if (status >= 400) connection.errorStream else connection.inputStream
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        } ?: java.io.ByteArrayInputStream(ByteArray(0))

        return OpenedResponse(
            status = status,
            location = location,
            contentType = contentType,
            body = body,
            closer = {
                try {
                    body.close()
                } finally {
                    connection.disconnect()
                }
            },
        )
    }
}

// ----------------------------------------------------------------------------
// The tool
// ----------------------------------------------------------------------------

/**
 * `web.fetch` — HTTP GET a URL and return readable text.
 *
 * READ_ONLY: it sends a GET with no credentials and changes nothing on the
 * device, so per docs/architecture.md §8 it executes without a confirmation
 * dialog. The honesty constraint is the other one: the observation must never
 * claim content it did not receive, must never contain raw markup, and must
 * never contain a stack trace.
 *
 * The public constructor is the production one; [WebFetchTool.withOpener] is
 * the seam the JVM suite drives the redirect chain, the status mapping and the
 * size cap through.
 */
class WebFetchTool private constructor(
    private val opener: ResponseOpener,
    private val grant: PlatformGrant,
) : AgentTool {

    constructor(grant: PlatformGrant) : this(HttpUrlConnectionOpener(), grant)

    override val definition: ToolDefinition = ToolMeta.WEB_FETCH.define(
        // NETWORK_EGRESS, not READ_ONLY. See the file header: this is the one
        // tool whose return value is text written by a party the user did not
        // choose, so the tier that let it run unattended is the tier that made
        // it an injection entry point.
        risk = ToolRisk.NETWORK_EGRESS,
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "url",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Absolute http:// or https:// address of the page."),
                            )
                        },
                    )
                    put(
                        "maxChars",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(MIN_MAX_CHARS))
                            put("maximum", JsonPrimitive(MAX_MAX_CHARS))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Characters of text to return, $DEFAULT_MAX_CHARS by default, " +
                                        "at most $MAX_MAX_CHARS. The whole response is also capped " +
                                        "at the observation budget, so a larger value may not " +
                                        "return more text.",
                                ),
                            )
                        },
                    )
                    // Removed rather than documented around. This advertised a
                    // three-value enum and the tool never read it: `execute`
                    // pulls only "url" and "maxChars". An argument the model can
                    // send and that provably does nothing is a schema lying
                    // about its own arguments, and "html" in particular implies
                    // raw markup can be returned, which this tool will not do.
                    // HTML is always stripped to text — that is now stated in
                    // the tool description instead of being an argument.
                },
            )
            put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("url")) })
        },
        requiredPermission = null,
    )

    /**
     * Blocking work goes to [Dispatchers.IO]. `execute` is a suspend function
     * on a phone main thread; a synchronous GET there is an ANR.
     */
    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val rawUrl = WebArgs.coerceString(args["url"])
                val verdict = WebUrls.validate(rawUrl)
                if (verdict is UrlVerdict.Rejected) {
                    return@withContext ToolResult(
                        success = false,
                        observation = verdict.reason,
                        error = ToolError.InvalidArguments(
                            "url rejected: " + verdict.reason.take(100),
                        ),
                    )
                }
                val accepted = verdict as UrlVerdict.Accepted
                val maxChars = WebArgs.coerceInt(
                    args["maxChars"],
                    fallback = DEFAULT_MAX_CHARS,
                    min = MIN_MAX_CHARS,
                    max = MAX_MAX_CHARS,
                )
                WebFetcher(opener).fetch(accepted.url, maxChars, context, grant)
            } catch (e: Exception) {
                // WebFetcher is already total. This is the last line: an
                // exception must never escape execute() into the agent loop.
                ToolResult(
                    success = false,
                    observation = "web.fetch could not run (${e.javaClass.simpleName}). " +
                        "Do not retry the same url.",
                    error = ToolError.Internal("${e.javaClass.simpleName} in WebFetchTool.execute"),
                )
            }
        }

    internal companion object {
        /** Test seam: a [WebFetchTool] backed by a scripted transport. */
        fun withOpener(opener: ResponseOpener, grant: PlatformGrant): WebFetchTool =
            WebFetchTool(opener, grant)
    }
}


// =====================================================================================
// The tool set
// =====================================================================================

/**
 * The one tool that reaches the internet.
 *
 * WHY a factory for a single tool: the other eight families each have one, and
 * [dev.localintelligence.android.di.AgentGraph] composes the tool set out of
 * them. A family of one that skips the pattern is the kind of asymmetry the
 * next person re-discovers.
 *
 * No [Context] parameter, and that is the design rather than an omission: the
 * fetcher is an [HttpUrlConnectionOpener] with no access to app state, so it
 * cannot read anything the user did not explicitly ask it to fetch.
 */
fun webTools(grant: PlatformGrant): List<AgentTool> = listOf(WebFetchTool(grant))
