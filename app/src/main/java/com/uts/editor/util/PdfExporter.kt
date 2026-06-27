package com.uts.editor.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.File
import java.io.OutputStream

/**
 * Renders document text into a paginated PDF. Uses [StaticLayout] with a
 * first-strong text-direction heuristic so Arabic (RTL) and English (LTR)
 * lines, and mixed lines, lay out correctly without flipped digits or brackets.
 */
object PdfExporter {

    // A4 @ 72dpi, in points.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36

    fun render(text: String, fontSize: Float = 11f): PdfDocument {
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = fontSize
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val contentWidth = PAGE_W - MARGIN * 2
        val contentHeight = PAGE_H - MARGIN * 2

        val layout = buildLayout(text, paint, contentWidth)
        val doc = PdfDocument()

        var line = 0
        var pageNumber = 1
        val lineCount = layout.lineCount
        if (lineCount == 0) {
            // Always emit at least one (blank) page.
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            doc.finishPage(page)
            return doc
        }
        while (line < lineCount) {
            val pageTop = layout.getLineTop(line)
            var endLine = line
            while (endLine < lineCount && layout.getLineBottom(endLine) - pageTop <= contentHeight) {
                endLine++
            }
            if (endLine == line) endLine = line + 1 // guarantee progress for an over-tall line

            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
            val page = doc.startPage(pageInfo)
            val canvas: Canvas = page.canvas
            canvas.save()
            canvas.translate(MARGIN.toFloat(), MARGIN.toFloat() - pageTop)
            canvas.clipRect(
                Rect(0, pageTop, contentWidth, pageTop + contentHeight)
            )
            layout.draw(canvas)
            canvas.restore()
            doc.finishPage(page)

            line = endLine
            pageNumber++
        }
        return doc
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        }
    }

    /** Export to a file in the app cache (for sharing / saving via SAF). */
    fun exportToCache(context: Context, baseName: String, text: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${baseName.substringBeforeLast('.')}.pdf")
        val doc = render(text)
        file.outputStream().use { writeTo(doc, it) }
        return file
    }

    fun writeTo(doc: PdfDocument, out: OutputStream) {
        try {
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
