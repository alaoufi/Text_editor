package com.uts.editor.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Reads text entries directly out of a ZIP archive without extracting to disk. */
object ZipSupport {

    data class Entry(val name: String, val size: Long)

    /** Lists entries that look like text files (by extension or unknown extension). */
    fun listTextEntries(resolver: ContentResolver, uri: Uri): List<Entry> {
        val out = mutableListOf<Entry>()
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open archive" }
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        out += Entry(e.name, e.size)
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        return out
    }

    /** Reads a single entry's raw bytes (used for detection then decode). */
    fun readEntryBytes(resolver: ContentResolver, uri: Uri, entryName: String): ByteArray {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open archive" }
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == entryName && !e.isDirectory) {
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                        }
                        return out.toByteArray()
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        throw IllegalArgumentException("Entry not found: $entryName")
    }
}
