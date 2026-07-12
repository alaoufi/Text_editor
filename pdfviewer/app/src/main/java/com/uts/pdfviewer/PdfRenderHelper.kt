package com.uts.pdfviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Renders PDF pages to bitmaps using the platform [PdfRenderer]. No third-party
 * library — this is all the app needs to display a PDF.
 */
object PdfRenderHelper {

    /** Copy the PDF to a private cache file (PdfRenderer needs a seekable fd) and open it. */
    fun open(context: Context, uri: Uri): PdfPages? = runCatching {
        val dir = File(context.cacheDir, "pdfview").apply { mkdirs() }
        val file = File(dir, "view.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { input.copyTo(it) }
        } ?: return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfPages(PdfRenderer(pfd), pfd, file)
    }.getOrNull()
}

/** A live PDF handle. Access is synchronized (PdfRenderer is not thread-safe). */
class PdfPages(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
    private val file: File,
) {
    // Captured once at construction: reading it later can touch a closed renderer.
    val pageCount: Int = renderer.pageCount

    private var closed = false

    @Volatile
    var lastError: String? = null
        private set

    /** Render a page, or null if the document has been closed or the page fails. */
    @Synchronized
    fun render(index: Int, widthPx: Int): Bitmap? {
        if (closed) { lastError = "closed"; return null }
        if (index < 0 || index >= pageCount) { lastError = "bad index"; return null }
        val page = try {
            renderer.openPage(index)
        } catch (e: Throwable) {
            lastError = "openPage: ${e.javaClass.simpleName}"; return null
        }
        try {
            val pw = if (page.width > 0) page.width else 1
            val ph = if (page.height > 0) page.height else 1
            var w = widthPx.coerceIn(1, MAX_DIM)
            var h = (ph * (w.toFloat() / pw)).toInt().coerceIn(1, MAX_DIM)
            if (w * h > 4_000_000) {
                val s = kotlin.math.sqrt(4_000_000f / (w * h))
                w = (w * s).toInt().coerceAtLeast(1)
                h = (h * s).toInt().coerceAtLeast(1)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            lastError = null
            return bmp
        } catch (e: Throwable) {
            lastError = "render: ${e.javaClass.simpleName}"; return null
        } finally {
            runCatching { page.close() }
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        runCatching { file.delete() }
    }

    private companion object {
        const val MAX_DIM = 2048
    }
}
