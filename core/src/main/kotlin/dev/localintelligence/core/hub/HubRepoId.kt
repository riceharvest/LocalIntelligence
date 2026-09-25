package dev.localintelligence.core.hub

/**
 * A validated HuggingFace repository identifier, `owner/name`.
 *
 * ## Why this is a type and not a String
 *
 * A repo id ends up in three places that each fail differently: an HTTP URL
 * path, a `File` name under the app's model directory, and an error message a
 * small local LLM will read. A raw string from a text field can be
 * `../../databases/app.db` or `owner/name/../../../etc` and every one of those
 * sinks mishandles it. Parsing once, here, means the rest of the hub only ever
 * sees a value that cannot escape either the URL path or the file tree.
 *
 * The [pathInRepo] field is the second half of the same problem: HF repos nest
 * files in folders (`q4_k_m/model-00001-of-00002.gguf`), and that string also
 * becomes a local path. It is validated segment by segment for the same reason.
 *
 * Validation is deliberately strict rather than lenient-and-escaped. HF repo
 * ids are `owner/name` with a small character set; anything outside it is a
 * typo or an attack, and a user staring at "invalid repository id" can retype
 * four characters, whereas a lenient parser produces a download that fails
 * 200 MB in.
 */
data class HubRepoId private constructor(
    val owner: String,
    val name: String,
) {
    /** `owner/name`. Safe as a URL path: both parts are already charset-clean. */
    val id: String get() = "$owner/$name"

    override fun toString(): String = id

    companion object {
        /**
         * HF's own limit on each half of a repo id. Enforced so a pathological
         * input cannot become a multi-megabyte URL before we reject it.
         */
        const val MAX_SEGMENT_LENGTH = 96

        /**
         * Parses `owner/name`, or returns `null` with a short reason.
         *
         * Returns a failure rather than throwing because the input is a user
         * typing into a text field, and the reason is shown to them verbatim.
         *
         * Rejected, and why each one matters:
         * - no slash, or more than one — HF ids are exactly two segments
         * - `.` or `..` as either segment — path traversal
         * - a backslash anywhere — a Windows-style separator smuggled past a
         *   slash-only check
         * - any character outside `[A-Za-z0-9._-]` — a percent-escape, a null
         *   byte, a space, a control char, or a URL fragment/query separator,
         *   any of which would change what the URL actually points at
         * - a leading `.` — `.` and `..` are the only such names in POSIX, and
         *   HF does not use them, so refusing the whole class is free
         * - a leading or trailing `-` — not actually rejected by HF, but a name
         *   like that is always a paste artifact, and this is a cheap gate
         */
        fun parse(raw: String): Result {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return Result.Invalid("Enter a repository like Qwen/Qwen3-0.6B-GGUF.")
            if (trimmed.length > MAX_SEGMENT_LENGTH * 2 + 1) {
                return Result.Invalid("Repository id is too long.")
            }

            val slash = trimmed.indexOf('/')
            if (slash <= 0) return Result.Invalid("Repository id must be owner/name, for example Qwen/Qwen3-0.6B-GGUF.")
            if (trimmed.indexOf('/', slash + 1) >= 0) {
                return Result.Invalid("Repository id must be owner/name with exactly one slash.")
            }
            if (trimmed.contains('\\')) return Result.Invalid("Repository id must not contain a backslash.")

            val owner = trimmed.substring(0, slash)
            val name = trimmed.substring(slash + 1)
            if (!isSegment(owner)) return Result.Invalid("Owner must be letters, digits, dot, dash or underscore.")
            if (!isSegment(name)) return Result.Invalid("Name must be letters, digits, dot, dash or underscore.")
            if (owner == "." || owner == ".." || name == "." || name == "..") {
                return Result.Invalid("Repository id must not contain . or ..")
            }
            if (owner.startsWith('.')) return Result.Invalid("Owner must not start with a dot.")
            if (name.startsWith('.')) return Result.Invalid("Name must not start with a dot.")
            if (owner.startsWith('-') || owner.endsWith('-')) return Result.Invalid("Owner must not start or end with a dash.")
            if (name.startsWith('-') || name.endsWith('-')) return Result.Invalid("Name must not start or end with a dash.")

            return Result.Valid(HubRepoId(owner, name))
        }

        private fun isSegment(segment: String): Boolean {
            if (segment.isEmpty() || segment.length > MAX_SEGMENT_LENGTH) return false
            return segment.all { c ->
                (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '.' || c == '_' || c == '-'
            }
        }
    }

    /** Either a usable id or a message safe to show a user verbatim. */
    sealed interface Result {
        data class Valid(val repoId: HubRepoId) : Result
        data class Invalid(val message: String) : Result
    }
}

/**
 * A file's location inside a repo, validated so it can be turned into a local
 * path without escaping the models directory.
 *
 * HF nests GGUFs one or two folders deep in the popular repos
 * (the older TheBloke org put each quant in its own folder), so ignoring sub-paths
 * would miss most real repos. But a path that becomes `../../shared_prefs/x`
 * is a file-write primitive, so every segment is checked the same way [HubRepoId]
 * checks its own.
 */
data class HubFilePath private constructor(val segments: List<String>) {

    /** The path as HF reports it, e.g. `q4_k_m/model-00001-of-00002.gguf`. */
    val pathInRepo: String get() = segments.joinToString("/")

    /**
     * The final path segment, which is the only part that becomes a local file
     * name. Always a bare name: no slash, no dot-dot, so it cannot escape.
     */
    val fileName: String get() = segments.last()

    override fun toString(): String = pathInRepo

    companion object {
        /**
         * Parses an HF `rfilename` value.
         *
         * Rejects, in addition to the [HubRepoId] rules:
         * - `.` and `..` segments, which are the only traversal that matters
         * - any segment that is not a plain name, so a percent-escape or a
         *   control character cannot survive into a file name
         * - a leading `/`, which is absolute and would anchor the local path
         * - a trailing `/`, which is a directory and not a file
         * - an empty result, which HF does not send but a hostile mirror could
         */
        fun parse(raw: String): Result {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return Result.Invalid("File path is empty.")
            if (trimmed.length > 512) return Result.Invalid("File path is too long.")
            if (trimmed.startsWith('/')) return Result.Invalid("File path must be relative.")
            if (trimmed.endsWith('/')) return Result.Invalid("File path must name a file, not a folder.")
            if (trimmed.contains('\\')) return Result.Invalid("File path must not contain a backslash.")

            val parts = trimmed.split('/')
            if (parts.size > 8) return Result.Invalid("File path has too many folders.")
            for (part in parts) {
                if (part.isEmpty()) return Result.Invalid("File path has an empty folder name.")
                if (part == "." || part == "..") return Result.Invalid("File path must not contain . or ..")
                if (part.length > 255) return Result.Invalid("File path has an over-long folder name.")
                if (!part.all { c -> (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "._-+ ()[]" }) {
                    return Result.Invalid("File path has unsupported characters.")
                }
            }
            val last = parts.last()
            if (last == "." || last == "..") return Result.Invalid("File path must not contain . or ..")
            if (last.startsWith('.')) return Result.Invalid("File name must not start with a dot.")

            return Result.Valid(HubFilePath(parts.toList()))
        }
    }

    sealed interface Result {
        data class Valid(val filePath: HubFilePath) : Result
        data class Invalid(val message: String) : Result
    }
}
