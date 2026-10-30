package dev.localintelligence.android.scripts

import dev.localintelligence.android.io.readUpTo
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The two files a script plugin is made of, as the user sees them: the text of
 * `plugin.json` and of `main.js`. Nothing is written until [ScriptPackages.check]
 * has accepted both, so a pasted or downloaded package is reviewed as text first.
 */
data class ScriptPackage(val manifestText: String, val mainJs: String)

/** A checked manifest. Every field is already within its limits. */
data class ScriptManifest(
    val id: String,
    val title: String,
    val summary: String,
    val version: String,
    val tools: List<ScriptToolSpec>,
)

/** One tool as declared in `plugin.json`. [name] is also the JavaScript function name in `main.js`. */
data class ScriptToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val risk: ToolRisk,
)

/**
 * Parses, checks and unpacks script plugin packages.
 *
 * WHY THE ALLOWED SET IS SMALL. A script can compute, and it can return text the
 * model reads, but it cannot reach the phone or the network: the sandbox has no
 * bridge to either. So the only risks a script can carry are "reads something" and
 * "changes something it owns", which are the two lowest tiers. A destructive or
 * egress tool is refused at install rather than trusted to behave.
 *
 * WHY THE FORMAT IS STRICT. A manifest that is loose here becomes a tool the
 * model is offered with a description the author wrote, so names, text lengths
 * and the risk tier are all bounded before anything is saved.
 */
object ScriptPackages {

    /** Both files together, as UTF-8. The same cap as a URL download. */
    const val MAX_PACKAGE_BYTES: Int = 256 * 1024

    /** A plugin with more tools than this is too much for a small model to choose between. */
    const val MAX_TOOLS: Int = 8

    /** Entries read from a zip. Anything beyond this is not a plugin. */
    private const val MAX_ZIP_ENTRIES = 64

    private val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val ID = NAME

    sealed interface Check {
        data class Ok(val manifest: ScriptManifest, val mainJs: String) : Check
        data class Refused(val reason: String) : Check
    }

    sealed interface Unzip {
        data class Ok(val pkg: ScriptPackage) : Unzip
        data class Refused(val reason: String) : Unzip
    }

    /** Parses and checks a package. Pure: no file, no sandbox. */
    fun check(pkg: ScriptPackage): Check {
        val size = pkg.manifestText.toByteArray().size + pkg.mainJs.toByteArray().size
        if (size > MAX_PACKAGE_BYTES) return Check.Refused("The plugin is larger than 256 KB.")
        if (pkg.mainJs.isBlank()) return Check.Refused("main.js is empty.")

        val root = runCatching { Json.parseToJsonElement(pkg.manifestText) }.getOrNull()?.let { it as? JsonObject }
            ?: return Check.Refused("plugin.json is not a JSON object.")

        val id = text(root, "id", max = 32) ?: return Check.Refused("plugin.json needs an id: lowercase letters, digits and _, starting with a letter.")
        if (!ID.matches(id)) return Check.Refused("The id \"$id\" must be lowercase letters, digits and _, starting with a letter.")
        val title = text(root, "title", max = 60) ?: return Check.Refused("plugin.json needs a title (up to 60 characters).")
        val summary = text(root, "summary", max = 200) ?: return Check.Refused("plugin.json needs a summary (up to 200 characters).")
        val version = text(root, "version", max = 20) ?: return Check.Refused("plugin.json needs a version (up to 20 characters).")

        val toolsJson = root["tools"] as? JsonArray
            ?: return Check.Refused("plugin.json needs a tools array.")
        if (toolsJson.isEmpty() || toolsJson.size > MAX_TOOLS) {
            return Check.Refused("A plugin has between 1 and $MAX_TOOLS tools.")
        }
        val tools = ArrayList<ScriptToolSpec>()
        for (item in toolsJson) {
            val tool = item as? JsonObject ?: return Check.Refused("Each tool must be a JSON object.")
            val name = text(tool, "name", max = 32) ?: return Check.Refused("Each tool needs a name.")
            if (!NAME.matches(name)) {
                return Check.Refused("The tool name \"$name\" must be lowercase letters, digits and _, starting with a letter.")
            }
            if (tools.any { it.name == name }) return Check.Refused("The tool name \"$name\" is used twice.")
            val description = text(tool, "description", max = 300) ?: return Check.Refused("Tool $name needs a description (up to 300 characters).")
            val parameters = when (val p = tool["parameters"]) {
                null -> buildJsonObject { put("type", "object") }
                is JsonObject -> p
                else -> return Check.Refused("Tool $name: parameters must be a JSON schema object.")
            }
            val risk = when ((tool["risk"] as? JsonPrimitive)?.contentOrNull) {
                "read_only" -> ToolRisk.READ_ONLY
                "reversible" -> ToolRisk.REVERSIBLE
                else -> return Check.Refused("Tool $name: risk must be \"read_only\" or \"reversible\". A script cannot have a riskier tier.")
            }
            if (!Regex("function\\s+$name\\s*\\(").containsMatchIn(pkg.mainJs)) {
                return Check.Refused("main.js must define function $name(args).")
            }
            tools += ScriptToolSpec(name, description, parameters, risk)
        }
        val manifest = ScriptManifest(id, title, summary, version, tools)
        return Check.Ok(manifest, pkg.mainJs)
    }

    /**
     * Reads `plugin.json` and `main.js` from a .zip, at the root or in one folder.
     *
     * Only those two entries are read, each capped, and the total is capped. A
     * name that tries to leave its folder (`..`, a leading slash, a backslash) is
     * refused outright. Nothing is extracted to disk: the bytes are read in memory
     * and handed to [check] like pasted text.
     */
    fun unzip(bytes: ByteArray): Unzip {
        var manifest: String? = null
        var mainJs: String? = null
        var total = 0
        var entries = 0
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    if (entries > MAX_ZIP_ENTRIES) return Unzip.Refused("The zip has too many files.")
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/") || name.contains('\\')) {
                        return Unzip.Refused("The zip has a path that leaves its folder: $name")
                    }
                    if (entry.isDirectory) continue
                    val parts = name.split('/')
                    if (parts.size > 2) continue
                    val base = parts.last()
                    if (base != "plugin.json" && base != "main.js") continue
                    val room = MAX_PACKAGE_BYTES - total
                    val content = zip.readUpTo(room + 1)
                    total += content.size
                    if (content.size > room) return Unzip.Refused("The zip is larger than 256 KB.")
                    val text = String(content, Charsets.UTF_8)
                    if (base == "plugin.json") {
                        if (manifest != null) return Unzip.Refused("The zip has two plugin.json files.")
                        manifest = text
                    } else {
                        if (mainJs != null) return Unzip.Refused("The zip has two main.js files.")
                        mainJs = text
                    }
                }
            }
        } catch (e: IOException) {
            return Unzip.Refused("The zip could not be read.")
        }
        val m = manifest ?: return Unzip.Refused("The zip has no plugin.json.")
        val js = mainJs ?: return Unzip.Refused("The zip has no main.js.")
        return Unzip.Ok(ScriptPackage(m, js))
    }

    /** A string field, trimmed, non-blank and within [max] characters; null when it is not. */
    private fun text(obj: JsonObject, key: String, max: Int): String? {
        val value = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
        return value.takeIf { it.isNotEmpty() && it.length <= max }
    }
}
