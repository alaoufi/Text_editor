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
            var w = widthPx.coerceIn(1, MAX_DIM)
            var h = (page.height * (w.toFloat() / page.width)).toInt().coerceAtLeast(1)
            // Keep both dimensions within the GPU texture limit so drawing the
            // bitmap never crashes with "bitmap too large to be uploaded".
            if (h > MAX_DIM) {
                val s = MAX_DIM.toFloat() / h
                w = (w * s).toInt().coerceAtLeast(1)
                h = MAX_DIM
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    private companion object {
        /** Conservative max texture dimension supported by essentially all GPUs. */
        const val MAX_DIM = 2048
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        runCatching { file.delete() }
    }
}
