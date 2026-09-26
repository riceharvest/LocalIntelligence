package dev.localintelligence.android.tools.web

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
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
//   3. Re-validated on EVERY hop, and resolved before every connection.
//      [EgressGate] resolves the host and refuses any address that is not
//      globally routable — once for the model's URL and again for each
//      redirect target, because a `Location:` header is chosen by the server
//      and is exactly as untrusted as the model's own string. Without this,
//      `localtest.me` (a public name resolving to 127.0.0.1) and
//      `http://[::ffff:127.0.0.1]/` both passed validation and reached the
//      socket. Both were confirmed ACCEPTED against the shipped class before
//      this gate existed.
//
//   4. Bounded in time and in bytes. A phone on mobile data will hang forever
//      on a black-holed connection, and a 50 MB body must never reach the
//      heap of a process that already holds a model in native memory. DNS is
//      bounded too ([EgressGate.RESOLVE_TIMEOUT_MS]), because an unbounded
//      lookup on a captive-portal network would make the gate that runs most
//      often the slowest thing in the request.
//
// RESIDUAL RISK, IN THE FILE RATHER THAN ONLY IN THE DOCS: the DNS gate is
// check-then-connect, so a hostile resolver that answers differently to our
// lookup than to the stack's still wins. Pinning would close it and the
// platform client cannot be pinned — the reasoning is in [EgressGate]'s KDoc
// and in docs/threat-model.md (T2). "Narrowed" is the accurate claim.
//
// The pure functions ([WebUrls], [EgressAddresses], [HtmlText], [BodyReader],
// [WebFetchContent]) hold all of the offline logic and carry no android.*
// imports, which is what makes them testable in principle.
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
// Address classification — the ONE list of non-global ranges
// ----------------------------------------------------------------------------

/**
 * Decides whether an address is globally routable.
 *
 * THIS IS THE ONLY SUCH LIST IN THE FILE, and that is the point of it. The
 * text path ([WebUrls.validate], which resolves nothing) and the resolved path
 * ([EgressGate], which resolves DNS) both call [nonGlobalKind]. Keeping two
 * lists is how a checker ends up with a hole: someone adds `100.64/10` to the
 * DNS-side list and the literal `100.100.100.100` sails through the other one.
 * A range is added here once or not at all.
 *
 * The ranges are the IANA special-purpose registry, plus the IPv6 transition
 * mechanisms that EMBED an IPv4 address. An embedded `127.0.0.1` is still
 * loopback however it is spelled, and `::ffff:127.0.0.1` is the spelling an
 * attacker reaches for first.
 */
internal object EgressAddresses {

    /** Why [bytes] is not globally routable, or null when it is. */
    fun nonGlobalKind(bytes: ByteArray): String? = when (bytes.size) {
        4 -> nonGlobalIpv4(bytes)
        16 -> nonGlobalIpv6(bytes)
        // A length we do not understand is not an address we should connect
        // to. Refusing is the only safe reading of an unknown shape.
        else -> "an address form this tool does not recognise"
    }

    private fun nonGlobalIpv4(b: ByteArray): String? {
        val b0 = b[0].u()
        val b1 = b[1].u()
        val b2 = b[2].u()
        return when {
            b0 == 0 -> "reserved (0/8)"
            b0 == 10 -> "private (10/8)"
            // Not RFC 1918, so it is easy to forget, and it is the range
            // Tailscale and several ISPs hand out. It is a private network
            // and reaching it is exactly the SSRF this tool refuses.
            b0 == 100 && b1 in 64..127 -> "carrier-grade NAT (100.64/10), a private overlay network"
            b0 == 127 -> "loopback"
            b0 == 169 && b1 == 254 -> "link-local, which is where cloud instance metadata lives"
            b0 == 172 && b1 in 16..31 -> "private (172.16/12)"
            b0 == 192 && b1 == 0 && b2 == 0 -> "IETF protocol assignments (192.0.0/24)"
            b0 == 192 && b1 == 0 && b2 == 2 -> "documentation (192.0.2/24)"
            b0 == 192 && b1 == 88 && b2 == 99 -> "6to4 relay anycast (192.88.99/24)"
            b0 == 192 && b1 == 168 -> "private (192.168/16)"
            b0 == 198 && (b1 == 18 || b1 == 19) -> "benchmarking (198.18/15)"
            b0 == 198 && b1 == 51 && b2 == 100 -> "documentation (198.51.100/24)"
            b0 == 203 && b1 == 0 && b2 == 113 -> "documentation (203.0.113/24)"
            b0 >= 224 -> "multicast or reserved"
            else -> null
        }
    }

    private fun nonGlobalIpv6(b: ByteArray): String? {
        // The first 16-bit group, which is how every prefix below is defined.
        val g0 = (b.u(0) shl 8) or b.u(1)
        val b0 = b.u(0)
        val b1 = b.u(1)

        // ORDER MATTERS, and the order is the opposite of what looks natural.
        // `::` and `::1` both satisfy the IPv4-compatible unwrap rule further
        // down, so testing them AFTER the unwrap reports loopback as
        // "reserved (0/8)" — true, and useless to a model deciding what to
        // send next. The two self-describing addresses are named first.
        if (b.all { it == 0.toByte() }) return "unspecified"
        if (g0 == 0 && b.copyOfRange(1, 15).all { it == 0.toByte() } && b[15] == 1.toByte()) {
            return "loopback"
        }

        // An IPv4 address wearing an IPv6 hat. InetAddress collapses the common
        // spellings to 4 bytes on its own, but a literal we parsed by hand does
        // not, and which of the two you get depends on who did the parsing. So
        // the embedded address is unwrapped and judged as IPv4.
        embeddedIpv4(b)?.let { embedded ->
            val kind = nonGlobalIpv4(embedded) ?: return null
            return "$kind, reached through an IPv6 address that embeds it"
        }

        // Prefix tests are on the MASK, not on leading bytes. `fe80::/10` spans
        // fe80:: through febf:ffff:..., so a test for the literal bytes
        // fe:80:00:00 accepts fe80::1 and misses febf::1, which is the same
        // link-local network and just as unreachable from the internet.
        if (b0 == 0xfe && (b1 and 0xc0) == 0x80) return "link-local (fe80::/10)"
        if ((b0 and 0xfe) == 0xfc) return "unique-local (fc00::/7)"
        if (b0 == 0xff) return "multicast (ff00::/8)"
        if (b0 == 0xfe && (b1 and 0xc0) == 0xc0) return "site-local (fec0::/10), deprecated"
        // 100::/64, RFC 6666. 0x0100 is `100` in the first group, and the /64
        // means the next six bytes are zero.
        if (g0 == 0x0100 && b.copyOfRange(2, 8).all { it == 0.toByte() }) {
            return "discard-only (100::/64)"
        }
        // 2001::/32 Teredo and 2001:db8::/32 documentation, both /32 on the
        // first two groups. Teredo tunnels over UDP and embeds an obfuscated
        // IPv4; it is not a place a web page is served from, so it is refused
        // rather than unwrapped.
        if (g0 == 0x2001) {
            val g1 = (b.u(2) shl 8) or b.u(3)
            if (g1 == 0x0000) return "Teredo tunnelling (2001::/32)"
            if (g1 == 0x0db8) return "documentation (2001:db8::/32)"
        }
        return null
    }

    /**
     * The IPv4 address an IPv6 form embeds, or null when it embeds none.
     *
     * Four forms, all of which have been used to carry `127.0.0.1` past a
     * checker that only understood the first:
     *  - `::ffff:a.b.c.d`  IPv4-mapped. The common one.
     *  - `::a.b.c.d`       IPv4-compatible, RFC 4291 §2.5.5.1. Deprecated,
     *                       still parsed, and it is why `::` and `::1` are
     *                       checked BEFORE the unwrap rather than after.
     *  - `64:ff9b::a.b.c.d` NAT64, RFC 6052. The real translation path on an
     *                       IPv6-only mobile network, which is most of them.
     *  - `2002:aabb:ccdd::` 6to4, RFC 3056. The v4 sits in bits 16..48.
     */
    private fun embeddedIpv4(b: ByteArray): ByteArray? {
        // ::ffff:a.b.c.d
        if (b.u(10) == 0xff && b.u(11) == 0xff && b.take(10).all { it == 0.toByte() }) {
            return b.copyOfRange(12, 16)
        }
        // 64:ff9b::a.b.c.d
        if (isIpv6(b, 0x00, 0x64, 0xff, 0x9b) && b.copyOfRange(4, 12).all { it == 0.toByte() }) {
            return b.copyOfRange(12, 16)
        }
        // ::a.b.c.d
        if (b.take(12).all { it == 0.toByte() }) return b.copyOfRange(12, 16)
        // 2002:aabb:ccdd::
        if (b.u(0) == 0x20 && b.u(1) == 0x02) return b.copyOfRange(2, 6)
        return null
    }

    /** True when the first four bytes of [b] are exactly [a0].[a1].[a2].[a3]. */
    private fun isIpv6(b: ByteArray, a0: Int, a1: Int, a2: Int, a3: Int): Boolean =
        b.u(0) == a0 && b.u(1) == a1 && b.u(2) == a2 && b.u(3) == a3

    /**
     * Strict dotted-quad IPv4 parser: four 0-255 decimal octets, nothing else.
     *
     * A leading zero is treated as ambiguous (octal-looking) and refused
     * rather than guessed at. Returns null if [literal] is not exactly a
     * numeric IPv4 address.
     */
    fun parseIpv4Literal(literal: String): ByteArray? {
        val parts = literal.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            if (!part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    /**
     * Strict IPv6 literal parser. Returns 16 bytes, or null if [literal] is not
     * a well-formed address.
     *
     * Written out rather than delegated to `InetAddress.getByName` for two
     * reasons, and both matter:
     *
     *  1. `InetAddress` is a RESOLVER. Calling it inside [WebUrls.validate] would
     *     make a function documented as pure-and-offline start doing network I/O
     *     on a code path that also runs from error formatting.
     *  2. `InetAddress` is lenient about which IPv4-embedded IPv6 forms it
     *     collapses to 4 bytes. A hand parser that always returns 16 keeps the
     *     unwrapping decision in [nonGlobalIpv6], where it can be read and
     *     argued about, instead of spread across platform behaviour.
     *
     * A zone id (`fe80::1%eth0`) is REFUSED rather than stripped. The `%` form
     * only ever names a link-local scope, and stripping it to get a parseable
     * address would mean manufacturing a routable address out of one that is
     * not.
     */
    fun parseIpv6Literal(literal: String): ByteArray? {
        // 45 is the longest legal IPv6 text form: the full 8-group expansion
        // with an embedded IPv4 tail. Anything longer is not an address.
        if (literal.isEmpty() || literal.length > 45) return null
        if ('%' in literal) return null
        if (literal.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' &&
                it != ':' && it != '.' }
        ) {
            return null
        }

        val gap = literal.indexOf("::")
        // At most one `::`. Two of them is not an address.
        if (gap >= 0 && literal.indexOf("::", gap + 1) >= 0) return null
        val head = if (gap >= 0) literal.substring(0, gap) else literal
        val tail = if (gap >= 0) literal.substring(gap + 2) else null

        val values = ArrayList<Int>(8)
        val headGroups = splitGroups(head) ?: return null
        for (g in headGroups) if (!addGroup(g, values, isLast = tail == null && g === headGroups.last())) {
            return null
        }
        val headCount = values.size
        if (tail != null) {
            val tailGroups = splitGroups(tail) ?: return null
            for (g in tailGroups) addGroup(g, values, isLast = g === tailGroups.last()) || return null
        }

        // A trailing dotted quad is two groups, not one, and it may only
        // appear at the very end.
        val groups = values.size
        val zeros = if (gap >= 0) 8 - groups else 0
        if (gap >= 0) {
            // `::` stands for one or more zero groups, never none.
            if (zeros < 1) return null
        } else if (groups != 8) {
            return null
        }

        val out = ByteArray(16)
        var i = 0
        for (v in values.take(headCount)) {
            out[i++] = (v shr 8).toByte()
            out[i++] = v.toByte()
        }
        i = 16 - (values.size - headCount) * 2
        for (v in values.drop(headCount)) {
            out[i++] = (v shr 8).toByte()
            out[i++] = v.toByte()
        }
        return out
    }

    /** `""` -> no groups. `"a::b"` sides -> the groups between colons. */
    private fun splitGroups(text: String): List<String>? {
        if (text.isEmpty()) return emptyList()
        val groups = text.split(':')
        if (groups.any { it.isEmpty() }) return null
        return groups
    }

    /**
     * Appends one group, or two if it is a trailing IPv4 dotted quad.
     *
     * `::ffff:127.0.0.1` is three groups of text and four of bytes, which is
     * the whole reason a hand parser is not a one-liner.
     */
    private fun addGroup(group: String, into: MutableList<Int>, isLast: Boolean): Boolean {
        if ('.' in group) {
            // An embedded IPv4 tail is only legal as the final group.
            if (!isLast) return false
            val v4 = parseIpv4Literal(group) ?: return false
            into.add(((v4[0].u() shl 8) or v4[1].u()))
            into.add(((v4[2].u() shl 8) or v4[3].u()))
            return true
        }
        if (group.isEmpty() || group.length > 4) return false
        val v = group.toIntOrNull(16) ?: return false
        into.add(v)
        return true
    }
}

/** Unsigned view of a byte. `-1` and `255` are the same bit pattern. */
private fun Byte.u(): Int = this.toInt() and 0xff

/** Unsigned view of byte [i] of [b]. */
private fun ByteArray.u(i: Int): Int = this[i].u()

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
        // A single-label host is a LAN name or a typo. It is also the shape
        // an SSRF attempt takes when it is probing the local network, and
        // the model has no legitimate way to know which it is.
        //
        // An IP LITERAL is exempt, and specifically an IPv6 one. A v6 address
        // is written in colons and hex and has no dot anywhere in it, so the
        // dot test below would refuse every public IPv6 literal on the
        // internet — `2606:4700:4700::1111` is Cloudflare's DNS and it would
        // have been called a typo. The exemption is keyed on "parses as an
        // address", not on "contains a dot", so it cannot be used to smuggle a
        // name past this check: `localhost` and `intranet` parse as nothing.
        val looksLikeAddress = EgressAddresses.parseIpv4Literal(
            host.removeSurrounding("[", "]"),
        ) != null || EgressAddresses.parseIpv6Literal(
            host.removeSurrounding("[", "]"),
        ) != null
        if (!looksLikeAddress && !host.contains('.') &&
            !host.equals("localhost", ignoreCase = true)
        ) {
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
     * Returns null for public addresses and for host NAMES. A name is not
     * classified here because doing so would mean resolving it, and this
     * function is deliberately resolver-free: it is called from
     * [WebFetcher.hostOf] while formatting an error, and a function that can
     * block on the network while reporting a timeout is a tool that can hang
     * the agent loop. Names are resolved by [EgressGate] instead, which runs
     * once per connection attempt, on the IO dispatcher, with a timeout.
     *
     * The classification itself is [EgressAddresses.nonGlobalKind] — the same
     * function the resolved path uses — so the literal list and the DNS list
     * cannot drift apart.
     */
    private fun rejectNonPublicAddress(host: String): UrlVerdict.Rejected? {
        if (host.equals("localhost", ignoreCase = true) ||
            host.endsWith(".localhost", ignoreCase = true)
        ) {
            return UrlVerdict.Rejected(
                "\"$host\" is this device. $ALLOWED to public addresses only.",
            )
        }
        val bare = host.removeSurrounding("[", "]")

        // Numeric shorthand: a host written entirely of digits, dots and hex
        // markers is an IP address in SOME notation, not a name. `127.1` is
        // loopback, `0177.0.0.1` has an octal-looking first octet, and
        // `0x7f.0.0.1` is hex. The platform resolver accepts all of these, so
        // refusing only the decimal family would leave the hex family open on
        // whatever platform decides to parse it — and the decision we are
        // making is "is this a name I should resolve at all", which does not
        // want to depend on the platform's leniency.
        //
        // `0x7f.0.0.1` was CONFIRMED ACCEPTED by the previous check, which
        // only looked for all-digit labels and let every hex spelling through.
        val numericNotation = bare.indexOf(':') < 0 && bare.isNotEmpty() &&
            bare.all { part ->
                part == '.' || part in '0'..'9' ||
                    part == 'x' || part == 'X' || part in 'a'..'f' || part in 'A'..'F'
            } &&
            // At least one digit, so a host like `abcdef.com` is not caught
            // by this purely because its letters are all valid hex digits.
            bare.any { it in '0'..'9' }

        val bytes = EgressAddresses.parseIpv4Literal(bare)
            ?: EgressAddresses.parseIpv6Literal(bare)
            ?: if (numericNotation) {
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

        // The parse was pure string work over a literal: no resolver was
        // involved, so this decision cannot be raced against a later
        // resolution. What it CAN be raced against is a NAME, which is why
        // `::ffff:127.0.0.1` and `0:0:0:0:0:ffff:10.0.0.1` land here too —
        // they parse as IPv6 literals, unwrap to the same private IPv4, and
        // are refused. Before the IPv6 parser existed they were not IPs at
        // all to this function and sailed straight through to the socket.
        val kind = EgressAddresses.nonGlobalKind(bytes) ?: return null
        return UrlVerdict.Rejected(
            "\"$host\" is a $kind address. $ALLOWED to public addresses only.",
        )
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

    /**
     * The host of [url] after full text validation, or null if it is refused.
     *
     * This is the per-hop text gate. [WebFetcher] calls it on the FIRST url
     * and again on every redirect target, so a URL that is unacceptable on
     * its face never reaches the resolver and never reaches a socket. The
     * resolver is a separate, more expensive gate ([EgressGate]) and there is
     * no reason to spend it on a `file:` URL.
     *
     * Returning null rather than throwing keeps it usable from the error
     * formatting in [WebFetcher.hostOf], which must never itself fail.
     */
    fun hostOf(url: String): String? = when (val v = validate(url)) {
        is UrlVerdict.Accepted -> v.host
        is UrlVerdict.Rejected -> null
    }
}

/** Outcome of a single redirect hop. */
internal sealed interface RedirectVerdict {
    data class Follow(val url: String, val redirects: Int) : RedirectVerdict
    data class Rejected(val reason: String) : RedirectVerdict
}

/** True for the status codes that mean "go somewhere else". */
internal fun isRedirectStatus(status: Int): Boolean = status in setOf(301, 302, 303, 307, 308)

// ----------------------------------------------------------------------------
// DNS resolution and the egress gate
// ----------------------------------------------------------------------------

/**
 * Outcome of [EgressGate.check].
 *
 * [Pinned] carries the addresses the host resolved to. It is not currently
 * used to open the connection — see the TOCTOU section in [EgressGate] for why
 * that is not possible with the platform client — but the resolution is
 * returned rather than discarded so the decision is auditable and so a future
 * client swap has the data it needs.
 */
internal sealed interface EgressVerdict {
    data class Pinned(val host: String, val addresses: List<ByteArray>) : EgressVerdict
    data class Rejected(val reason: String) : EgressVerdict
}

/** Name resolution, as a seam. Production uses the platform resolver. */
internal fun interface HostResolver {
    /** Addresses for [host]. Throws UnknownHostException when it does not resolve. */
    fun resolve(host: String): List<ByteArray>

    companion object {
        /**
         * The platform resolver.
         *
         * `getAllByName`, not `getByName`: a host with both an A record for a
         * public address and an AAAA record for `::1` is a name we must REFUSE,
         * and only the full set tells us that. Checking one address and
         * connecting to whichever the stack picks is the bypass.
         */
        val SYSTEM = HostResolver { host ->
            InetAddress.getAllByName(host).map { it.address }
        }
    }
}

/**
 * Resolves a host and refuses any address that is not globally routable.
 *
 * WHAT THIS CLOSES. `localtest.me`, `127.0.0.1.nip.io` and every nip.io-style
 * service are ordinary public names with a public-looking TLD that resolve to
 * `127.0.0.1`. Before this existed, [WebUrls.validate] was the only gate and it
 * matched address TEXT, so those names passed it and the socket went to
 * loopback. Confirmed against the shipped class before the change:
 * `http://localtest.me/` returned `Accepted`.
 *
 * EVERY ADDRESS IS CHECKED, not the first. A name that resolves to both a
 * public and a private address is refused, because the connection is not ours
 * to make: `HttpURLConnection` picks from the set and we cannot see which. This
 * is the difference between a rebinding-resistant gate and a trivially
 * defeated one.
 *
 * RUNS ON EVERY HOP. [WebFetcher] calls this immediately before each
 * `opener.open`, including after a redirect, because a redirect target is
 * chosen by the server and is exactly as untrusted as the model-supplied URL.
 *
 * ## The TOCTOU problem, stated plainly
 *
 * Check-then-connect is a race and this class does not close it. Between
 * [HostResolver.resolve] returning and the socket's own resolution, a hostile
 * authoritative server can answer twice: a public address for us, `127.0.0.1`
 * for the connection. The window is milliseconds, which is why this is a
 * meaningful mitigation rather than a fix, and it is why the honest claim is
 * "narrowed" and not "closed".
 *
 * Pinning is what would close it, and it is NOT available here:
 *
 *  - `HttpURLConnection` exposes no hook to supply a pre-resolved address. It
 *    resolves internally, and there is no `setResolvedAddress` on the JDK 21
 *    or the Android API 36 `android.jar` (checked, not assumed).
 *  - The `InetAddressResolverProvider` SPI (JDK 18+) is absent from
 *    `android.jar` entirely, so the platform offers no interception point.
 *  - The usual workaround — connect to the pinned IP literal with a `Host:`
 *    header override — breaks TLS, because the certificate is then verified
 *    against an IP instead of a name, which requires disabling hostname
 *    verification. Turning that off to make SSRF harder is a strictly worse
 *    trade than the one being made here.
 *  - OkHttp's `Dns` interface is the clean answer and is not a dependency this
 *    project has; adding one is out of scope.
 *
 * So: DNS names are checked, every redirect hop is re-checked, and a rebind
 * that answers differently to our lookup than to the stack's still gets
 * through. `docs/threat-model.md` says so in the same words.
 */
internal object EgressGate {

    /** DNS lookup ceiling. A blackholed resolver must not hang the agent loop. */
    const val RESOLVE_TIMEOUT_MS: Int = 5_000

    private const val ALLOWED = "only http:// and https:// URLs can be fetched"

    /**
     * Resolves [host] and judges every address it maps to.
     *
     * A host that is already an address literal is classified WITHOUT
     * resolving: [WebUrls.validate] has already done the text work, and asking
     * the resolver about a literal gains nothing.
     */
    fun check(
        host: String,
        resolver: HostResolver = HostResolver.SYSTEM,
    ): EgressVerdict {
        val bare = host.removeSurrounding("[", "]")

        // A literal needs no lookup. Classify the bytes we already parsed.
        val literal = EgressAddresses.parseIpv4Literal(bare)
            ?: EgressAddresses.parseIpv6Literal(bare)
        if (literal != null) {
            val kind = EgressAddresses.nonGlobalKind(literal)
            return if (kind == null) {
                EgressVerdict.Pinned(host, listOf(literal))
            } else {
                EgressVerdict.Rejected("\"$host\" is a $kind address. $ALLOWED to public addresses only.")
            }
        }

        val addresses = try {
            resolver.resolve(bare)
        } catch (e: UnknownHostException) {
            return EgressVerdict.Rejected(
                "\"$host\" does not resolve to any address, so there is nothing to fetch.",
            )
        } catch (e: SecurityException) {
            return EgressVerdict.Rejected(
                "Android refused the DNS lookup for \"$host\" (${e.javaClass.simpleName}).",
            )
        }

        if (addresses.isEmpty()) {
            return EgressVerdict.Rejected(
                "\"$host\" resolved to no addresses, so there is nothing to fetch.",
            )
        }

        // EVERY address, and the FIRST non-global one is reported by name.
        // A resolver that answers with a mix is refused outright: the stack
        // chooses which one to connect to, and it is not us.
        for (address in addresses) {
            val kind = EgressAddresses.nonGlobalKind(address)
            if (kind != null) {
                return EgressVerdict.Rejected(
                    "\"$host\" resolves to a $kind address (${describe(address)}). " +
                        "$ALLOWED to public addresses only — a public name that points " +
                        "inward is refused as well as a private address typed directly.",
                )
            }
        }
        return EgressVerdict.Pinned(host, addresses)
    }

    /** Printable form of an address, for the model-facing rejection text. */
    fun describe(address: ByteArray): String = try {
        InetAddress.getByAddress(address).hostAddress
    } catch (e: IllegalArgumentException) {
        "an unrecognised address"
    }

    /**
     * [check] with the lookup itself put under a timeout.
     *
     * `InetAddress.getAllByName` on a captive-portal Wi-Fi network or a
     * blackholed resolver blocks for the platform's own retry schedule, which
     * on Android is tens of seconds. The tool already bounds its socket time
     * at [CONNECT_TIMEOUT_MS]; leaving the lookup unbounded would mean the
     * call that is supposed to be fast enough to be re-checked on every hop
     * is the slowest thing in the request. The timeout is enforced by
     * abandoning the future, not by interrupting the thread, because a
     * half-cancelled resolver call can return a partial set.
     */
    fun checkBounded(host: String): EgressVerdict = try {
        val future = LOOKUP_POOL.submit<EgressVerdict> { check(host) }
        try {
            future.get(RESOLVE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            EgressVerdict.Rejected(
                "The DNS lookup for \"$host\" did not answer within " +
                    "${RESOLVE_TIMEOUT_MS / 1000}s, so nothing was fetched.",
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            EgressVerdict.Rejected("The DNS lookup for \"$host\" was interrupted.")
        } catch (e: java.util.concurrent.ExecutionException) {
            // check() handles UnknownHostException and SecurityException
            // itself, so anything arriving here is a resolver bug. Refusing
            // is the correct reading: an unexplained failure is not consent.
            EgressVerdict.Rejected(
                "The DNS lookup for \"$host\" failed unexpectedly " +
                    "(${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}), " +
                    "so nothing was fetched.",
            )
        }
    } catch (e: RuntimeException) {
        EgressVerdict.Rejected("The DNS lookup for \"$host\" could not be started.")
    }

    /**
     * One daemon thread for every lookup the app ever makes.
     *
     * Daemon, so a stuck resolver cannot keep the process alive on shutdown;
     * single-threaded, because a burst of concurrent fetches must not spawn a
     * thread per lookup. A timed-out task that is genuinely stuck stays stuck
     * on this thread — that is the deliberate trade for a tool that is allowed
     * a handful of fetches per session, and it is why the timeout abandons the
     * future instead of trying to kill it.
     */
    private val LOOKUP_POOL = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "web-fetch-dns").apply { isDaemon = true }
    }
}

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
            //
            // THREE GATES RUN PER HOP, IN THIS ORDER, and the order is the
            // design. `current` is re-validated as TEXT first (cheap, offline,
            // refuses a bad scheme or a private LITERAL), then the host is
            // RESOLVED and every address judged, and only then is a socket
            // opened. Putting the DNS gate on this line rather than once
            // before the loop is the entire point: a redirect target is
            // chosen by the server, so a public page that answers
            // `Location: http://localtest.me/` is exactly as dangerous as a
            // model-supplied one, and a gate that ran only on the first URL
            // would walk straight into it.
            while (true) {
                response?.close()
                response = null

                if (context.signal.isCancelled()) return cancelled()

                // Gate 1+2, on every hop including the first and every
                // redirect. A rejection here never reaches the network.
                //
                // The text gate runs first and, on a refusal, the resolver is
                // never asked: resolving a host we have already decided not to
                // fetch is both wasted latency and a needless DNS leak to a
                // name the model chose.
                val hopHost = WebUrls.hostOf(current)
                    ?: return invalid(
                        "Refusing to fetch $current: it did not pass URL validation.",
                        "url rejected before connecting: $current",
                    )
                when (val gate = EgressGate.checkBounded(hopHost)) {
                    is EgressVerdict.Rejected -> return invalid(gate.reason, gate.reason)
                    is EgressVerdict.Pinned -> Unit
                }

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

    override val definition: ToolDefinition = ToolDefinition(
        name = "web.fetch",
        description = "Fetches a web page over http or https and returns its readable text, " +
            "with any HTML markup stripped out. There is no format argument: the result is " +
            "always plain text.",
        category = "web",
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
        risk = ToolRisk.NETWORK_EGRESS,
        // Stated rather than inherited: this is the one tool in the system whose
        // observations are written by a party the user did not choose, and the
        // default is NETWORK precisely so that being explicit here is a
        // deliberate act.
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf(
            "web", "fetch", "url", "internet", "page", "website", "read online", "http",
        ),
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
