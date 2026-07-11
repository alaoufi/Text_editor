package com.uts.editor.data

import android.content.ContentResolver
import android.net.Uri
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the editor's tab-separated table back into a real .xlsx file with NO
 * third-party library. An .xlsx is an OPC ZIP of XML parts, so we assemble the
 * minimal valid workbook ourselves. Each editor line becomes a row and each
 * tab-separated field a cell; fields that are plain numbers are written as
 * numeric cells, everything else as inline strings (so Arabic text is exact).
 */
object XlsxWriter {

    fun write(resolver: ContentResolver, uri: Uri, text: String) {
        val sheet = buildSheet(text)
        resolver.openOutputStream(uri, "wt").use { os ->
            requireNotNull(os) { "Cannot open output stream" }
            ZipOutputStream(os).use { zip ->
                zip.putEntry("[Content_Types].xml", CONTENT_TYPES)
                zip.putEntry("_rels/.rels", RELS)
                zip.putEntry("xl/workbook.xml", WORKBOOK)
                zip.putEntry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
                zip.putEntry("xl/worksheets/sheet1.xml", sheet)
            }
        }
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildSheet(text: String): String {
        val sb = StringBuilder(XML_DECL)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        if (text.isNotEmpty()) {
            text.split('\n').forEachIndexed { r, line ->
                val rowNum = r + 1
                sb.append("<row r=\"$rowNum\">")
                line.split('\t').forEachIndexed { c, value ->
                    if (value.isEmpty()) return@forEachIndexed
                    val ref = colName(c) + rowNum
                    if (isNumber(value)) {
                        sb.append("<c r=\"$ref\"><v>$value</v></c>")
                    } else {
                        sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>")
                    }
                }
                sb.append("</row>")
            }
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** True only when the value is a plain number whose canonical form is itself
     *  (so leading zeros, "+" signs and long IDs stay as text, not mangled). */
    private fun isNumber(s: String): Boolean {
        if (s.isEmpty() || s.length > 15) return false
        s.toLongOrNull()?.let { return it.toString() == s }
        val d = s.toDoubleOrNull() ?: return false
        return d.toString() == s
    }

    /** 0 -> "A", 25 -> "Z", 26 -> "AA", ... */
    private fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private val CONTENT_TYPES = XML_DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "</Types>"

    private val RELS = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private val WORKBOOK = XML_DECL +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private val WORKBOOK_RELS = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "</Relationships>"
}
