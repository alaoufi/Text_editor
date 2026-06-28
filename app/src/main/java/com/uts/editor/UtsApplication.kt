package com.uts.editor

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class UtsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android needs its resource loader initialised once with a
        // context before any PDF is parsed (used for font handling).
        PDFBoxResourceLoader.init(applicationContext)
    }
}
