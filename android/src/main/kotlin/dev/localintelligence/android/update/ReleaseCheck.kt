package dev.localintelligence.android.update

import dev.localintelligence.android.tools.web.exchangeJson
import dev.localintelligence.core.tool.CancellationSignal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

// ===========================================================================
// The "Check for updates" row of the full build (app/src/full) asks GitHub for the latest
// release. The HTTP helper is internal to this module, so the request and the parse live
// here and the row calls them.
//
// WHY THE LINK IS CHECKED: the release page address comes from the reply, so it is used only
// when it points at this repository. Anything else falls back to the fixed releases page.
//
// WHY A SUFFIX IS CUT: the full build's version name is "0.1.0-full" (versionNameSuffix in
// app/build.gradle.kts). Comparing that text would call every release newer, so the suffix
// is dropped before the numbers are compared.
// ===========================================================================

private const val LATEST_RELEASE_API = "https://api.github.com/repos/riceharvest/LocalIntelligence/releases/latest"
private const val RELEASE_PAGE_PREFIX = "https://github.com/riceharvest/LocalIntelligence/"
private const val RELEASES_PAGE = "https://github.com/riceharvest/LocalIntelligence/releases/latest"
private const val USER_AGENT = "LocalIntelligence/1.0 (update check)"

/** The answer from GitHub's latest-release endpoint. */
sealed interface LatestRelease {
    /** A published release. [notes] is the release body as GitHub sent it, in Markdown. */
    class Found(val tag: String, val notes: String, val pageUrl: String) : LatestRelease

    /** GitHub answered 404: the repository has no published release yet. */
    data object NoReleases : LatestRelease

    /** No network, a non-2xx status (a redirect is one: redirects are not followed), or a reply that is not a release. */
    data object Failed : LatestRelease
}

/**
 * GET the latest release. Blocking: call it on Dispatchers.IO. Never throws; every failure
 * is [LatestRelease.Failed].
 */
fun fetchLatestRelease(signal: CancellationSignal): LatestRelease {
    val reply = try {
        exchangeJson("GET", LATEST_RELEASE_API, emptyMap(), null, USER_AGENT, signal, timeoutMs = 8_000 to 10_000)
    } catch (e: IOException) {
        return LatestRelease.Failed
    }
    if (reply.status == 404) return LatestRelease.NoReleases
    if (!reply.ok) return LatestRelease.Failed
    return try {
        val release = Json.parseToJsonElement(reply.body).jsonObject
        val tag = release.text("tag_name") ?: return LatestRelease.Failed
        val page = release.text("html_url")?.takeIf { it.startsWith(RELEASE_PAGE_PREFIX) } ?: RELEASES_PAGE
        LatestRelease.Found(tag = tag, notes = release.text("body").orEmpty(), pageUrl = page)
    } catch (e: IllegalArgumentException) {
        LatestRelease.Failed
    }
}

/**
 * True when [remote] is a newer version than [installed], false when it is the same or older,
 * and null when either one has no leading number. A leading "v" and anything after a "-" or
 * "+" are ignored, so "v0.2.0" compares with "0.1.0-full" as 0.2.0 against 0.1.0.
 */
fun versionIsNewer(remote: String, installed: String): Boolean? {
    val a = numbersOf(remote) ?: return null
    val b = numbersOf(installed) ?: return null
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun numbersOf(version: String): List<Int>? {
    val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    return core.split('.').map { it.toIntOrNull() ?: return null }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
