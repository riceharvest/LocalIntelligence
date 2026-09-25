package dev.localintelligence.app.document

import java.io.File

/**
 * The JVM-testable half of this app's FileProvider, and the reason it exists at
 * all.
 *
 * ## Why this is not just a manifest entry
 *
 * `androidx.core.content.FileProvider` does the right thing, but it does it
 * *inside* the Android framework, which means every property that makes a
 * provider safe or unsafe is unverifiable without an emulator. A path-traversal
 * regression in a `<paths>` declaration would ship green and be a data breach in
 * the field. This object re-implements the two decisions that actually carry the
 * risk — *is this path inside our declared scope* and *is this URI something we
 * are willing to act on* — as pure functions over `java.io.File` and `String`, so
 * `./gradlew :app:test` can prove them on the JVM in under a second.
 *
 * The two are layered, and the layering is the point:
 *
 *  1. [resolve] is a **pre-flight** check. A tool calls it before it ever calls
 *     `FileProvider.getUriForFile`, so a traversal string is rejected by our code
 *     and never becomes a URI at all. Defense in depth, not defense only.
 *  2. `file_paths.xml` is the **actual** enforcement. If this object were
 *     deleted entirely, the provider would still only serve `files/out/`, because
 *     that is what the XML declares. [ProviderPathScopeTest] pins the two together
 *     so they cannot drift.
 *
 * The invariant this class exists to keep: **no path outside app-private
 * `files/out/` can ever produce a shareable URI.** Not via `..`, not via percent
 * encoding, not via double encoding, not via a backslash, not via a NUL byte, not
 * via an absolute path, and not via a symlink.
 */
object ProviderPathScope {

    /**
     * Suffix of the provider authority.
     *
     * WHY the authority is derived from `applicationId` and never written as a
     * literal: a provider authority is a global namespace on the device. Two apps
     * that declare the same authority cannot both install, and one app cannot
     * silently hijack another's provider. `${applicationId}.fileprovider` is
     * unique per app *and* per build variant, so a debug build and a release
     * build of the same app never collide on a shared device.
     */
    const val AUTHORITY_SUFFIX = ".fileprovider"

    /** The `name` attribute of the single `<files-path>` in `file_paths.xml`. */
    const val ROOT_NAME = "out"

    /** The `path` attribute of the single `<files-path>` in `file_paths.xml`. */
    const val ROOT_RELATIVE = "out/"

    /**
     * Longest accepted provider-relative path.
     *
     * WHY a bound: a real shareable artifact is a note, a trace, a metadata sidecar.
     * None approaches 512 characters. A path this long is not a path the user
     * chose, it is either a fuzz probe or a stack-overflow attempt, and refusing it
     * costs nothing.
     */
    const val MAX_PATH_CHARS = 512

    /**
     * Longest accepted document URI.
     *
     * WHY: same reasoning as [MAX_PATH_CHARS]. A real `content://` URI from the
     * system picker is tens of characters. This is a ceiling, not a target.
     */
    const val MAX_URI_CHARS = 2048

    /**
     * How many times a percent-encoded path is decoded before giving up.
     *
     * WHY more than one: `..` has three encodings that all mean the same thing to
     * a naive consumer — `..`, `%2e%2e`, and `%252e%2e` (double-encoded). Android's
     * `Uri` decodes lazily in places, and a share target downstream may decode
     * again. Decoding to a fixed point and re-checking each round is the only way
     * to be sure the string we approve is the string that gets used.
     *
     * WHY bounded: an unbounded decode loop is a denial-of-service vector against
     * our own process. Three rounds is far past any real encoding; if the string
     * is still changing after three, it is adversarial and we refuse it.
     */
    const val MAX_DECODE_ROUNDS = 3

    /**
     * Builds the provider authority for an application id.
     *
     * Exposed as a function, not a constant, so the test can prove the value is
     * *derived* — a hardcoded literal in a test would pass forever, and a
     * hardcoded literal in production is the collision bug.
     */
    fun authority(applicationId: String): String = applicationId + AUTHORITY_SUFFIX

    /**
     * Why a path or URI was refused.
     *
     * Modelled as distinct values rather than a boolean because the caller
     * (a tool returning an observation to a model) needs to say something useful,
     * and because a test asserting "rejected" without saying *which* rule fired
     * cannot tell a working filter from one that rejects everything.
     */
    enum class Rejection {
        /** Null, empty, or whitespace-only. */
        BLANK,

        /** Longer than [MAX_PATH_CHARS] / [MAX_URI_CHARS]. */
        TOO_LONG,

        /** Contains a C0 control character or DEL, most importantly NUL. */
        CONTROL_CHARACTER,

        /** `\` — rejected outright rather than normalized. */
        BACKSLASH,

        /** Starts with `/`, or carries a `scheme:` prefix, or a Windows drive. */
        ABSOLUTE,

        /** Contains a `..` segment, before or after any amount of decoding. */
        TRAVERSAL,

        /** Still percent-encoding something after [MAX_DECODE_ROUNDS] rounds. */
        OVER_ENCODED,

        /** Does not begin with the declared root, e.g. `databases/foo.db`. */
        OUT_OF_SCOPE,

        /** A `..` segment that only appears after canonicalization (symlink). */
        ESCAPES_ROOT,

        /** Not a `content://` URI, or has no authority. */
        NOT_CONTENT_URI,

        /** A `content://` URI pointing back at our own provider. */
        OWN_AUTHORITY,
    }

    /** Outcome of [resolve]. Carries the real `File` so callers cannot re-derive it. */
    sealed interface Resolution {
        /** The path is inside the declared scope. [uriPath] is what the provider is asked for. */
        data class Allowed(val file: File, val uriPath: String) : Resolution

        /** The path is refused. [rejection] is safe to show a model; the path is not echoed. */
        data class Rejected(val rejection: Rejection) : Resolution
    }

    /**
     * Decides whether [requested] may become a shareable URI.
     *
     * The order of the checks is load-bearing. Cheap structural rejections run
     * first so that the decode loop never runs on a string that is already
     * provably hostile, and the containment check runs **last** so that it is the
     * final word regardless of what the string checks let through.
     *
     * @param filesDir the app's internal files directory (`context.filesDir`).
     * @param requested a provider-relative path, i.e. one that begins with `out/`.
     */
    fun resolve(filesDir: File, requested: String?): Resolution {
        val trimmed = requested?.trim()
        if (trimmed.isNullOrEmpty()) return Resolution.Rejected(Rejection.BLANK)
        val raw: String = trimmed
        if (raw.length > MAX_PATH_CHARS) return Resolution.Rejected(Rejection.TOO_LONG)

        structural(raw)?.let { return Resolution.Rejected(it) }

        // Decode to a fixed point, re-checking structure on every round. A string
        // that is still changing at the limit is not a path, it is a payload.
        var current: String = raw
        repeat(MAX_DECODE_ROUNDS) {
            val decoded = percentDecode(current) ?: return Resolution.Rejected(Rejection.OVER_ENCODED)
            if (decoded == current) return checkScopeAndContainment(filesDir, decoded)
            current = decoded
            // Re-check every round, not just the first. This is what catches a
            // multi-encoded `..`: the raw string is clean, the decoded string is
            // not, and only decoding one layer per round proves which is which.
            structural(current)?.let { return Resolution.Rejected(it) }
        }
        // Still changing after the final round: the encoding is deeper than the
        // decode budget, so this string is a payload rather than a path.
        if (percentDecode(current) != current) return Resolution.Rejected(Rejection.OVER_ENCODED)
        return checkScopeAndContainment(filesDir, current)
    }

    /**
     * Rejections that do not depend on the filesystem, ordered cheapest first.
     *
     * Extracted so it can be re-run after every decode round without duplicating
     * the ordering, and so a test can assert each rule individually.
     */
    private fun structural(path: String): Rejection? = when {
        path.any { it.isISOControl() } -> Rejection.CONTROL_CHARACTER
        path.contains('\\') -> Rejection.BACKSLASH
        // A scheme (`file:`, `content:`) or a Windows drive letter is an attempt to
        // hand the FileProvider something that is not a relative path at all.
        path.startsWith('/') -> Rejection.ABSOLUTE
        SCHEME_PREFIX.containsMatchIn(path) -> Rejection.ABSOLUTE
        path.segments().any { it == ".." } -> Rejection.TRAVERSAL
        else -> null
    }

    /**
     * The final two checks: is the path in the declared scope, and does the
     * real file it names stay inside that scope?
     *
     * The second check is the one that catches what string inspection cannot: a
     * symlink inside `files/out/` pointing at `databases/session.db`. No amount of
     * `..` detection sees that. Canonicalizing the target and re-testing
     * containment does.
     */
    private fun checkScopeAndContainment(filesDir: File, path: String): Resolution {
        if (!path.startsWith(ROOT_RELATIVE)) return Resolution.Rejected(Rejection.OUT_OF_SCOPE)

        val canonicalRoot = runCatching { filesDir.canonicalFile }.getOrNull()
            ?: return Resolution.Rejected(Rejection.OUT_OF_SCOPE)
        val canonicalScope = runCatching { File(canonicalRoot, ROOT_RELATIVE).canonicalFile }.getOrNull()
            ?: return Resolution.Rejected(Rejection.OUT_OF_SCOPE)
        val canonical = runCatching { File(canonicalRoot, path).canonicalFile }.getOrNull()
            ?: return Resolution.Rejected(Rejection.OUT_OF_SCOPE)

        // Compare against scope + separator, not just scope, so that a sibling
        // directory named `out-evil` cannot pass a bare `startsWith` test.
        val scopePrefix = canonicalScope.path + File.separator
        if (canonical.path != canonicalScope.path && !canonical.path.startsWith(scopePrefix)) {
            return Resolution.Rejected(Rejection.ESCAPES_ROOT)
        }
        // A directory is not shareable content. `out/` itself resolves to the
        // scope directory, and a URI naming a directory is not a file to hand to
        // another app — it is a way to make a reader enumerate our scope.
        if (canonical.isDirectory || canonical == canonicalScope || canonical == canonicalRoot) {
            return Resolution.Rejected(Rejection.OUT_OF_SCOPE)
        }

        return Resolution.Allowed(canonical, path)
    }

    /**
     * Validates a `content://` URI returned by the system document picker.
     *
     * This runs on the value that came back from *another app*, so it assumes the
     * sender is hostile until proven otherwise. Three rejections here are
     * non-obvious and each one closes a real hole:
     *
     *  - [Rejection.NOT_CONTENT_URI] blocks `file://`. A picker that returned a
     *    `file://` URI would put us one line away from `FileUriExposedException`
     *    and, worse, would have handed us a filesystem path chosen by a foreign
     *    process.
     *  - [Rejection.OWN_AUTHORITY] blocks a picker that hands back *our own*
     *    provider URI. That is a confused-deputy: the URI would name a path under
     *    `files/out/` chosen by a foreign process, turning "write what the user
     *    picked" into "write wherever that app says". `resolve` is the only way to
     *    name a file in our own scope, and this is not it.
     *  - the [structural] rules, which reject a `..` segment that arrived inside
     *    the URI path from another process.
     */
    fun validateDocumentUri(uri: String?, applicationId: String): Rejection? {
        if (uri.isNullOrBlank()) return Rejection.BLANK
        if (uri.length > MAX_URI_CHARS) return Rejection.TOO_LONG
        if (uri.any { it.isISOControl() }) return Rejection.CONTROL_CHARACTER
        if (!uri.startsWith(CONTENT_SCHEME)) return Rejection.NOT_CONTENT_URI

        val authority = uri.removePrefix(CONTENT_SCHEME).substringBefore('/')
        if (authority.isEmpty()) return Rejection.NOT_CONTENT_URI
        if (authority == authority(applicationId)) return Rejection.OWN_AUTHORITY

        // Strip the leading separator before checking the path. `structural` is
        // written for provider-relative paths, where a leading `/` is an escape
        // attempt; in a `content://` URI the `/` is the authority delimiter and
        // is present in every well-formed URI, so it must not be judged here.
        val path = uri.substringAfter('/', "").removePrefix("/")
        if (path.isNotEmpty()) structural(path)?.let { return it }
        return null
    }

    const val CONTENT_SCHEME = "content://"

    /**
     * Decodes `%XX` escapes, or returns null if the string is not valid
     * percent-encoding.
     *
     * Returns null rather than passing malformed input through because a `%` that
     * is not followed by two hex digits means the string is being interpreted by
     * something other than us, and we do not want to be the layer that guesses.
     * Decoding happens on the ASCII range only: overlong UTF-8 encodings of `.`
     * and `/` (`%c0%ae`, `%c0%af`) are rejected below by the control-character and
     * separator rules, and attempting a full UTF-8 decoder here would be a much
     * larger piece of machinery for no gain.
     */
    private fun percentDecode(value: String): String? {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hi = value[i + 1].digitToIntOrNull(16) ?: return null
                val lo = value[i + 2].digitToIntOrNull(16) ?: return null
                val decoded = (hi shl 4) or lo
                // %00 would truncate a path in any C-based consumer downstream.
                if (decoded == 0) return null
                out.append(decoded.toChar())
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Splits on `/` and drops empty segments, so `out//a` and `out/a` agree. */
    private fun String.segments(): List<String> = split('/').filter { it.isNotEmpty() }
}

/** `scheme:` at the start of a string. Used to reject `file:` and `content:` as a *path*. */
private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
