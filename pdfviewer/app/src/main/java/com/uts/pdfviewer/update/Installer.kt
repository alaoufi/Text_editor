package com.uts.pdfviewer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * المثبّت الأصليّ (بديل MethodChannel في Flutter): يفتح مثبّت النظام لتركيب الـAPK
 * فوق النسخة القديمة. يتطلّب إذن «تثبيت تطبيقات غير معروفة» على أندرويد 8+.
 */
object Installer {

    /** هل يُسمح للتطبيق بطلب تثبيت الحِزم؟ */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.packageManager.canRequestPackageInstalls()
        else true

    /** يفتح شاشة منح إذن «تثبيت تطبيقات غير معروفة» لهذا التطبيق. */
    fun openInstallSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val i = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
    }

    /** يشغّل مثبّت النظام على ملفّ الـAPK المنزَّل (عبر FileProvider). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(i) }
    }
}
