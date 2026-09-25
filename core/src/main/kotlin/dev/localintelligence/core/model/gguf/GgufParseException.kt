package dev.localintelligence.core.model.gguf

/**
 * Thrown only when the file is not usable *at all*.
 *
 * WHY the split between this and [GgufWarning]: a warning means "I read what you gave
 * me, and here is what is wrong with it"; this means "there is no header, do not show
 * the user a model". Import-time code can therefore treat an exception as a hard
 * import failure and a warning as a banner. The distinction is the whole reason the
 * parser is usable on a half-downloaded file.
 */
class GgufParseException(
    val reason: Reason,
    message: String,
    /** Byte offset in the file where the problem was detected, or -1 if not applicable. */
    val offset: Long = -1L,
) : Exception(message) {

    enum class Reason {
        /** Fewer bytes than a fixed-size header field requires. */
        TRUNCATED,

        /** The four-byte magic was absent or in the wrong byte order. */
        NOT_A_GGUF_FILE,

        /** Magic was fine but the version field is not a GGUF version this build speaks. */
        UNSUPPORTED_VERSION,

        /** A declared count is beyond what any real file could hold. */
        IMPLAUSIBLE_COUNT,

        /** Reading would pass the end of the file or the configured header ceiling. */
        LIMIT_EXCEEDED,

        /** Well-formed framing that this build deliberately does not support. */
        UNSUPPORTED_STRUCTURE,
    }

    /** Short, stable phrase suitable for a UI error string; the message is for logs. */
    val displayText: String
        get() = when (reason) {
            Reason.TRUNCATED -> "File is too short to be a model"
            Reason.NOT_A_GGUF_FILE -> "Not a GGUF model file"
            Reason.UNSUPPORTED_VERSION -> "Unsupported GGUF version"
            Reason.IMPLAUSIBLE_COUNT -> "File header looks corrupt"
            Reason.LIMIT_EXCEEDED -> "File header is unreasonably large"
            Reason.UNSUPPORTED_STRUCTURE -> "File uses an unsupported structure"
        }
}
