package dev.localintelligence.core.policy

import dev.localintelligence.core.model.ToolArgs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A structured read of a tool call's arguments.
 *
 * WHY this exists as a separate step: the product rule is that risk depends on
 * WHAT the call does, not what the tool is called. "share this file to a
 * contact named mom" and "share this file to a stranger" are the same tool and
 * different risks. You cannot get that from `ToolRisk` alone — you have to look
 * at the arguments, once, in a way every rule can then share.
 *
 * Deliberately total: it never throws, and every extraction failure is
 * represented as data ([malformed]) rather than an exception. A policy engine
 * that can crash is a policy engine that fails open at the worst moment.
 */
data class ArgumentAnalysis(
    /** Every filesystem path the call names, from any path-like argument. */
    val paths: List<String>,

    /** Paths that resolve outside [PolicyConfig.appStorageRoots]. */
    val escapingPaths: List<String>,

    /** The recipient/target the call is addressed to, if one is present. */
    val target: String?,

    /** True when the target is in [PolicyConfig.knownTargets]. */
    val targetIsKnown: Boolean,

    /** The message body, if the call carries one. */
    val body: String?,

    /** True when the body is a bare link with no human-readable text. */
    val bodyIsBareLink: Boolean,

    /**
     * How many distinct items this call touches. Taken from an explicit count
     * argument when present, otherwise the size of the largest list argument.
     * Conservative: an unreadable count counts as unbounded, not as one.
     */
    val affectedCount: Int,

    /** True when [affectedCount] exceeds the bulk threshold. */
    val isBulk: Boolean,

    /** Names of arguments the engine could not interpret as the declared type. */
    val malformed: List<String>,

    /** Names the tool's schema marks required but the call omitted. */
    val missing: List<String>,

    /** True when no argument carried any usable content at all. */
    val isEmpty: Boolean,
) {
    companion object {
        /**
         * The safe reading of an argument set the engine could not parse.
         *
         * WHY every field is pessimistic: this object is the input to every
         * gating decision, so a field it cannot fill must resolve to the
         * restrictive value. `affectedCount` is [Int.MAX_VALUE] rather than 0
         * because "I could not tell how much this touches" is not "it touches
         * nothing".
         */
        fun unparseable(reason: String): ArgumentAnalysis = ArgumentAnalysis(
            paths = emptyList(),
            escapingPaths = emptyList(),
            target = null,
            targetIsKnown = false,
            body = null,
            bodyIsBareLink = false,
            affectedCount = Int.MAX_VALUE,
            isBulk = true,
            malformed = listOf(reason),
            missing = emptyList(),
            isEmpty = true,
        )
    }
}

/**
 * Turns a raw [ToolArgs] blob into an [ArgumentAnalysis].
 *
 * Pure and deterministic: no clock, no I/O, no randomness. The same arguments
 * always produce the same analysis, which is what lets a decision be replayed
 * from a trace and audited later.
 */
object ArgumentInspector {

    /** Cap on how many list entries are counted before a list is "unbounded". */
    private const val LIST_SCAN_CAP = 1000

    /**
     * Argument names treated as holding a filesystem path.
     *
     * Superset of [PolicyConfig.DEFAULT_PATH_ARGS] because a *tool* may also
     * be path-shaped by name (`files.delete`) even when its argument is called
     * something else. The caller's [PolicyConfig.pathArgumentNames] is unioned
     * with this so a custom name is honoured too.
     */
    private val IMPLICIT_PATH_ARGS = setOf("path", "paths", "filePath", "file_path", "dir", "directory", "filename")

    fun analyze(args: ToolArgs, config: PolicyConfig): ArgumentAnalysis {
        if (args.isEmpty()) {
            // An argument-less call is legitimate (battery.read). The risk tier
            // decides, not this function — so report "empty" without punishing.
            return ArgumentAnalysis(
                paths = emptyList(), escapingPaths = emptyList(), target = null,
                targetIsKnown = false, body = null, bodyIsBareLink = false,
                affectedCount = 0, isBulk = false, malformed = emptyList(),
                missing = emptyList(), isEmpty = true,
            )
        }

        val malformed = mutableListOf<String>()
        val pathArgs = config.pathArgumentNames + IMPLICIT_PATH_ARGS
        val paths = mutableListOf<String>()
        var count = 0
        var countDeclared = false

        for ((key, value) in args) {
            if (value is JsonNull) {
                // An explicit null is a malformed argument, not an absent one.
                // `sms.send{to=null}` must not read as "no recipient given".
                malformed += key
                continue
            }
            when (key) {
                in pathArgs -> {
                    when (val strings = flattenStrings(value)) {
                        null -> malformed += key
                        else -> {
                            paths += strings
                            // A path argument can also be a LIST (`paths`,
                            // `files`). Its length is the blast radius, so it
                            // has to reach the count even though this branch
                            // wins the `when` before the count branch can see
                            // the same key — `paths` is legitimately in both
                            // sets, and losing the count here would let a
                            // 500-file delete read as a single item.
                            count = maxOf(count, strings.size)
                            countDeclared = true
                        }
                    }
                }

                in config.countArgumentNames -> {
                    when (value) {
                        is JsonPrimitive -> {
                            val n = value.intOrNull
                            if (n == null) {
                                // A count we cannot read is unbounded. Not 0.
                                malformed += key
                                count = Int.MAX_VALUE
                                countDeclared = true
                            } else {
                                count = maxOf(count, n)
                                countDeclared = true
                            }
                        }

                        is JsonArray -> {
                            count = maxOf(count, value.size.coerceAtMost(LIST_SCAN_CAP))
                            countDeclared = true
                        }

                        else -> {
                            malformed += key
                            count = Int.MAX_VALUE
                            countDeclared = true
                        }
                    }
                }

                // A target or body we cannot read is malformed, NOT absent.
                // Without this branch `sms.send{to: {"a": 1}}` would fall through
                // as "no recipient given" and be judged as an unknown-target
                // confirmation, which reads as a considered decision when it is
                // really a parse failure the model should be told about.
                in config.targetArgumentNames, in config.bodyArgumentNames -> {
                    if (flattenStrings(value) == null) malformed += key
                }
            }
        }

        val target = args.keys.firstOrNull { it in config.targetArgumentNames }
            ?.let { flattenStrings(args[it])?.firstOrNull() }

        val body = args.keys.firstOrNull { it in config.bodyArgumentNames }
            ?.let { flattenStrings(args[it])?.firstOrNull() }

        return ArgumentAnalysis(
            paths = paths,
            escapingPaths = paths.filterNot { isInsideAppStorage(it, config) },
            target = target,
            targetIsKnown = target != null && target in config.knownTargets,
            body = body,
            bodyIsBareLink = body != null && isBareLink(body),
            // No count argument at all means the call affects the one thing it
            // names. A count we failed to read is already Int.MAX_VALUE above.
            affectedCount = if (countDeclared) count.coerceAtLeast(0) else 0,
            isBulk = countDeclared && (count > config.bulkThreshold || count == Int.MAX_VALUE),
            malformed = malformed,
            missing = emptyList(),
            isEmpty = false,
        )
    }

    /**
     * Whether a path stays inside the app's own storage.
     *
     * WHY lexical and not `File.canonicalPath`: resolving symlinks or touching
     * the filesystem is I/O, and a policy engine that touches the disk is a
     * policy engine whose answer can change between two runs on the same
     * input. Lexical normalisation is deterministic and can only err toward
     * calling a safe path escaping — which costs a confirmation dialog, not a
     * deleted file.
     */
    fun isInsideAppStorage(path: String, config: PolicyConfig): Boolean {
        // The `..` check runs on the RAW path, not the normalised one. Checking
        // after normalisation would be dead code: normalisation has already
        // consumed every `..` by the time it runs. A model that emits `..` is
        // either confused or probing, and a path needing normalisation is not
        // one a human can meaningfully eyeball in a confirmation dialog, so
        // both cases are escalated.
        if (hasTraversalSegment(path)) return false
        val normalized = normalizeLexically(path)
        return config.appStorageRoots.any { root -> normalized.startsWith(normalizeLexically(root)) }
    }

    /** True when any path segment is a `..` traversal. */
    fun hasTraversalSegment(path: String): Boolean =
        path.split('/').any { it == ".." }

    /**
     * Collapses `.` and `..` without touching the filesystem.
     *
     * Kept separate from [isInsideAppStorage] because a `..` that survives
     * normalisation is a traversal attempt and must stay visible to the caller
     * rather than being silently resolved into a clean-looking path.
     */
    fun normalizeLexically(path: String): String {
        if (path.isEmpty()) return path
        val isAbsolute = path.startsWith("/")
        val out = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeLast() else out.addLast("..")
                else -> out.addLast(segment)
            }
        }
        val joined = out.joinToString("/")
        return if (isAbsolute) "/$joined" else joined
    }

    /**
     * True when a message body is a single link and nothing else.
     *
     * WHY that specific shape matters: a body the user can read and judge is a
     * normal message. A body that is *only* a URL is what a compromised or
     * misdirected agent uses to launder a payload past a human who skims. The
     * decision is still a confirmation, not a block — the user may well be
     * sending a link to their own family — but the dialog says so.
     */
    fun isBareLink(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            // Also treat a bare domain as link-like.
            if (!trimmed.matches(BARE_DOMAIN)) return false
        }
        // Any prose alongside the link disqualifies it.
        return trimmed.split(Regex("\\s+")).none { token ->
            token.isNotEmpty() && !token.startsWith("http://") && !token.startsWith("https://")
        }
    }

    private val BARE_DOMAIN = Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/\\S*)?$")

    /**
     * Flattens a JSON value to strings, or null when it is not string-like.
     *
     * Numbers and booleans are rendered as text because models emit `"8"` for
     * an int constantly, and a phone number may arrive unquoted. Anything
     * structured (object, nested array) is a malformed argument, not a string.
     */
    private fun flattenStrings(value: JsonElement?): List<String>? {
        val primitive = value as? JsonPrimitive
        if (primitive != null) {
            // Numbers and booleans render as their literal text: a model that
            // emits 8 for a count, or an unquoted phone number, is still
            // stating an intent. Only structured values are refused.
            return listOf(primitive.content)
        }
        val array = value as? JsonArray ?: return null
        val out = ArrayList<String>(array.size)
        for (item in array) {
            val element = item as? JsonPrimitive ?: return null
            out += element.content
        }
        return out
    }
}
