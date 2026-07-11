package com.uts.editor.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
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

    /** Max width/height (px) an embedded image is downscaled to for the view. */
    private const val MAX_IMG_DIM = 1400

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
        // Whether the document as a whole reads right-to-left (Arabic). Used to
        // give Latin/number-only paragraphs the correct alignment in the RTL
        // editor instead of leaving them stuck on the right.
        val docRtl = TextDirection.isRtl(plainText(body))

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
                val colorInt = Regex("<w:color\\b[^>]*w:val=\"([0-9A-Fa-f]{6})\"").find(rpr)?.groupValues?.get(1)
                    ?.let { runCatching { (0xFF shl 24) or it.toInt(16) }.getOrNull() }
                if (bold || italic || underline || sizeSp != null || colorInt != null) {
                    spans.add(RichSpan(start, end, bold = bold, italic = italic, underline = underline, sizeSp = sizeSp, color = colorInt))
                }
            }
            if (hasBr) appendNewline()
        }

        fun appendParagraph(p: String) {
            val jc = Regex("<w:jc\\b[^>]*w:val=\"([^\"]*)\"").find(p)?.groupValues?.get(1)
            val alignCode = when (jc) {
                "center" -> 1
                "both", "distribute" -> 3
                "left" -> 2          // visually opposite of the RTL start side
                "right", "end" -> 0
                else -> {
                    // No explicit Word alignment. In an RTL document, a paragraph
                    // that is itself Latin/number text (a title, a number, a code)
                    // should sit on the left (End) rather than the RTL start side.
                    val ptext = Regex("<w:t\\b[^>]*>([\\s\\S]*?)</w:t>").findAll(p)
                        .joinToString("") { unescapeXml(it.groupValues[1]) }
                    if (docRtl && ptext.isNotBlank() && !TextDirection.isRtl(ptext)) 2 else 0
                }
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
        // Read the main part, its relationships and any embedded images in one
        // pass so pictures can be inlined as data URIs (a .docx keeps images in
        // word/media/, referenced by relationship id).
        val entries = readEntries(resolver, uri) {
            it == "word/document.xml" || it == "word/_rels/document.xml.rels" || it.startsWith("word/media/")
        }
        val xmlBytes = entries["word/document.xml"] ?: return ""
        val xml = String(xmlBytes, Charsets.UTF_8)
        val body = Regex("<w:body>([\\s\\S]*)</w:body>").find(xml)?.groupValues?.get(1) ?: xml
        val images = buildImageMap(entries)
        val pageBg = Regex("<w:background\\b[^>]*w:color=\"([0-9A-Fa-f]{6})\"").find(xml)?.groupValues?.get(1)
        val sb = StringBuilder()
        // Walk tables and the paragraphs between them in document order. Assumes
        // tables are not nested (the common case).
        var last = 0
        for (m in Regex("<w:tbl>[\\s\\S]*?</w:tbl>").findAll(body)) {
            sb.append(convertParagraphs(body.substring(last, m.range.first), images))
            sb.append(convertTable(m.value, images))
            last = m.range.last + 1
        }
        sb.append(convertParagraphs(body.substring(last), images))
        val bodyHtml = sb.toString()
        return wrap(bodyHtml, TextDirection.dominant(plainText(body)), pageBg)
    }

    /** relationship id -> inline "data:image/...;base64,..." for each embedded picture. */
    private fun buildImageMap(entries: Map<String, ByteArray>): Map<String, String> {
        val rels = entries["word/_rels/document.xml.rels"]?.let { String(it, Charsets.UTF_8) } ?: return emptyMap()
        val map = HashMap<String, String>()
        for (rel in Regex("<Relationship\\b[^>]*/?>").findAll(rels)) {
            val tag = rel.value
            if (!tag.contains("/image")) continue
            val id = Regex("Id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: continue
            var target = Regex("Target=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: continue
            target = target.removePrefix("/word/").removePrefix("../").removePrefix("/")
            val path = if (target.startsWith("word/")) target else "word/$target"
            val bytes = entries[path] ?: entries["word/${target.substringAfterLast("../")}"] ?: continue
            // Downscale/re-encode before embedding: full-size photos as base64
            // bloat the HTML to megabytes, which can overwhelm the WebView (or
            // force a fall back to plain text). A capped JPEG keeps it light.
            downscaleToDataUri(bytes)?.let { map[id] = it }
        }
        return map
    }

    private fun downscaleToDataUri(bytes: ByteArray): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMG_DIM || bounds.outHeight / sample > MAX_IMG_DIM) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    /** All visible run text of the document, for base-direction detection. */
    private fun plainText(body: String): String =
        Regex("<w:t\\b[^>]*>([\\s\\S]*?)</w:t>").findAll(body)
            .joinToString(" ") { unescapeXml(it.groupValues[1]) }

    private fun convertParagraphs(segment: String, images: Map<String, String>): String {
        val out = StringBuilder()
        for (p in Regex("<w:p\\b[\\s\\S]*?</w:p>").findAll(segment)) {
            out.append(convertParagraph(p.value, images))
        }
        return out.toString()
    }

    private fun convertParagraph(p: String, images: Map<String, String>): String {
        val inner = runsToHtml(p, images)
        val heading = Regex("<w:pStyle\\b[^>]*w:val=\"Heading(\\d)\"").find(p)?.groupValues?.get(1)?.toIntOrNull()
        val align = when (Regex("<w:jc\\b[^>]*w:val=\"([^\"]*)\"").find(p)?.groupValues?.get(1)) {
            "center" -> "center"
            "right", "end" -> "right"
            "both" -> "justify"
            "left", "start" -> "left"
            else -> null
        }
        val style = if (align != null) " style=\"text-align:$align\"" else ""
        // Each block carries dir="auto" so it resolves its OWN direction — an
        // Arabic paragraph stays right-to-left even inside a document whose first
        // characters are Latin/digits (which would otherwise flip everything).
        if (inner.isBlank()) return "<p dir=\"auto\">&nbsp;</p>"
        return if (heading != null && heading in 1..6) "<h$heading dir=\"auto\"$style>$inner</h$heading>"
        else "<p dir=\"auto\"$style>$inner</p>"
    }

    private fun runsToHtml(scope: String, images: Map<String, String>): String {
        val out = StringBuilder()
        for (r in Regex("<w:r\\b[\\s\\S]*?</w:r>").findAll(scope)) {
            val run = r.value
            // Inline any embedded picture (w:drawing / VML) referenced by this run.
            if (run.contains("<w:drawing") || run.contains("<w:pict") || run.contains("<w:object")) {
                val relId = Regex("r:embed=\"([^\"]+)\"").find(run)?.groupValues?.get(1)
                    ?: Regex("r:id=\"([^\"]+)\"").find(run)?.groupValues?.get(1)
                images[relId]?.let { out.append("<img src=\"$it\" style=\"max-width:100%;height:auto\"/>") }
            }
            val rpr = Regex("<w:rPr>[\\s\\S]*?</w:rPr>").find(run)?.value ?: ""
            var text = Regex("<w:t\\b[^>]*>([\\s\\S]*?)</w:t>").findAll(run)
                .joinToString("") { escape(unescapeXml(it.groupValues[1])) }
            if (run.contains("<w:tab")) text += "&emsp;"
            if (run.contains("<w:br")) text += "<br/>"
            if (text.isEmpty()) continue
            if (rpr.contains("<w:b/>") || rpr.contains("<w:b ")) text = "<b>$text</b>"
            if (rpr.contains("<w:i/>") || rpr.contains("<w:i ")) text = "<i>$text</i>"
            if (rpr.contains("<w:u ")) text = "<u>$text</u>"
            // Run text colour (skip "auto"/black-by-default so themes still adapt).
            val color = Regex("<w:color\\b[^>]*w:val=\"([0-9A-Fa-f]{6})\"").find(rpr)?.groupValues?.get(1)
            if (color != null && !color.equals("auto", true)) text = "<span style=\"color:#$color\">$text</span>"
            // Highlight / cell-run shading -> background colour.
            val hi = Regex("<w:highlight\\b[^>]*w:val=\"([a-zA-Z]+)\"").find(rpr)?.groupValues?.get(1)
            if (hi != null && !hi.equals("none", true)) text = "<span style=\"background:$hi\">$text</span>"
            out.append(text)
        }
        return out.toString()
    }

    private fun convertTable(tbl: String, images: Map<String, String>): String {
        val out = StringBuilder("<table>")
        for (tr in Regex("<w:tr\\b[\\s\\S]*?</w:tr>").findAll(tbl)) {
            out.append("<tr>")
            for (tc in Regex("<w:tc\\b[\\s\\S]*?</w:tc>").findAll(tr.value)) {
                // Cell shading -> background colour.
                val shd = Regex("<w:shd\\b[^>]*w:fill=\"([0-9A-Fa-f]{6})\"").find(tc.value)?.groupValues?.get(1)
                val bg = if (shd != null && !shd.equals("auto", true) && !shd.equals("FFFFFF", true))
                    " style=\"background:#$shd\"" else ""
                out.append("<td dir=\"auto\"$bg>").append(convertParagraphs(tc.value, images).ifBlank { "&nbsp;" }).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
        return out.toString()
    }

    private fun wrap(bodyHtml: String, baseDir: String, bgColor: String?): String {
        val bg = if (bgColor != null) "#$bgColor" else "#fff"
        return """
        <!doctype html><html dir="$baseDir"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:sans-serif;line-height:1.7;padding:12px;color:#111;background:$bg;word-wrap:break-word}
          p{margin:0 0 8px}
          table{border-collapse:collapse;width:100%;margin:8px 0}
          td{border:1px solid #999;padding:6px;vertical-align:top}
          h1,h2,h3,h4,h5,h6{margin:10px 0 6px}
        </style></head><body>$bodyHtml</body></html>
        """.trimIndent()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescapeXml(s: String): String {
        if (s.indexOf('&') < 0) return s
        return s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }

    /** Read every entry matching [keep] from the .docx zip in a single pass. */
    private fun readEntries(
        resolver: ContentResolver,
        uri: Uri,
        keep: (String) -> Boolean,
    ): Map<String, ByteArray> {
        val map = HashMap<String, ByteArray>()
        resolver.openInputStream(uri).use { input ->
            input ?: return map
            // Tolerate a corrupt entry (e.g. a bad-CRC image): keep whatever was
            // read so far — document.xml/relationships come first, so text and
            // colours always survive even if a later picture fails.
            runCatching {
                ZipInputStream(input).use { zip ->
                    var e = zip.nextEntry
                    val buf = ByteArray(64 * 1024)
                    while (e != null) {
                        if (!e.isDirectory && keep(e.name)) {
                            val out = ByteArrayOutputStream()
                            while (true) { val n = zip.read(buf); if (n == -1) break; out.write(buf, 0, n) }
                            map[e.name] = out.toByteArray()
                        }
                        zip.closeEntry(); e = zip.nextEntry
                    }
                }
            }
        }
        return map
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
