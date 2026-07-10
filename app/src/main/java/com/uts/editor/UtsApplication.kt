package com.uts.editor

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class UtsApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Persist the stack trace of any uncaught crash so it can be shown (and
        // shared) on the next launch — invaluable for diagnosing device-only bugs.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText(
                    "v${BuildConfig.VERSION_NAME}\n${throwable}\n\n$sw"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }

        // PDFBox-Android needs its resource loader initialised once with a
        // context before any PDF is parsed (used for font handling).
        PDFBoxResourceLoader.init(applicationContext)
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
