package com.uts.editor.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub Releases for a newer build and downloads its APK
 * so the app can update itself without going through a browser/store.
 */
object UpdateChecker {

    private const val LATEST_URL =
        "https://api.github.com/repos/alaoufi/Text_editor/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val tagName: String,
        val apkUrl: String,
        val notes: String,
    )

    /** Returns release info when the latest release is newer than [currentVersion], else null. */
    fun check(currentVersion: String): UpdateInfo? {
        val json = httpGet(LATEST_URL) ?: return null
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name").ifEmpty { return null }
        if (!isNewer(tag, currentVersion)) return null
        val assets = obj.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                break
            }
        }
        val url = apkUrl?.takeIf { it.isNotEmpty() } ?: return null
        return UpdateInfo(tag.removePrefix("v"), tag, url, obj.optString("body"))
    }

    /** Download the APK to the cache and return the saved file. */
    fun downloadApk(context: Context, url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GlobalTextEditor")
        }
        try {
            conn.inputStream.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** Semantic-ish comparison: true when [remote] (e.g. "v1.7") is newer than [current]. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val c = current.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun httpGet(urlStr: String): String? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "GlobalTextEditor")
        }
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
