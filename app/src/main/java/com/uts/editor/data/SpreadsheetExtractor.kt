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
