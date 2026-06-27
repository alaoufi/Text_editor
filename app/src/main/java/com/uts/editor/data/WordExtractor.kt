package com.uts.editor.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts plain text from Microsoft Word documents for *viewing and copying*
 * (not formatted editing). Deliberately dependency-free to keep the app light:
 *
 *  - .docx is an OPC ZIP; we read `word/document.xml` and strip the markup.
 *  - .doc (binary OLE2) has no light, exact parser, so we recover readable
 *    text runs (UTF-16LE — what modern Word writes, incl. Arabic) as a
 *    best-effort. Results may include minor noise for complex documents.
 */
object WordExtractor {

    /** True for names this extractor handles. */
    fun isWord(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".docx") || n.endsWith(".doc")
    }

    fun extract(resolver: ContentResolver, uri: Uri, name: String): String {
        return if (name.lowercase().endsWith(".docx")) extractDocx(resolver, uri)
        else extractDoc(resolver, uri)
    }

    // ---- DOCX (ZIP + XML) ----

    private fun extractDocx(resolver: ContentResolver, uri: Uri): String {
        val xmlBytes = readZipEntry(resolver, uri, "word/document.xml")
            ?: return "" // not a valid docx
        val xml = String(xmlBytes, Charsets.UTF_8)
        // Turn structural tags into whitespace, then drop all remaining tags.
        var s = xml
        s = s.replace(Regex("<w:tab\\b[^>]*/>"), "\t")
        s = s.replace(Regex("<w:br\\b[^>]*/?>"), "\n")
        s = s.replace(Regex("<w:cr\\b[^>]*/?>"), "\n")
        s = s.replace(Regex("</w:p>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        return unescapeXml(s).trimEnd('\n')
    }

    private fun readZipEntry(resolver: ContentResolver, uri: Uri, entryName: String): ByteArray? {
        resolver.openInputStream(uri).use { input ->
            input ?: return null
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == entryName) {
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf); if (n == -1) break; out.write(buf, 0, n)
                        }
                        return out.toByteArray()
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
        }
        return null
    }

    private fun unescapeXml(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 1)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val rep = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") ->
                            ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") ->
                            ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    // ---- DOC (binary OLE2) best-effort ----

    private const val MIN_RUN = 4

    private fun extractDoc(resolver: ContentResolver, uri: Uri): String {
        val bytes = resolver.openInputStream(uri).use { input ->
            input ?: return ""
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf); if (n == -1) break
                out.write(buf, 0, n); total += n
                if (total > 40 * 1024 * 1024) break // safety cap
            }
            out.toByteArray()
        }
        // Recover UTF-16LE text runs (Word stores body text as UTF-16LE).
        val best = listOf(recoverUtf16Runs(bytes, 0), recoverUtf16Runs(bytes, 1))
            .maxByOrNull { it.length } ?: ""
        return best.trim()
    }

    private fun recoverUtf16Runs(bytes: ByteArray, startParity: Int): String {
        val sb = StringBuilder()
        val run = StringBuilder()
        var i = startParity
        while (i + 1 < bytes.size) {
            val code = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            if (isReadable(code)) {
                run.append(code.toChar())
            } else {
                flushRun(run, sb)
            }
            i += 2
        }
        flushRun(run, sb)
        return sb.toString()
    }

    private fun isReadable(code: Int): Boolean =
        code in 0x0600..0x06FF || code in 0x0750..0x077F ||   // Arabic
            code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF ||  // Arabic presentation forms
            code == 0x09 || code == 0x0A || code == 0x0D ||      // whitespace
            code in 0x20..0x7E ||                                // ASCII printable
            code in 0xA0..0x24F                                  // Latin-1/extended

    private fun flushRun(run: StringBuilder, sb: StringBuilder) {
        if (run.length >= MIN_RUN && run.any { it.isLetterOrDigit() }) {
            sb.append(run).append('\n')
        }
        run.setLength(0)
    }
}
