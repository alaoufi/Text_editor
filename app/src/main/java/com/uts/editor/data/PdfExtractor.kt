package com.uts.editor.data

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Extracts plain text from PDF files for *viewing and copying* (not formatted
 * editing). Uses Apache PDFBox (Android port), which handles compressed content
 * streams and ToUnicode maps, so Arabic and other Unicode text is recovered
 * correctly rather than as garbled glyph codes.
 */
object PdfExtractor {

    /** True for names this extractor handles. */
    fun isPdf(name: String): Boolean = name.lowercase().endsWith(".pdf")

    /** Extract the document text in reading order, preserving line breaks. */
    fun extract(resolver: ContentResolver, uri: Uri): String {
        resolver.openInputStream(uri).use { input ->
            input ?: return ""
            PDDocument.load(input).use { document ->
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    lineSeparator = "\n"
                    paragraphEnd = "\n"
                }
                return stripper.getText(document).trimEnd('\n')
            }
        }
    }
}
