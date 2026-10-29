package dev.localintelligence.core.skill

/**
 * Reads and writes SKILL.md text: a `---` frontmatter block with `name:` and
 * `description:`, then markdown.
 *
 * The format is the one Google AI Edge Gallery, Hermes Agent and pi share, so
 * files from any of them load. The reader is tolerant on purpose: a skill
 * written by hand or by another tool has unknown keys (`license:`,
 * `metadata:` with nested lines), folded or literal multi-line values,
 * quoted values, a BOM, or CRLF line endings. Those are accepted, not
 * rejected, because rejecting a usable skill over its metadata is the
 * failure a user would notice. What is rejected is what the app cannot use:
 * no frontmatter, or no name or description.
 *
 * The writer always quotes `name` and `description`, escaping `\` and `"`, so
 * a task that contains a colon or a quote cannot break the frontmatter.
 */
object SkillParser {

    sealed interface Parsed {
        data class Ok(val skill: Skill) : Parsed
        data class Rejected(val reason: String) : Parsed
    }

    fun parse(raw: String): Parsed {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trimStart()
        val lines = text.split('\n')
        if (lines.firstOrNull()?.trim() != "---") {
            return Parsed.Rejected("A skill starts with a --- line, then name: and description:, then another --- line.")
        }
        val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: -1
        if (close < 0) {
            return Parsed.Rejected("The frontmatter is not closed. Add a --- line after description:.")
        }

        val values = HashMap<String, String>()
        var i = 1
        while (i < close) {
            val line = lines[i]
            i++
            if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            val wantsContinuation = key == "name" || key == "description"
            if (value == ">" || value == "|" || value == ">-" || value == "|-" || (value.isEmpty() && wantsContinuation)) {
                // Folded or literal block, or a plain value on the next lines:
                // the indented lines under the key are its value.
                val parts = ArrayList<String>()
                while (i < close && (lines[i].isBlank() || lines[i].first().isWhitespace())) {
                    if (lines[i].isNotBlank()) parts += lines[i].trim()
                    i++
                }
                value = parts.joinToString(" ")
            }
            if (key !in values) values[key] = unquote(value)
        }

        val name = values["name"]?.trim().orEmpty()
        val description = values["description"]?.trim().orEmpty()
        if (name.isEmpty()) return Parsed.Rejected("The frontmatter has no name: line.")
        if (description.isEmpty()) return Parsed.Rejected("The frontmatter has no description: line.")
        if (SkillSlug.of(name).isEmpty()) {
            return Parsed.Rejected("The name must contain at least one letter or digit.")
        }
        val body = lines.drop(close + 1).joinToString("\n").trim()
        return Parsed.Ok(Skill(name = name, description = description, body = body))
    }

    /** The canonical text for [skill]: what the editor shows and what is written to disk. */
    fun render(name: String, description: String, body: String): String = buildString {
        append("---\n")
        append("name: ").append(quote(name)).append('\n')
        append("description: ").append(quote(description)).append('\n')
        append("---\n\n")
        append(body.trim()).append('\n')
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun unquote(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        return value
    }
}
