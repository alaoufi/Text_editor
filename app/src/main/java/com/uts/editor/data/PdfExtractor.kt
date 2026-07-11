package com.uts.editor.data

/**
 * PDF handling is done by rendering each page to an image with the platform's
 * native `android.graphics.pdf.PdfRenderer` (see PdfRenderHelper) — this matches
 * a real PDF reader's layout exactly, and scanned pages become editable text via
 * on-device OCR. No third-party PDF text-extraction library is bundled, keeping
 * the app small without sacrificing fidelity.
 */
object PdfExtractor {

    /** True for names this app treats as PDFs. */
    fun isPdf(name: String): Boolean = name.lowercase().endsWith(".pdf")
}
