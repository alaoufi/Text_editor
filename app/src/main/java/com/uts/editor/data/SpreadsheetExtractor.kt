package com.uts.editor.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts a readable, tab-separated text table from spreadsheets.
 *
 *  - .xlsx (modern Office Open XML) is parsed properly: shared strings, cell
 *    types, column positions and multiple sheets are all honoured, so numbers,
 *    text and Arabic content land in the right columns.
 *  - .xls (legacy binary BIFF) has no light exact parser, so readable text runs
 *    are recovered as a best-effort (strings come through; exact layout may not).
 */
object SpreadsheetExtractor {

    fun isSpreadsheet(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".xlsx") || n.endsWith(".xls")
    }

    fun extract(resolver: ContentResolver, uri: Uri, name: String): String =
        if (name.lowercase().endsWith(".xlsx")) extractXlsx(resolver, uri)
        else BinaryTextRecovery.recover(readAllBytes(resolver, uri))

    /** Render an .xlsx as an HTML table (preserves the grid); "" for legacy .xls. */
    fun toHtml(resolver: ContentResolver, uri: Uri, name: String): String {
        if (!name.lowercase().endsWith(".xlsx")) return ""
        val entries = readZipEntries(resolver, uri) { it.startsWith("xl/") }
        val shared = entries["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(String(it, Charsets.UTF_8)) } ?: emptyList()
        val sheetNames = entries["xl/workbook.xml"]
            ?.let { parseSheetNames(String(it, Charsets.UTF_8)) } ?: emptyList()
        val sheetFiles = entries.keys
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".xml").toIntOrNull() ?: 0 }
        val sb = StringBuilder()
        sheetFiles.forEachIndexed { i, sheetFile ->
            if (sheetFiles.size > 1) {
                sb.append("<h3>").append(escapeHtml(sheetNames.getOrNull(i) ?: "Sheet ${i + 1}")).append("</h3>")
            }
            sb.append(sheetToHtml(String(entries[sheetFile]!!, Charsets.UTF_8), shared))
        }
        // Right-to-left sheet when the content is predominantly Arabic.
        return wrapHtml(sb.toString(), TextDirection.dominant(shared.joinToString(" ")))
    }

    private fun sheetToHtml(xml: String, shared: List<String>): String {
        val rowRegex = Regex("<row\\b[^>]*>([\\s\\S]*?)</row>")
        val cellRegex = Regex("<c\\b([^>]*)>([\\s\\S]*?)</c>|<c\\b([^>]*)/>")
        val out = StringBuilder("<table>")
        for (row in rowRegex.findAll(xml)) {
            val cells = sortedMapOf<Int, String>()
            for (c in cellRegex.findAll(row.groupValues[1])) {
                val attrs = c.groupValues[1].ifEmpty { c.groupValues[3] }
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                val col = ref?.let { columnIndex(it) } ?: cells.size
                cells[col] = cellValue(attrs, c.groupValues[2], shared)
            }
            out.append("<tr>")
            val maxCol = cells.lastKey().takeIf { cells.isNotEmpty() } ?: -1
            for (col in 0..maxCol) {
                out.append("<td dir=\"auto\">").append(escapeHtml(cells[col] ?: "").ifEmpty { "&nbsp;" }).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
        return out.toString()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun wrapHtml(body: String, baseDir: String): String = """
        <!doctype html><html dir="$baseDir"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:sans-serif;padding:8px;color:#111;background:#fff}
        table{border-collapse:collapse;margin:6px 0}
        td{border:1px solid #999;padding:5px;white-space:nowrap}
        h3{margin:10px 0 4px}</style></head><body>$body</body></html>
    """.trimIndent()

    // ---- XLSX (ZIP + XML) ----

    private fun extractXlsx(resolver: ContentResolver, uri: Uri): String {
        val entries = readZipEntries(resolver, uri) { it.startsWith("xl/") }
        val shared = entries["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(String(it, Charsets.UTF_8)) } ?: emptyList()
        val sheetNames = entries["xl/workbook.xml"]
            ?.let { parseSheetNames(String(it, Charsets.UTF_8)) } ?: emptyList()
        val sheetFiles = entries.keys
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.substringAfter("sheet").substringBefore(".xml").toIntOrNull() ?: 0 }

        val sb = StringBuilder()
        sheetFiles.forEachIndexed { i, sheetFile ->
            if (sheetFiles.size > 1) {
                val title = sheetNames.getOrNull(i) ?: "Sheet ${i + 1}"
                sb.append("# ").append(title).append('\n')
            }
            sb.append(parseSheet(String(entries[sheetFile]!!, Charsets.UTF_8), shared))
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val siRegex = Regex("<si\\b[^>]*>([\\s\\S]*?)</si>")
        val tRegex = Regex("<t\\b[^>]*>([\\s\\S]*?)</t>")
        return siRegex.findAll(xml).map { si ->
            tRegex.findAll(si.groupValues[1]).joinToString("") { unescapeXml(it.groupValues[1]) }
        }.toList()
    }

    private fun parseSheetNames(xml: String): List<String> =
        Regex("<sheet\\b[^>]*\\bname=\"([^\"]*)\"").findAll(xml)
            .map { unescapeXml(it.groupValues[1]) }.toList()

    private fun parseSheet(xml: String, shared: List<String>): String {
        val rowRegex = Regex("<row\\b[^>]*>([\\s\\S]*?)</row>")
        val cellRegex = Regex("<c\\b([^>]*)>([\\s\\S]*?)</c>|<c\\b([^>]*)/>")
        val out = StringBuilder()
        for (row in rowRegex.findAll(xml)) {
            val cells = sortedMapOf<Int, String>()
            for (c in cellRegex.findAll(row.groupValues[1])) {
                val attrs = c.groupValues[1].ifEmpty { c.groupValues[3] }
                val body = c.groupValues[2]
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                val col = ref?.let { columnIndex(it) } ?: cells.size
                cells[col] = cellValue(attrs, body, shared)
            }
            if (cells.isEmpty()) { out.append('\n'); continue }
            val maxCol = cells.lastKey()
            for (col in 0..maxCol) {
                if (col > 0) out.append('\t')
                out.append(cells[col] ?: "")
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun cellValue(attrs: String, body: String, shared: List<String>): String {
        val type = Regex("t=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: ""
        val v = Regex("<v\\b[^>]*>([\\s\\S]*?)</v>").find(body)?.groupValues?.get(1)
        return when (type) {
            "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "inlineStr" -> Regex("<t\\b[^>]*>([\\s\\S]*?)</t>").findAll(body)
                .joinToString("") { unescapeXml(it.groupValues[1]) }
            "b" -> if (v == "1") "TRUE" else "FALSE"
            else -> unescapeXml(v ?: "")
        }
    }

    /** "A" -> 0, "B" -> 1, ... "Z" -> 25, "AA" -> 26, ... */
    private fun columnIndex(letters: String): Int {
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    // ---- shared helpers ----

    private fun readZipEntries(
        resolver: ContentResolver,
        uri: Uri,
        keep: (String) -> Boolean,
    ): Map<String, ByteArray> {
        val map = HashMap<String, ByteArray>()
        resolver.openInputStream(uri).use { input ->
            input ?: return map
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
        return map
    }

    private fun readAllBytes(resolver: ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri).use { input ->
            input ?: return ByteArray(0)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf); if (n == -1) break
                out.write(buf, 0, n); total += n
                if (total > 40 * 1024 * 1024) break
            }
            out.toByteArray()
        }

    private fun unescapeXml(s: String): String {
        if (s.indexOf('&') < 0) return s
        return s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("&#x([0-9A-Fa-f]+);")) { m ->
                m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            }
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            }
            .replace("&amp;", "&")
    }
}
