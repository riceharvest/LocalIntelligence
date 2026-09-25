package dev.localintelligence.android.tools.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import dev.localintelligence.android.tools.files.OBSERVATION_BUDGET
import dev.localintelligence.android.tools.files.ToolSafety
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

// =====================================================================================
// APPS: package names, fuzzy labels, and the share URI problem
//
// Everything with an `android.*` type in its signature is a thin shell around a pure
// object below it: [AppMatcher] (fuzzy label resolution), [AppQuery] (filtering and
// ranking) and [AppShareTarget] (the file:// refusal). That split is what makes this
// file testable on the JVM — PackageManager has no useful double here, and
// `intent.resolveActivity()` returns null in a unit test for reasons that have nothing
// to do with the code under test.
//
// THE AMBIGUITY RULE, which is the whole point of apps.open:
//   a fuzzy label match that fits more than one app returns InvalidArguments listing
//   every candidate. It never picks one. "Open Chrome" when Chrome, Chrome Beta and
//   Chrome Canary are installed must not silently open the wrong one; the model is
//   told the candidates and re-issues with a package name. Guessing here is how an
//   agent ends up in a banking app.
//
// apps.share AND THE file:// PROBLEM — the chosen strategy is SAF content://:
//   On Android 7.0 (API 24 — below this module's minSdk 26) handing a `file://` URI to
//   another app throws FileUriExposedException in the SENDER, i.e. here, at
//   startActivity. The two legal ways out are (a) a FileProvider, which needs an
//   AndroidManifest <provider> entry plus res/xml/file_paths.xml, or (b) a content://
//   URI obtained through SAF. This build has neither: :android is a library whose
//   manifest is `<manifest/>` and empty, and adding a provider entry is out of scope
//   for this branch. So the SAF route is implemented and a `file://` URI is REFUSED at
//   the argument layer with a message that says exactly what to do instead — a crash
//   inside the chooser is not recoverable by the agent loop, but a typed
//   InvalidArguments is. The exact manifest XML needed to enable the FileProvider
//   route is in the PR report.
// =====================================================================================

/** One installed, launchable app. */
data class AppEntry(
    val packageName: String,
    val label: String,
    /** True when the package declares a MAIN/LAUNCHER activity. */
    val launchable: Boolean,
)

/** The result of resolving a fuzzy app name. */
sealed interface AppMatch {
    /** Exactly one app. [entry] is safe to launch. */
    data class Resolved(val entry: AppEntry) : AppMatch

    /**
     * More than one app fits. Never collapsed to a guess — the caller must surface
     * [candidates] and ask for a package name.
     */
    data class Ambiguous(val query: String, val candidates: List<AppEntry>) : AppMatch

    /** Nothing fits. */
    data class None(val query: String) : AppMatch
}

/**
 * Fuzzy label -> app resolution, as pure functions over a list.
 *
 * Kept pure so the ambiguity rule can be proven without a PackageManager. The scoring
 * is deliberately simple and total: exact match, then prefix, then word-boundary
 * prefix, then substring, then a token-overlap fallback. Every tier is checked for
 * multiplicity, because a tier that matches three apps is ambiguous even if a looser
 * tier would have matched exactly one.
 */
object AppMatcher {

    /** Never show the model more than this many candidates for an ambiguous name. */
    const val MAX_CANDIDATES = 8

    /** Longest query considered; a longer one is a confused model. */
    const val MAX_QUERY_CHARS = 120

    /** A package name is dot-separated lowercase identifiers; this is the whole shape. */
    private val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    fun isPackageName(s: String?): Boolean =
        s != null && s.length <= 255 && PACKAGE_PATTERN.matches(s)

    /**
     * Resolves [query] against [apps].
     *
     * A syntactically valid package name is matched EXACTLY and, if it is not
     * installed, reported as not found — never fuzzy-matched. Falling through to a
     * label search for "com.android.chrome" would find nothing sensible, and a caller
     * who said a package name means it.
     */
    fun match(query: String, apps: List<AppEntry>): AppMatch {
        val q = query.trim()
        if (q.isEmpty()) return AppMatch.None(query)

        if (isPackageName(q)) {
            val exact = apps.filter { it.packageName.equals(q, ignoreCase = true) }
            return when (exact.size) {
                0 -> AppMatch.None(q)
                1 -> AppMatch.Resolved(exact.first())
                // Two rows with one package name cannot happen from a real
                // PackageManager, but a duplicated entry must not pick arbitrarily.
                else -> AppMatch.Ambiguous(q, exact.take(MAX_CANDIDATES))
            }
        }

        val lower = q.lowercase(Locale.ROOT)

        val exactLabel = apps.filter { it.label.trim().lowercase(Locale.ROOT) == lower }
        if (exactLabel.size == 1) return AppMatch.Resolved(exactLabel.first())
        if (exactLabel.size > 1) return AppMatch.Ambiguous(q, exactLabel.take(MAX_CANDIDATES))

        val prefix = apps.filter { it.label.trim().lowercase(Locale.ROOT).startsWith(lower) }
        if (prefix.size == 1) return AppMatch.Resolved(prefix.first())
        if (prefix.size > 1) return AppMatch.Ambiguous(q, prefix.take(MAX_CANDIDATES))

        // A match at a word boundary ("maps" -> "Google Maps") outranks a match
        // buried mid-word ("maps" -> "Heatmaps"), so it is checked first.
        val wordPrefix = apps.filter { candidate ->
            val label = candidate.label.lowercase(Locale.ROOT)
            label.startsWith(lower) || label.contains(" $lower")
        }
        if (wordPrefix.size == 1) return AppMatch.Resolved(wordPrefix.first())
        if (wordPrefix.size > 1) return AppMatch.Ambiguous(q, wordPrefix.take(MAX_CANDIDATES))

        val contains = apps.filter { it.label.lowercase(Locale.ROOT).contains(lower) }
        if (contains.size == 1) return AppMatch.Resolved(contains.first())
        if (contains.size > 1) return AppMatch.Ambiguous(q, contains.take(MAX_CANDIDATES))

        // Last resort: every query token must appear somewhere in the label. This is
        // what makes "play music" find "Google Play Music" without an embedding.
        val tokens = lower.split(Regex("\\s+")).filter { it.length > 1 }
        if (tokens.isNotEmpty()) {
            val overlap = apps.filter { candidate ->
                val label = candidate.label.lowercase(Locale.ROOT)
                tokens.all { label.contains(it) }
            }
            if (overlap.size == 1) return AppMatch.Resolved(overlap.first())
            if (overlap.size > 1) return AppMatch.Ambiguous(q, overlap.take(MAX_CANDIDATES))
        }

        return AppMatch.None(q)
    }

    /**
     * The observation for an ambiguous match. States the count, names every candidate
     * with its package, and gives the exact next call to make.
     *
     * This string is the model's only chance to recover, so it has to be actionable
     * rather than merely diagnostic: "call apps.open with package X" is the whole
     * recovery procedure.
     */
    fun ambiguityObservation(match: AppMatch.Ambiguous): String {
        val lines = match.candidates.map { "- ${it.label} (${it.packageName})" }
        val more = if (match.candidates.size >= MAX_CANDIDATES) ", and possibly more" else ""
        return "${match.candidates.size} apps match '${match.query}'$more:\n" +
            lines.joinToString("\n") +
            "\nCall apps.open again with the exact package name, e.g. " +
            "\"package\": \"${match.candidates.first().packageName}\"."
    }
}

/** Defensive coercion for the app tools, mirroring the file tools. */
object AppArgs {

    const val DEFAULT_LIMIT = 30
    const val MAX_LIMIT = 100
    const val MAX_QUERY_CHARS = AppMatcher.MAX_QUERY_CHARS
    const val MAX_TEXT_CHARS = 2000

    /** Clamped to `[1, MAX_LIMIT]`. A non-positive value means "the model did not mean it". */
    fun limit(raw: JsonElement?): Int {
        val n = longOrNull(raw) ?: return DEFAULT_LIMIT
        return when {
            n < 1L -> DEFAULT_LIMIT
            n > MAX_LIMIT -> MAX_LIMIT
            else -> n.toInt()
        }
    }

    fun optionalString(raw: JsonElement?, maxChars: Int = MAX_QUERY_CHARS): String? {
        val text = stringOrNull(raw)?.trim() ?: return null
        if (text.isEmpty()) return null
        return if (text.length > maxChars) text.take(maxChars) else text
    }

    fun stringOrNull(raw: JsonElement?): String? = when (raw) {
        null, is JsonNull -> null
        is JsonPrimitive -> raw.contentOrNull
        is JsonArray, is JsonObject -> null
    }

    fun longOrNull(raw: JsonElement?): Long? {
        val prim = raw as? JsonPrimitive ?: return null
        if (prim is JsonNull) return null
        prim.longOrNull?.let { return it }
        val text = prim.contentOrNull?.trim() ?: return null
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
    }
}

/** Filtering and ranking a launchable-app list, as a pure function. */
object AppQuery {

    /**
     * Filters [apps] by a free-text [query] and caps the result.
     *
     * With no query every app is returned, ranked by label. With a query, a row is
     * kept when any token appears in the label OR in the package name — a user who
     * types "com.android" is searching by package, and one who types "clock" is
     * searching by label. Both are one substring test.
     *
     * Ranking is by [rank] descending, then label, then package, so two calls with
     * the same inputs always agree.
     */
    fun filter(apps: List<AppEntry>, query: String?, limit: Int): List<AppEntry> {
        val cap = limit.coerceIn(1, AppArgs.MAX_LIMIT)
        val q = query?.trim()?.lowercase(Locale.ROOT)
        if (q.isNullOrEmpty()) {
            return apps.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName }))
                .take(cap)
        }
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return apps.asSequence()
            .filter { app ->
                val label = app.label.lowercase(Locale.ROOT)
                val pkg = app.packageName.lowercase(Locale.ROOT)
                tokens.any { label.contains(it) || pkg.contains(it) }
            }
            .sortedWith(
                compareByDescending<AppEntry> { rank(it, q) }
                    .thenBy { it.label.lowercase(Locale.ROOT) }
                    .thenBy { it.packageName },
            )
            .take(cap)
            .toList()
    }

    /**
     * How well [app] matches [query]: higher is better, 0 means not a match at all.
     *
     * Exact package (100) beats exact label (90), which beats a label prefix (60),
     * which beats a word-boundary prefix (40), which beats a bare substring (20).
     * The tiers exist so that typing a full package name surfaces the right app
     * above an app that merely happens to contain that string in its label.
     */
    fun rank(app: AppEntry, query: String): Int {
        val label = app.label.trim().lowercase(Locale.ROOT)
        val pkg = app.packageName.lowercase(Locale.ROOT)
        return when {
            pkg == query -> 100
            label == query -> 90
            label.startsWith(query) -> 60
            label.contains(" $query") -> 40
            label.contains(query) -> 20
            pkg.contains(query) -> 10
            else -> 0
        }
    }

    /**
     * Renders an app list for the model. `label (package)` per line, with an explicit
     * statement of what was withheld.
     */
    fun format(apps: List<AppEntry>, withheld: Int, what: String): String {
        if (apps.isEmpty()) {
            return "No $what matched. Check apps.list for the exact label, or pass the " +
                "full package name."
        }
        val sb = StringBuilder()
        sb.append(if (withheld > 0) {
            "${apps.size} $what shown, $withheld more not shown."
        } else {
            "${apps.size} $what."
        }).append('\n')
        for (app in apps) {
            sb.append("- ").append(app.label).append(" (").append(app.packageName).append(')')
            if (!app.launchable) sb.append(" [no launcher]")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}

/** The decision about a URI handed to apps.share. */
sealed interface ShareTarget {
    /** A usable SAF/MediaStore document URI. */
    data class Shareable(val uri: String) : ShareTarget

    /** Plain text with no attachment. Always shareable. */
    data class TextOnly(val text: String) : ShareTarget

    /**
     * A `file://` URI. Refused rather than converted, because this build has no
     * FileProvider. The message tells the caller what to pass instead.
     */
    data class FileUriRefused(val uri: String) : ShareTarget

    /** Neither a URI nor any text to share. */
    data object Nothing : ShareTarget
}

/**
 * Decides what apps.share can legally attach.
 *
 * The load-bearing case is [ShareTarget.FileUriRefused]. A `file://` URI is illegal
 * across a process boundary from Android 7.0, and the failure is an exception thrown
 * inside `startActivity` — after the chooser intent is built, at a point where the
 * agent loop can only see a crashed step. Refusing it here converts that into a typed
 * `InvalidArguments` the model can act on.
 */
object AppShareTarget {

    const val FILE_SCHEME = "file://"
    const val CONTENT_SCHEME = "content://"

    /**
     * Resolves the share target from the raw arguments.
     *
     * @param uri a URI string, or null.
     * @param text plain text to share, or null.
     * @param allowFileUris true only when a FileProvider is actually configured;
     *   with one, a `file://` path would be converted by the caller. False here.
     */
    fun resolve(uri: String?, text: String?, allowFileUris: Boolean = false): ShareTarget {
        val u = uri?.trim()?.takeIf { it.isNotEmpty() }
        val t = text?.takeIf { it.isNotBlank() }
        return when {
            u == null && t == null -> ShareTarget.Nothing
            u == null -> ShareTarget.TextOnly(t!!)
            u.startsWith(CONTENT_SCHEME) && u.length > CONTENT_SCHEME.length -> ShareTarget.Shareable(u)
            u.startsWith(FILE_SCHEME) && !allowFileUris -> ShareTarget.FileUriRefused(u)
            // A bare path or an unknown scheme: not something any receiver can read.
            else -> ShareTarget.FileUriRefused(u)
        }
    }

    /**
     * The message shown when a `file://` URI is passed.
     *
     * It names the actual reason (FileUriExposedException, Android 7+) and the actual
     * fix (a content:// URI from files.search), because a vague "invalid uri" here
     * produces exactly the retry loop the tool contract warns about.
     */
    fun fileUriRefusal(uri: String): String =
        "Cannot share '$uri': a file:// URI is not allowed to cross app boundaries on " +
            "Android 7.0 and newer (FileUriExposedException). Pass 'uri' as a content:// " +
            "URI from files.search or files.list, or share text only with 'text'."

    /** MIME type to declare for an attachment, from a display name, else a safe default. */
    fun mimeFor(name: String?): String =
        dev.localintelligence.android.tools.files.MimeTypes.fromExtension(name) ?: "application/octet-stream"
}

// =====================================================================================
// PackageManager plumbing
// =====================================================================================

private fun PackageManager.launchableApps(): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved: List<ResolveInfo> = try {
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            queryIntentActivities(intent, 0)
        }
    } catch (t: SecurityException) {
        return emptyList()
    }
    val out = ArrayList<AppEntry>(resolved.size)
    for (info in resolved) {
        val pkg = info.activityInfo?.packageName ?: continue
        val label = try {
            info.loadLabel(this).toString().trim().ifEmpty { pkg }
        } catch (t: RuntimeException) {
            // A package that disappeared between resolve and load must not abort
            // the whole listing; fall back to the package name.
            pkg
        }
        out += AppEntry(pkg, label, launchable = true)
    }
    return out
}

private fun PackageManager.launchIntentFor(entry: AppEntry): Intent? {
    val direct = getLaunchIntentForPackage(entry.packageName)
    if (direct != null) return direct
    // A package with a MAIN/LAUNCHER filter but no default launch intent still
    // needs to be launchable; resolve it explicitly before giving up.
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(entry.packageName)
    val match = try {
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            resolveActivity(intent, 0)
        }
    } catch (t: SecurityException) {
        null
    } ?: return null
    // Build the launch intent from the component name rather than
    // ActivityInfo.applicationIntent, which is @hide in the SDK. Targeting the
    // component explicitly also survives a package that declares a non-default
    // launcher activity, where getLaunchIntentForPackage returns null.
    val activity = match.activityInfo ?: return null
    val component = activity.name ?: return null
    return Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(activity.packageName, component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun JsonObject.str(name: String): JsonElement? = this[name]

private fun ok(observation: String, data: JsonObject? = null) =
    ToolResult(success = true, observation = observation, data = data)

private fun fail(observation: String, error: ToolError) =
    ToolResult(success = false, observation = observation, error = error)

/** List launchable apps. */
class AppsListTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "apps.list",
        description = "List launchable apps on this device with their labels and package names, optionally filtered by a query.",
        category = "apps",
        schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("maxLength", AppArgs.MAX_QUERY_CHARS)
                        put("description", "Filter by app label or package name.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", AppArgs.MAX_LIMIT)
                        put("description", "How many apps to return. Default ${AppArgs.DEFAULT_LIMIT}.")
                    })
                },
            )
            putJsonArray("required") { }
            put("additionalProperties", false)
        },
        risk = ToolRisk.READ_ONLY,
        tags = setOf("apps", "applications", "installed", "launcher", "home screen", "what apps do i have", "packages"),
        requiredPermission = "android.permission.QUERY_ALL_PACKAGES is NOT used; package visibility rules apply on API 30+",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolSafety.guard("apps.list") {
            val query = AppArgs.optionalString(args.str("query"))
            val limit = AppArgs.limit(args.str("limit"))
            val all = withContext(Dispatchers.IO) { appContext.packageManager.launchableApps() }
            if (all.isEmpty()) {
                return@guard fail(
                    "No launchable apps are visible to this app. On Android 11 and newer " +
                        "the system hides most installed apps unless the app declares a " +
                        "<queries> element, or the user selects one in Settings.",
                    ToolError.Unavailable("PackageManager returned no launchable activities"),
                )
            }
            val matched = if (query == null) all else all.filter { AppQuery.rank(it, query.lowercase(Locale.ROOT)) > 0 || it.label.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT)) }
            val shown = AppQuery.filter(matched, query, limit)
            ok(
                AppQuery.format(shown, (matched.size - shown.size).coerceAtLeast(0), "app" + if (query != null) " matching '$query'" else "s"),
                buildJsonObject {
                    put("count", shown.size)
                    put("total", matched.size)
                },
            )
        }
}

/** Launch an app by package or by label. */
class AppsOpenTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "apps.open",
        description = "Open an app by its exact package name, or by a label that matches exactly one installed app.",
        category = "apps",
        schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact package name, e.g. com.android.chrome. Preferred.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("maxLength", AppArgs.MAX_QUERY_CHARS)
                        put("description", "App label to fuzzy-match, e.g. \"Maps\". Ambiguous names are refused.")
                    })
                },
            )
            putJsonArray("required") { }
            put("additionalProperties", false)
        },
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("open", "launch", "start", "run", "switch to", "go to app", "show me the app"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolSafety.guard("apps.open") {
            val pkg = AppArgs.optionalString(args.str("package"), 255)
            val name = AppArgs.optionalString(args.str("name"))
            if (pkg == null && name == null) {
                return@guard fail(
                    "apps.open needs a 'package' (exact, preferred) or a 'name' to match " +
                        "against app labels.",
                    ToolError.InvalidArguments("neither package nor name supplied"),
                )
            }

            // A package name is a precise request. Verify it directly rather than
            // building the whole app list for it: that is one PackageManager call
            // instead of one per installed app.
            if (pkg != null && !AppMatcher.isPackageName(pkg)) {
                return@guard fail(
                    "'$pkg' is not a valid package name. Pass an exact package such as " +
                        "com.android.chrome, or use 'name' for a label.",
                    ToolError.InvalidArguments("malformed package name"),
                )
            }
            val installed = if (pkg != null) {
                withContext(Dispatchers.IO) { packageEntry(appContext, pkg) }
            } else {
                val all = withContext(Dispatchers.IO) { appContext.packageManager.launchableApps() }
                when (val match = AppMatcher.match(name!!, all)) {
                    is AppMatch.Resolved -> match.entry
                    is AppMatch.Ambiguous -> return@guard fail(
                        AppMatcher.ambiguityObservation(match),
                        ToolError.InvalidArguments("${match.candidates.size} apps match '${match.query}'"),
                    )
                    is AppMatch.None -> return@guard fail(
                        "No installed app matches '${match.query}'. Call apps.list to see " +
                            "available apps and use the exact label or package name.",
                        ToolError.NotFound("no app matches '${match.query}'"),
                    )
                }
            }

            if (installed == null) {
                return@guard fail(
                    "${pkg ?: name} is not an installed app on this device, or it has no " +
                        "launcher activity. Call apps.list to see what is available.",
                    ToolError.NotFound("package ${pkg ?: name} is not installed"),
                )
            }
            val intent = withContext(Dispatchers.IO) { appContext.packageManager.launchIntentFor(installed) }
            if (intent == null) {
                return@guard fail(
                    "${installed.label} is installed but has no launchable activity, so it " +
                        "cannot be opened.",
                    ToolError.Unavailable("no launch intent for ${installed.packageName}"),
                )
            }
            val launched = withContext(Dispatchers.IO) {
                try {
                    appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (t: ActivityNotFoundException) {
                    false
                } catch (t: SecurityException) {
                    false
                }
            }
            if (!launched) {
                return@guard fail(
                    "Android refused to open ${installed.label} " +
                        "(${installed.packageName}). It may be disabled or restricted.",
                    ToolError.Unavailable("startActivity was refused"),
                )
            }
            ok("Opened ${installed.label} (${installed.packageName}).")
        }
}

/** Share a document or a piece of text. */
class AppsShareTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "apps.share",
        description = "Share a content:// document or a text snippet through the Android share sheet, with the user's confirmation.",
        category = "apps",
        schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("uri", buildJsonObject {
                        put("type", "string")
                        put("description", "content:// document URI from files.list or files.search.")
                    })
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("maxLength", AppArgs.MAX_TEXT_CHARS)
                        put("description", "Plain text to share, with no attachment.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Display name of the attachment, used for the MIME type and the share title.")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("maxLength", 200)
                        put("description", "Title for the share sheet.")
                    })
                },
            )
            putJsonArray("required") { }
            put("additionalProperties", false)
        },
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        tags = setOf("share", "send", "attach", "share file", "share text", "pass to another app", "forward"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolSafety.guard("apps.share") {
            // The runtime gates this on userConfirmed; the tool does not re-ask and
            // does not decide. The check here is a defence in depth for a caller that
            // invokes the tool directly, and it can only ever refuse.
            if (!context.userConfirmed) {
                return@guard fail(
                    "Sharing leaves the device and was not confirmed by the user. " +
                        "Ask before sharing anything that is not the user's own text.",
                    ToolError.PermissionDenied("apps.share requires explicit user confirmation"),
                )
            }

            val uri = AppArgs.optionalString(args.str("uri"), 2048)
            val text = AppArgs.optionalString(args.str("text"), AppArgs.MAX_TEXT_CHARS)
            val name = AppArgs.optionalString(args.str("name"), 200)
            val title = AppArgs.optionalString(args.str("title"), 200)

            when (val target = AppShareTarget.resolve(uri, text)) {
                is ShareTarget.Nothing -> return@guard fail(
                    "apps.share needs a 'uri' (a content:// document) or some 'text' to share.",
                    ToolError.InvalidArguments("nothing to share"),
                )
                is ShareTarget.FileUriRefused -> return@guard fail(
                    AppShareTarget.fileUriRefusal(target.uri.take(200)),
                    ToolError.InvalidArguments("file:// URI cannot cross a process boundary"),
                )
                is ShareTarget.TextOnly -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, target.text)
                        if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (!startChooser(intent, title ?: "Share text")) {
                        return@guard fail(
                            "No app on this device can receive shared text.",
                            ToolError.Unavailable("startActivity for chooser was refused"),
                        )
                    }
                    ok("Opened the share sheet with ${target.text.length} characters of text.")
                }
                is ShareTarget.Shareable -> {
                    val parsed = Uri.parse(target.uri)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        // The declared type must match the attachment or receivers will
                        // filter it out of the chooser entirely.
                        type = AppShareTarget.mimeFor(name ?: parsed.lastPathSegment)
                        putExtra(Intent.EXTRA_STREAM, parsed)
                        // Both flags are required: NEW_TASK because this runs without an
                        // Activity, and the grant flag because the receiving app has no
                        // permission of its own to read our document.
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newRawUri(name ?: "shared document", parsed)
                    }
                    if (!startChooser(intent, title ?: "Share ${name ?: "document"}")) {
                        return@guard fail(
                            "No app on this device can receive ${name ?: "that document"}.",
                            ToolError.Unavailable("startActivity for chooser was refused"),
                        )
                    }
                    ok("Opened the share sheet for ${name ?: target.uri.take(80)}.")
                }
            }
        }

    private suspend fun startChooser(intent: Intent, title: String): Boolean =
        withContext(Dispatchers.IO) {
            val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                appContext.startActivity(chooser)
                true
            } catch (t: ActivityNotFoundException) {
                false
            } catch (t: SecurityException) {
                false
            }
        }
}

/**
 * Resolves an exact package name to an [AppEntry] without listing every app.
 *
 * Falls back to the application label via `getApplicationInfo`, and to the package name
 * when even that is unavailable — an entry with a usable label is not a precondition
 * for launching something.
 */
private suspend fun packageEntry(context: Context, pkg: String): AppEntry? =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val info: ApplicationInfo = try {
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                pm.getApplicationInfo(pkg, 0)
            }
        } catch (t: PackageManager.NameNotFoundException) {
            return@withContext null
        } catch (t: SecurityException) {
            return@withContext null
        }
        val label = try {
            info.loadLabel(pm).toString().trim().ifEmpty { pkg }
        } catch (t: RuntimeException) {
            pkg
        }
        AppEntry(pkg, label, launchable = pm.getLaunchIntentForPackage(pkg) != null)
    }

/** Every app tool, in registry order. */
fun appTools(context: Context): List<AgentTool> = listOf(
    AppsListTool(context),
    AppsOpenTool(context),
    AppsShareTool(context),
)
