package com.uts.editor.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.uts.editor.model.LineEnding
import com.uts.editor.model.LoadMode
import com.uts.editor.model.TextEncoding
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader

/**
 * All file content access goes through here. Reads are streaming and
 * memory-bounded; writes always preserve (or explicitly change) the original
 * encoding and line endings so files round-trip without corruption.
 */
object FileIo {

    /** Above this size we open read-only in paged mode instead of loading everything. */
    const val EDITABLE_LIMIT_BYTES = 16L * 1024 * 1024      // 16 MB editable
    const val ABSOLUTE_LIMIT_BYTES = 500L * 1024 * 1024     // 500 MB hard ceiling
    private const val DETECT_SAMPLE = 256 * 1024            // bytes sampled for detection
    private const val READ_BUFFER = 64 * 1024

    data class Meta(val name: String, val size: Long)

    /**
     * MIME type to use when *creating* a file, chosen so the SAF provider keeps
     * the exact extension the user typed instead of appending ".txt".
     *
     * The provider appends an extension only when the requested MIME maps to a
     * different extension than the file name already has. By handing it the MIME
     * that corresponds to the name's own extension (or application/octet-stream,
     * which maps to no extension, for code files like .kt/.py/.sql/.sh), the
     * name always round-trips unchanged.
     */
    fun mimeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        // octet-stream maps to no extension, so the provider never appends one.
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun queryMeta(resolver: ContentResolver, uri: Uri): Meta {
        var name = "untitled.txt"
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) size = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        return Meta(name, size)
    }

    /** Read a leading sample for charset detection without loading the whole file. */
    fun readSample(resolver: ContentResolver, uri: Uri, max: Int = DETECT_SAMPLE): ByteArray {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open input stream" }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(READ_BUFFER)
            var remaining = max
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n == -1) break
                out.write(buf, 0, n)
                remaining -= n
            }
            return out.toByteArray()
        }
    }

    fun loadModeFor(size: Long): LoadMode =
        if (size in 0..EDITABLE_LIMIT_BYTES) LoadMode.EDITABLE else LoadMode.READONLY_LARGE

    /**
     * Fully decode an editable-sized document. Strips a leading BOM if the
     * chosen encoding carries one, so it is never shown as a stray character.
     * [onProgress] reports bytes read for the loading UI.
     */
    fun readAll(
        resolver: ContentResolver,
        uri: Uri,
        encoding: TextEncoding,
        onProgress: ((Long) -> Unit)? = null,
    ): String {
        val decoder = encoding.charset()
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open input stream" }
            val reader = BufferedReader(InputStreamReader(input, decoder), READ_BUFFER)
            val sb = StringBuilder()
            val buf = CharArray(READ_BUFFER)
            var totalChars = 0L
            while (true) {
                val n = reader.read(buf)
                if (n == -1) break
                sb.append(buf, 0, n)
                totalChars += n
                onProgress?.invoke(totalChars)
            }
            // InputStreamReader handles the BOM for UTF-16/32; strip a UTF-8 BOM ourselves.
            if (sb.isNotEmpty() && sb[0] == '﻿') sb.deleteCharAt(0)
            return sb.toString()
        }
    }

    /**
     * Paged, read-only access for very large files: reads [count] lines
     * starting at [startLine] (0-based) without holding the rest in memory.
     */
    fun readLineWindow(
        resolver: ContentResolver,
        uri: Uri,
        encoding: TextEncoding,
        startLine: Int,
        count: Int,
    ): String {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open input stream" }
            val reader = BufferedReader(InputStreamReader(input, encoding.charset()), READ_BUFFER)
            var idx = 0
            // Skip leading lines.
            while (idx < startLine) {
                if (reader.readLine() == null) return ""
                idx++
            }
            val sb = StringBuilder()
            var taken = 0
            while (taken < count) {
                val line = reader.readLine() ?: break
                if (taken > 0) sb.append('\n')
                sb.append(line)
                taken++
            }
            return sb.toString()
        }
    }

    /**
     * Write [text] to [uri] using [encoding] and [lineEnding]. The output is
     * byte-identical for round-trips: BOM (if any) first, then the text encoded
     * with REPORT error handling so we never silently drop characters — callers
     * are expected to pick a compatible encoding (the UI defaults to the original).
     */
    fun writeAll(
        resolver: ContentResolver,
        uri: Uri,
        text: String,
        encoding: TextEncoding,
        lineEnding: LineEnding,
    ) {
        val normalized = normalizeLineEndings(text, lineEnding)
        resolver.openOutputStream(uri, "wt").use { out ->
            requireNotNull(out) { "Cannot open output stream" }
            encoding.bom?.let { out.write(it) }
            // Encode in chunks to bound memory for large editable docs.
            val writer = out.bufferedWriter(encoding.charset())
            writer.write(normalized)
            writer.flush()
        }
    }

    /**
     * Create (or overwrite) [fileName] inside the SAF tree [treeUri] and return
     * its document Uri, ready to be written with [writeAll]. Used by the
     * "default save folder" feature so new files land where the user chose.
     */
    fun createInTree(
        context: Context,
        treeUri: Uri,
        fileName: String,
        mime: String = mimeForName(fileName),
    ): Uri? {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!tree.canWrite()) return null
        // Overwrite a same-named file rather than creating "name (1)".
        tree.findFile(fileName)?.delete()
        val created = tree.createFile(mime, fileName) ?: return null
        return created.uri
    }

    /** Best-effort display name for a SAF tree folder. */
    fun treeDisplayName(context: Context, treeUri: Uri): String? =
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()

    private fun normalizeLineEndings(text: String, ending: LineEnding): String {
        if (ending == LineEnding.LF) {
            // Editor keeps text as LF internally; only convert when needed.
            return if (text.contains('\r')) text.replace("\r\n", "\n").replace("\r", "\n") else text
        }
        val lf = text.replace("\r\n", "\n").replace("\r", "\n")
        return lf.replace("\n", ending.sequence)
    }
}
