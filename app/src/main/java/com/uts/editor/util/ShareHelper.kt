package com.uts.editor.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Builds share intents backed by FileProvider so other apps can read the file. */
object ShareHelper {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    fun shareText(context: Context, fileName: String, text: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName.ifBlank { "document.txt" })
        file.writeText(text)
        shareFile(context, file, "text/plain")
    }

    fun sharePdf(context: Context, file: File) {
        shareFile(context, file, "application/pdf")
    }

    private fun shareFile(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
