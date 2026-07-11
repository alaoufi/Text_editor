package com.uts.editor.data

import android.content.ContentResolver
import android.net.Uri
import com.uts.editor.viewmodel.RichSpan
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Converts a .docx document into simple, self-contained HTML so it can be shown
 * with its structure preserved (paragraphs, headings, bold/italic/underline,
 * alignment, tables) — far closer to Word than flattened plain text.
 */
object DocxHtmlConverter {

    /**
     * The content of a .docx turned into something the plain-text editor can
     * hold WITH formatting: the text, character-range formatting spans, and
     * per-paragraph alignment (line index -> 0 start / 1 center / 2 end / 3
     * justify). Used by the "Edit" action so editing keeps the document's
     * structure and formatting instead of a jumbled text dump.
     */
    data class EditableDoc(
        val text: String,
        val spans: List<RichSpan>,
        val aligns: Map<Int, Int>,
    )

    /** Heading level -> font size (sp) so headings keep their visual weight. */
    private fun headingSize(level: Int): Float = when (level) {
        1 -> 28f; 2 -> 24f; 3 -> 20f; 4 -> 18f; 5 -> 16f; else -> 15f
    }

    /**
     * Build an [EditableDoc] from a .docx: paragraphs in document order (tables
     * as tab-separated cells / newline-separated rows), carrying bold, italic,
     * underline, heading sizes and paragraph alignment across into the editor.
     */
    fun toEditable(resolver: ContentResolver, uri: Uri): EditableDoc? {
        val xmlBytes = readZipEntry(resolver, uri, "word/document.xml") ?: return null
        val xml = String(xmlBytes, Charsets.UTF_8)
        val body = Regex("<w:body>([\\s\\S]*)</w:body>").find(xml)?.groupValues?.get(1) ?: xml

        val sb = StringBuilder()
        val spans = ArrayList<RichSpan>()
        val aligns = HashMap<Int, Int>()
        var lineCount = 0

        fun appendNewline() { sb.append('\n'); lineCount++ }

        // Append one run's text, recording a formatting span over it.
        fun appendRun(run: String, forceBold: Boolean, sizeSp: Float?) {
            val rpr = Regex("<w:rPr>[\\s\\S]*?</w:rPr>").find(run)?.value ?: ""
            var text = Regex("<w:t\\b[^>]*>([\\s\\S]*?)</w:t>").findAll(run)
                .joinToString("") { unescapeXml(it.groupValues[1]) }
            if (run.contains("<w:tab")) text += "\t"
            val hasBr = run.contains("<w:br") || run.contains("<w:cr")
            if (text.isNotEmpty()) {
                val start = sb.length
                sb.append(text)
                val end = sb.length
                val bold = forceBold || rpr.contains("<w:b/>") || rpr.contains("<w:b ")
                val italic = rpr.contains("<w:i/>") || rpr.contains("<w:i ")
                val underline = rpr.contains("<w:u ")
                if (bold || italic || underline || sizeSp != null) {
                    spans.add(RichSpan(start, end, bold = bold, italic = italic, underline = underline, sizeSp = sizeSp))
                }
            }
            if (hasBr) appendNewline()
        }

        fun appendParagraph(p: String) {
            val alignCode = when (Regex("<w:jc\\b[^>]*w:val=\"([^\"]*)\"").find(p)?.groupValues?.get(1)) {
                "center" -> 1
                "both", "distribute" -> 3
                "left" -> 2          // visually opposite of the RTL start side
                "right", "end" -> 0
                else -> 0
            }
            val heading = Regex("<w:pStyle\\b[^>]*w:val=\"Heading(\\d)\"")
                .find(p)?.groupValues?.get(1)?.toIntOrNull()
            val size = heading?.let { headingSize(it) }
            if (alignCode != 0) aligns[lineCount] = alignCode
            for (r in Regex("<w:r\\b[\\s\\S]*?</w:r>").findAll(p)) {
                appendRun(r.value, forceBold = heading != null, sizeSp = size)
            }
            appendNewline()
        }

        fun appendParagraphs(segment: String) {
            for (m in Regex("<w:p\\b[\\s\\S]*?</w:p>").findAll(segment)) appendParagraph(m.value)
        }

        fun appendTable(tbl: String) {
            for (tr in Regex("<w:tr\\b[\\s\\S]*?</w:tr>").findAll(tbl)) {
                val cells = Regex("<w:tc\\b[\\s\\S]*?</w:tc>").findAll(tr.value).toList()
                cells.forEachIndexed { i, tc ->
                    for (r in Regex("<w:r\\b[\\s\\S]*?</w:r>").findAll(tc.value)) {
                        appendRun(r.value, forceBold = false, sizeSp = null)
                    }
                    if (i < cells.size - 1) sb.append('\t')
                }
                appendNewline()
            }
        }

        var last = 0
        for (m in Regex("<w:tbl>[\\s\\S]*?</w:tbl>").findAll(body)) {
            appendParagraphs(body.substring(last, m.range.first))
            appendTable(m.value)
            last = m.range.last + 1
        }
        appendParagraphs(body.substring(last))

        val text = sb.toString().trimEnd('\n')
        if (text.isBlank()) return null
        // Drop any spans that fell past the trimmed end.
        val clean = spans.filter { it.start < text.length }
            .map { if (it.end > text.length) it.copy(end = text.length) else it }
        return EditableDoc(text, clean, aligns)
    }

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
