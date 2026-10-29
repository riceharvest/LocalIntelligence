package dev.localintelligence.core.tool

/**
 * The word split the lexical scorers share: lowercase ASCII runs of three or
 * more characters. Shared so the tool selector and the skill selector score the
 * same way; a change here changes both, on purpose.
 */
internal fun lexicalTokens(text: String): List<String> =
    text.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 }
