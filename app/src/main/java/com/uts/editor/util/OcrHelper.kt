package com.uts.editor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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

    /** Width (px) each page is rendered at before OCR — higher = more accurate, slower.
     *  Dense/coloured tables (e.g. an Excel sheet exported to PDF) need the detail. */
    private const val OCR_WIDTH = 2600

    /**
     * Flatten a page to high-contrast grayscale over a white background before
     * OCR. Coloured cell fills (common in spreadsheets) otherwise confuse the
     * recogniser's binarisation and it reads almost nothing; removing colour and
     * boosting contrast makes the dark text stand out.
     */
    private fun preprocess(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val matrix = ColorMatrix().apply { setSaturation(0f) } // grayscale
        val contrast = 1.7f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

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
            val raw = pages.renderForOcr(pageIndex, OCR_WIDTH) ?: return ""
            val bmp = preprocess(raw)
            raw.recycle()
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
                val raw = runCatching { pages.renderForOcr(i, OCR_WIDTH) }.getOrNull()
                if (raw != null) {
                    val bmp = preprocess(raw)
                    raw.recycle()
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
