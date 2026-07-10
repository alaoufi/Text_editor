package com.uts.editor.util

import android.content.Context
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device OCR for scanned (image-only) PDFs. Renders each page with
 * [PdfRenderHelper] and recognises it with Tesseract. Arabic + English language
 * data is downloaded on first use into the app's private storage.
 */
object OcrHelper {

    private const val TESSDATA_BASE =
        "https://github.com/tesseract-ocr/tessdata_fast/raw/main/"

    /** Width (px) each page is rendered at before OCR — higher = more accurate, slower. */
    private const val OCR_WIDTH = 1400

    /** OCR a single page (fast). Returns the recognised text. */
    fun recognizePage(
        context: Context,
        uri: Uri,
        langs: String,
        pageIndex: Int,
    ): String {
        val dataPath = ensureLanguages(context, langs.split("+"))
        val pages = PdfRenderHelper.open(context, uri) ?: return ""
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dataPath.absolutePath, langs)) return ""
            // Full automatic page-segmentation improves reading order on multi-
            // column / structured pages compared with the single-block default.
            runCatching { tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO }
            val bmp = pages.render(pageIndex, OCR_WIDTH) ?: return ""
            tess.setImage(bmp)
            val text = tess.getUTF8Text() ?: ""
            bmp.recycle()
            return text.trim()
        } finally {
            runCatching { tess.recycle() }
            pages.close()
        }
    }

    /** Ensure the language data exists locally; returns the Tesseract data path. */
    fun ensureLanguages(context: Context, langs: List<String>): File {
        val tessdir = File(context.filesDir, "tessdata").apply { mkdirs() }
        for (lang in langs) {
            val f = File(tessdir, "$lang.traineddata")
            if (!f.exists() || f.length() == 0L) {
                download("$TESSDATA_BASE$lang.traineddata", f)
            }
        }
        return context.filesDir
    }

    /**
     * OCR every page of [uri]. [onProgress] reports (pageDone, pageTotal).
     * Returns the recognised text, pages separated by blank lines.
     */
    fun recognizePdf(
        context: Context,
        uri: Uri,
        langs: String,
        onProgress: (Int, Int) -> Unit,
    ): String {
        val dataPath = ensureLanguages(context, langs.split("+"))
        val pages = PdfRenderHelper.open(context, uri) ?: return ""
        val tess = TessBaseAPI()
        val sb = StringBuilder()
        try {
            if (!tess.init(dataPath.absolutePath, langs)) return ""
            val total = pages.pageCount
            for (i in 0 until total) {
                val bmp = runCatching { pages.render(i, OCR_WIDTH) }.getOrNull()
                if (bmp != null) {
                    tess.setImage(bmp)
                    sb.append(tess.getUTF8Text() ?: "").append("\n\n")
                    bmp.recycle()
                }
                onProgress(i + 1, total)
            }
        } finally {
            runCatching { tess.recycle() }
            pages.close()
        }
        return sb.toString().trim()
    }

    private fun download(urlStr: String, dest: File) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GlobalTextEditor")
        }
        try {
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
        } finally {
            conn.disconnect()
        }
    }
}
