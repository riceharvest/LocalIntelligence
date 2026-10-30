package dev.localintelligence.android.skills

import dev.localintelligence.core.skill.Skill
import dev.localintelligence.core.skill.SkillParser
import dev.localintelligence.core.skill.SkillSlug
import java.io.File

/** An installed skill and the folder name it is stored under. */
data class InstalledSkill(val slug: String, val skill: Skill)

/**
 * Skills on disk: `<root>/<slug>/SKILL.md`, one folder each.
 *
 * Nothing here decides whether a skill is wise to follow; it only stores what
 * the user approved. Every write goes through [save], which parses first, so a
 * file that is not a usable skill is never written.
 */
class SkillRepository(private val root: File) {

    sealed interface SaveResult {
        data class Saved(val slug: String) : SaveResult
        data class Refused(val reason: String) : SaveResult
    }

    /** Usable skills only. A folder with a broken SKILL.md is skipped, not fatal. */
    fun list(): List<InstalledSkill> {
        val folders = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return folders.mapNotNull { readSkill(it) }.sortedBy { it.skill.name.lowercase() }
    }

    fun save(text: String, overwrite: Boolean = false): SaveResult {
        val skill = when (val parsed = SkillParser.parse(text)) {
            is SkillParser.Parsed.Rejected -> return SaveResult.Refused(parsed.reason)
            is SkillParser.Parsed.Ok -> parsed.skill
        }
        val slug = SkillSlug.of(skill.name)
        val folder = folderFor(slug) ?: return SaveResult.Refused("That name does not make a folder name.")
        if (folder.exists() && !overwrite) {
            if (readSkill(folder) != null) {
                return SaveResult.Refused("A skill called \"${skill.name}\" is already installed. Change its name: line, or delete the old one first.")
            }
            // A leftover from a failed save: list() hides it, so clear it rather than block the name.
            folder.deleteRecursively()
        }
        // Only a folder this call creates is removed on failure. An overwrite must not wipe the old skill.
        val createdHere = !folder.exists()
        return runCatching {
            folder.mkdirs()
            val target = File(folder, FILE)
            val temp = File(folder, "$FILE.tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
            SaveResult.Saved(slug)
        }.getOrElse {
            // A folder this call made is removed, so a failed write does not block the name next time.
            if (createdHere) folder.deleteRecursively()
            SaveResult.Refused("Could not write the skill: ${it.message ?: it::class.java.simpleName}")
        }
    }

    /** The skill in [folder], or null when the folder holds no usable SKILL.md. */
    private fun readSkill(folder: File): InstalledSkill? {
        val file = File(folder, FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return when (val parsed = SkillParser.parse(text)) {
            is SkillParser.Parsed.Ok -> InstalledSkill(folder.name, parsed.skill)
            is SkillParser.Parsed.Rejected -> null
        }
    }

    fun delete(slug: String): Boolean {
        val folder = folderFor(slug) ?: return false
        return folder.deleteRecursively()
    }

    /** The folder for [slug], or null when the slug is not a plain name. Never leaves [root]. */
    private fun folderFor(slug: String): File? {
        if (slug.isEmpty() || slug != SkillSlug.of(slug)) return null
        val folder = File(root, slug)
        return folder.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }

    companion object {
        const val FILE = "SKILL.md"
    }
}
