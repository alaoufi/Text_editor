package com.uts.editor.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Converts a .docx document into simple, self-contained HTML so it can be shown
 * with its structure preserved (paragraphs, headings, bold/italic/underline,
 * alignment, tables) — far closer to Word than flattened plain text.
 */
object DocxHtmlConverter {

    fun toHtml(resolver: ContentResolver, uri: Uri): String {
        val xmlBytes = readZipEntry(resolver, uri, "word/document.xml") ?: return ""
        val xml = String(xmlBytes, Charsets.UTF_8)
        val body = Regex("<w:body>([\\s\\S]*)</w:body>").find(xml)?.groupValues?.get(1) ?: xml
        val sb = StringBuilder()
        // Walk tables and the paragraphs between them in document order. Assumes
        // tables are not nested (the common case).
        var last = 0
        for (m in Regex("<w:tbl>[\\s\\S]*?</w:tbl>").findAll(body)) {
            sb.append(convertParagraphs(body.substring(last, m.range.first)))
            sb.append(convertTable(m.value))
            last = m.range.last + 1
        }
        sb.append(convertParagraphs(body.substring(last)))
        return wrap(sb.toString())
    }

    private fun convertParagraphs(segment: String): String {
        val out = StringBuilder()
        for (p in Regex("<w:p\\b[\\s\\S]*?</w:p>").findAll(segment)) {
            out.append(convertParagraph(p.value))
        }
        return out.toString()
    }

    private fun convertParagraph(p: String): String {
        val inner = runsToHtml(p)
        val heading = Regex("<w:pStyle\\b[^>]*w:val=\"Heading(\\d)\"").find(p)?.groupValues?.get(1)?.toIntOrNull()
        val align = when (Regex("<w:jc\\b[^>]*w:val=\"([^\"]*)\"").find(p)?.groupValues?.get(1)) {
            "center" -> "center"
            "right", "end" -> "right"
            "both" -> "justify"
            "left", "start" -> "left"
            else -> null
        }
        val style = if (align != null) " style=\"text-align:$align\"" else ""
        if (inner.isBlank()) return "<p>&nbsp;</p>"
        return if (heading != null && heading in 1..6) "<h$heading$style>$inner</h$heading>"
        else "<p$style>$inner</p>"
    }

    private fun runsToHtml(scope: String): String {
        val out = StringBuilder()
        for (r in Regex("<w:r\\b[\\s\\S]*?</w:r>").findAll(scope)) {
            val run = r.value
            val rpr = Regex("<w:rPr>[\\s\\S]*?</w:rPr>").find(run)?.value ?: ""
            var text = Regex("<w:t\\b[^>]*>([\\s\\S]*?)</w:t>").findAll(run)
                .joinToString("") { escape(unescapeXml(it.groupValues[1])) }
            if (run.contains("<w:tab")) text += "&emsp;"
            if (run.contains("<w:br")) text += "<br/>"
            if (text.isEmpty()) continue
            if (rpr.contains("<w:b/>") || rpr.contains("<w:b ")) text = "<b>$text</b>"
            if (rpr.contains("<w:i/>") || rpr.contains("<w:i ")) text = "<i>$text</i>"
            if (rpr.contains("<w:u ")) text = "<u>$text</u>"
            out.append(text)
        }
        return out.toString()
    }

    private fun convertTable(tbl: String): String {
        val out = StringBuilder("<table>")
        for (tr in Regex("<w:tr\\b[\\s\\S]*?</w:tr>").findAll(tbl)) {
            out.append("<tr>")
            for (tc in Regex("<w:tc\\b[\\s\\S]*?</w:tc>").findAll(tr.value)) {
                out.append("<td>").append(convertParagraphs(tc.value).ifBlank { "&nbsp;" }).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
        return out.toString()
    }

    private fun wrap(bodyHtml: String): String = """
        <!doctype html><html dir="auto"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:sans-serif;line-height:1.7;padding:12px;color:#111;background:#fff;word-wrap:break-word}
          p{margin:0 0 8px}
          table{border-collapse:collapse;width:100%;margin:8px 0}
          td{border:1px solid #999;padding:6px;vertical-align:top}
          h1,h2,h3,h4,h5,h6{margin:10px 0 6px}
        </style></head><body>$bodyHtml</body></html>
    """.trimIndent()

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescapeXml(s: String): String {
        if (s.indexOf('&') < 0) return s
        return s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }

    private fun readZipEntry(resolver: ContentResolver, uri: Uri, entryName: String): ByteArray? {
        resolver.openInputStream(uri).use { input ->
            input ?: return null
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                val buf = ByteArray(64 * 1024)
                while (e != null) {
                    if (e.name == entryName) {
                        val out = ByteArrayOutputStream()
                        while (true) { val n = zip.read(buf); if (n == -1) break; out.write(buf, 0, n) }
                        return out.toByteArray()
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
        }
        return null
    }
}
