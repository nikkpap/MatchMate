package gr.matchbell.app

import org.json.JSONObject

/** OTA source: the public GitHub repository. Releases are created by .github/workflows/release.yml. */
object UpdateSource {
    const val OWNER = "nikkpap"
    const val REPO = "MatchMate"
    const val LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val PAGE = "https://github.com/$OWNER/$REPO/releases/latest"
}

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
    val pageUrl: String
)

/** Version numbers "0.3.12", "v0.3.12", "0.3.12-preview" compared part by part. */
fun versionParts(value: String): List<Int> = value.trim().removePrefix("v").removePrefix("V")
    .substringBefore('-').substringBefore('+').split('.')
    .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

fun isNewerVersion(remote: String, local: String): Boolean {
    val a = versionParts(remote); val b = versionParts(local)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private val Sha = Regex("\\b[0-9a-fA-F]{64}\\b")

/**
 * Reads the GitHub "latest release" JSON. Returns null for drafts, prereleases
 * or a release without an .apk asset. The SHA-256 is taken from the release text
 * ("SHA-256: …"), written there by the workflow, and checked after download.
 */
fun parseRelease(text: String): UpdateInfo? = try {
    val o = JSONObject(text)
    if (o.optBoolean("draft") || o.optBoolean("prerelease")) null else {
        val assets = o.optJSONArray("assets")
        val apk = assets?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } }
        val url = apk?.text("browser_download_url")
        val version = o.text("tag_name") ?: o.text("name")
        if (apk == null || url == null || version == null || !url.startsWith("https://")) null
        else {
            val body = o.text("body") ?: ""
            UpdateInfo(version.removePrefix("v"), body.lines().filterNot { it.contains("SHA-256", true) }.joinToString("\n").trim(),
                url, apk.optLong("size", 0), Sha.find(body)?.value?.lowercase(),
                o.text("html_url") ?: UpdateSource.PAGE)
        }
    }
} catch (_: Exception) { null }
