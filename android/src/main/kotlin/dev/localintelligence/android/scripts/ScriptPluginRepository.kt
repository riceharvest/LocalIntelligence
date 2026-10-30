package dev.localintelligence.android.scripts

import java.io.File

/**
 * Script plugins on disk: `<root>/<id>/plugin.json` and `<root>/<id>/main.js`, one
 * folder each. Same shape as [dev.localintelligence.android.skills.SkillRepository].
 *
 * Every write goes through [save], which checks first, so a folder that is not a
 * usable plugin is never written. A folder that no longer checks is skipped by
 * [list], not fatal, the same as a broken skill.
 */
class ScriptPluginRepository(private val root: File) {

    /** A plugin read from disk: its checked manifest and the source it runs. */
    data class Installed(val manifest: ScriptManifest, val mainJs: String)

    sealed interface SaveResult {
        data class Saved(val id: String) : SaveResult
        data class Refused(val reason: String) : SaveResult
    }

    /** Usable plugins, by title. */
    fun list(): List<Installed> {
        val folders = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return folders.mapNotNull { folder ->
            val manifest = File(folder, MANIFEST).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
                ?: return@mapNotNull null
            val main = File(folder, MAIN).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
                ?: return@mapNotNull null
            when (val checked = ScriptPackages.check(ScriptPackage(manifest, main))) {
                is ScriptPackages.Check.Ok -> Installed(checked.manifest, checked.mainJs)
                is ScriptPackages.Check.Refused -> null
            }
        }.sortedBy { it.manifest.title.lowercase() }
    }

    fun save(pkg: ScriptPackage): SaveResult {
        val checked = when (val result = ScriptPackages.check(pkg)) {
            is ScriptPackages.Check.Refused -> return SaveResult.Refused(result.reason)
            is ScriptPackages.Check.Ok -> result
        }
        val id = checked.manifest.id
        val folder = folderFor(id) ?: return SaveResult.Refused("That id does not make a folder name.")
        if (folder.exists()) {
            return SaveResult.Refused("A plugin with the id \"$id\" is already installed. Delete it first.")
        }
        return runCatching {
            folder.mkdirs()
            writeAtomically(File(folder, MAIN), pkg.mainJs)
            writeAtomically(File(folder, MANIFEST), pkg.manifestText)
            SaveResult.Saved(id)
        }.getOrElse { SaveResult.Refused("Could not write the plugin: ${it.message ?: it::class.java.simpleName}") }
    }

    fun delete(id: String): Boolean {
        val folder = folderFor(id) ?: return false
        return folder.deleteRecursively()
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }

    /** The folder for [id], or null when the id is not a plain name. Never leaves [root]. */
    private fun folderFor(id: String): File? {
        if (!ID.matches(id)) return null
        val folder = File(root, id)
        return folder.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }

    companion object {
        const val MANIFEST = "plugin.json"
        const val MAIN = "main.js"
        private val ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}
