package dev.localintelligence.core.skill

/**
 * The folder name a skill is stored under. A skill's `name` comes from text
 * someone pasted or downloaded, so it is reduced to lowercase letters, digits
 * and dashes before it ever becomes a path. `../../settings` cannot survive
 * this, which is the point of doing it in :core where every caller goes through it.
 */
object SkillSlug {
    const val MAX_LENGTH = 48

    fun of(name: String): String = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(MAX_LENGTH)
        .trim('-')
}
