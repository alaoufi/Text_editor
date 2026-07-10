package com.uts.editor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Renders PDF pages to bitmaps using the platform [PdfRenderer], so image-only
 * (scanned) PDFs — which have no extractable text — can still be viewed.
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

/** A live PDF handle. Not thread-safe internally, so [render] is synchronized. */
class PdfPages(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
    private val file: File,
) {
    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun render(index: Int, widthPx: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val w = widthPx.coerceAtLeast(1)
            val scale = w.toFloat() / page.width
            val h = (page.height * scale).toInt().coerceIn(1, 8000)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        runCatching { file.delete() }
    }
}
