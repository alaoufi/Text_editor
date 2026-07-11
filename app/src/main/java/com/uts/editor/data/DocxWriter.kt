package com.uts.editor.data

import android.content.ContentResolver
import android.net.Uri
import com.uts.editor.viewmodel.RichSpan
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the editor's content back into a real .docx file — with NO third-party
 * library. A .docx is just an OPC ZIP of XML parts, so we assemble the minimal
 * valid package ourselves:
 *   [Content_Types].xml, _rels/.rels, word/document.xml
 *
 * The editor's structure is mapped to WordprocessingML: each line becomes a
 * paragraph, character-range formatting (bold/italic/underline/size/colour)
 * becomes runs with `w:rPr`, tabs become `w:tab`, and per-paragraph alignment
 * becomes `w:jc`. Grid tables are written as tab-separated text (tab stops)
 * rather than reconstructed `w:tbl` grids.
 */
object DocxWriter {

    fun write(
        resolver: ContentResolver,
        uri: Uri,
        text: String,
        spans: List<RichSpan>,
        aligns: Map<Int, Int>,
    ) {
        val body = buildBody(text, spans, aligns)
        val document = XML_DECL +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body<w:sectPr/></w:body></w:document>"
        resolver.openOutputStream(uri, "wt").use { os ->
            requireNotNull(os) { "Cannot open output stream" }
            ZipOutputStream(os).use { zip ->
                zip.putEntry("[Content_Types].xml", CONTENT_TYPES)
                zip.putEntry("_rels/.rels", RELS)
                zip.putEntry("word/document.xml", document)
            }
        }
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildBody(text: String, spans: List<RichSpan>, aligns: Map<Int, Int>): String {
        val sb = StringBuilder()
        var offset = 0
        text.split('\n').forEachIndexed { lineIndex, line ->
            sb.append(buildParagraph(line, offset, spans, aligns[lineIndex]))
            offset += line.length + 1 // account for the '\n' separator
        }
        return sb.toString()
    }

    private fun buildParagraph(line: String, lineOffset: Int, spans: List<RichSpan>, alignCode: Int?): String {
        // Mark Arabic paragraphs right-to-left so Microsoft Word (and any reader)
        // shows them correctly instead of defaulting to left-to-right.
        val rtl = TextDirection.isRtl(line)
        val jc = when (alignCode) {
            1 -> "center"; 2 -> "end"; 3 -> "both"; else -> null
        }
        val pPrInner = StringBuilder()
        if (rtl) pPrInner.append("<w:bidi/>")
        if (jc != null) pPrInner.append("<w:jc w:val=\"$jc\"/>")
        val pPr = if (pPrInner.isNotEmpty()) "<w:pPr>$pPrInner</w:pPr>" else ""
        if (line.isEmpty()) return "<w:p>$pPr</w:p>"
        return "<w:p>$pPr${buildRuns(line, lineOffset, spans, rtl)}</w:p>"
    }

    private data class Attr(
        val bold: Boolean, val italic: Boolean, val underline: Boolean,
        val size: Float?, val color: Int?,
    )

    /** Effective formatting at a global character offset (spans layer; later wins). */
    private fun attrAt(global: Int, spans: List<RichSpan>): Attr {
        var bold = false; var italic = false; var underline = false
        var size: Float? = null; var color: Int? = null
        for (sp in spans) {
            if (global >= sp.start && global < sp.end) {
                if (sp.bold) bold = true
                if (sp.italic) italic = true
                if (sp.underline) underline = true
                if (sp.sizeSp != null) size = sp.sizeSp
                if (sp.color != null) color = sp.color
            }
        }
        return Attr(bold, italic, underline, size, color)
    }

    /** Split a line into runs of uniform formatting. */
    private fun buildRuns(line: String, lineOffset: Int, spans: List<RichSpan>, rtl: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val a = attrAt(lineOffset + i, spans)
            var j = i + 1
            while (j < line.length && attrAt(lineOffset + j, spans) == a) j++
            sb.append(runXml(line.substring(i, j), a, rtl))
            i = j
        }
        return sb.toString()
    }

    private fun runXml(segment: String, a: Attr, rtl: Boolean): String {
        val rpr = StringBuilder()
        if (a.bold) rpr.append("<w:b/>")
        if (a.italic) rpr.append("<w:i/>")
        if (a.underline) rpr.append("<w:u w:val=\"single\"/>")
        if (a.color != null) rpr.append("<w:color w:val=\"${hex(a.color)}\"/>")
        if (a.size != null) {
            val half = (a.size * 2).toInt() // WordprocessingML sizes are half-points
            rpr.append("<w:sz w:val=\"$half\"/><w:szCs w:val=\"$half\"/>")
        }
        if (rtl) rpr.append("<w:rtl/>")
        val rprXml = if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else ""
        val content = StringBuilder()
        segment.split('\t').forEachIndexed { idx, piece ->
            if (idx > 0) content.append("<w:tab/>")
            if (piece.isNotEmpty()) content.append("<w:t xml:space=\"preserve\">${escape(piece)}</w:t>")
        }
        return "<w:r>$rprXml$content</w:r>"
    }

    private fun hex(color: Int): String = String.format("%06X", color and 0xFFFFFF)

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private val CONTENT_TYPES = XML_DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "</Types>"

    private val RELS = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"
}
