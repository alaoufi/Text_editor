package com.uts.editor.model

import android.net.Uri

/** How the file is held in memory. */
enum class LoadMode {
    /** Fully editable, entire content in memory. */
    EDITABLE,

    /** Too large to edit safely: read-only, paged view backed by the source. */
    READONLY_LARGE,
}

/** A line-ending style preserved across save so files round-trip unchanged. */
enum class LineEnding(val sequence: String) {
    LF("\n"), CRLF("\r\n"), CR("\r");

    companion object {
        fun detect(text: String): LineEnding = when {
            text.contains("\r\n") -> CRLF
            text.contains("\r") -> CR
            else -> LF
        }
    }
}

/** Live counts shown in the status bar. */
data class TextStats(
    val lines: Int = 1,
    val words: Int = 0,
    val chars: Int = 0,
    val sizeBytes: Long = 0,
) {
    companion object {
        fun of(text: String, sizeBytes: Long): TextStats {
            val lines = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            return TextStats(lines = lines, words = words, chars = text.length, sizeBytes = sizeBytes)
        }
    }
}

/**
 * A single open document / tab. Immutable snapshot held in the ViewModel state;
 * the editable text itself lives in a Compose [androidx.compose.ui.text.input.TextFieldValue]
 * kept beside this in the ViewModel to avoid copying large strings into state on every keystroke.
 */
data class DocumentState(
    val id: String,
    /** Source document Uri (SAF). Null for brand-new unsaved documents. */
    val uri: Uri? = null,
    val displayName: String,
    val encoding: TextEncoding = TextEncoding.UTF_8,
    val lineEnding: LineEnding = LineEnding.LF,
    val language: SyntaxLanguage = SyntaxLanguage.PLAIN,
    val loadMode: LoadMode = LoadMode.EDITABLE,
    val isModified: Boolean = false,
    val stats: TextStats = TextStats(),
    /** Total size on disk, used for the large-file read-only path. */
    val totalBytes: Long = 0,
    /** Entry name when the document was opened from inside a ZIP archive. */
    val zipEntry: String? = null,
)

/** Supported syntax-highlight languages. */
enum class SyntaxLanguage(val display: String) {
    PLAIN("Text"),
    SQL("SQL"),
    HTML("HTML"),
    CSS("CSS"),
    JAVASCRIPT("JavaScript"),
    JSON("JSON"),
    XML("XML"),
    PYTHON("Python"),
    JAVA("Java"),
    KOTLIN("Kotlin"),
    PHP("PHP");

    companion object {
        fun fromFileName(name: String): SyntaxLanguage {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "sql" -> SQL
                "html", "htm" -> HTML
                "css" -> CSS
                "js", "ts", "mjs", "cjs" -> JAVASCRIPT
                "json" -> JSON
                "xml" -> XML
                "py", "pyw" -> PYTHON
                "java" -> JAVA
                "kt", "kts" -> KOTLIN
                "php" -> PHP
                else -> PLAIN
            }
        }
    }
}
