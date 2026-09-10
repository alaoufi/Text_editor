package com.uts.pdfviewer.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** معلومات التحديث المقروءة من version.json. */
data class UpdateInfo(val version: String, val build: Int, val url: String)

/**
 * خدمة التحديث: تقرأ version.json من إصدار latest (مع مصادر احتياطية)، وتقارن
 * **اسم النسخة** دلاليًّا (2.10 مقابل 2.9). كل شبكة تُستدعى من خيط خلفيّ.
 */
object UpdateService {

    private const val REPO = "alaoufi/Text_editor"
    // فرع نشر version.json الاحتياطيّ (raw / jsDelivr).
    private const val BRANCH = "claude/text-editor-app-hfafhv"

    // ترتيب المصادر: أصل latest ثم raw ثم jsDelivr.
    private val SOURCES = listOf(
        "https://github.com/$REPO/releases/download/latest/version.json",
        "https://raw.githubusercontent.com/$REPO/$BRANCH/pdfviewer/version.json",
        "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/pdfviewer/version.json",
    )

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0"

    /** يعيد UpdateInfo إن توفّرت نسخة أحدث، وإلا null. (استدعِه من خيط خلفيّ.) */
    fun check(context: Context): UpdateInfo? {
        val local = currentVersion(context)
        for (src in SOURCES) {
            val txt = fetch(src) ?: continue
            val info = runCatching {
                val j = JSONObject(txt)
                UpdateInfo(j.getString("version"), j.optInt("build", 0), j.getString("url"))
            }.getOrNull() ?: continue
            return if (isNewer(info.version, local)) info else null
        }
        return null
    }

    /** مقارنة دلاليّة لنسخ منقّطة: 2.10 > 2.9 (لا تعتمد على رقم البناء). */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun fetch(urlStr: String): String? = runCatching {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            instanceFollowRedirects = true; requestMethod = "GET"
        }
        try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }.getOrNull()

    /** ينزّل الـAPK إلى الكاش مع تقدّم (0..100). يعيد الملفّ أو null. (خيط خلفيّ.) */
    fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): File? = runCatching {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
        }
        try {
            if (c.responseCode !in 200..299) return null
            val total = c.contentLengthLong
            var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024); var read: Int; var sum = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read); sum += read
                        if (total > 0) {
                            val p = ((sum * 100) / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            onProgress(100)
            out
        } finally { c.disconnect() }
    }.getOrNull()
}
