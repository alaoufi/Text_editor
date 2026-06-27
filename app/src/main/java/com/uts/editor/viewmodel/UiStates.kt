package com.uts.editor.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.uts.editor.data.ZipSupport
import com.uts.editor.model.DocumentState
import com.uts.editor.model.TextEncoding

/** Why the manual encoding dialog is being shown. */
enum class EncodingPromptReason { DETECT_FAILED, USER_REQUEST_REOPEN }

/** State backing the manual encoding-selection dialog (with live preview). */
data class EncodingPrompt(
    val uri: Uri?,
    val zipEntry: String?,
    val displayName: String,
    val size: Long,
    val sample: ByteArray,
    val suggested: TextEncoding,
    val confidence: Int,
    val reason: EncodingPromptReason,
)

/** State backing the ZIP entry picker. */
data class ZipPrompt(
    val uri: Uri,
    val entries: List<ZipSupport.Entry>,
)

/** Shown when a file does not look like text (e.g. an image), before opening it. */
data class BinaryPrompt(
    val uri: Uri,
    val displayName: String,
    val size: Long,
)

/** State for find / replace. [matches] are ranges into the current text. */
data class FindState(
    val visible: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val regex: Boolean = false,
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val matches: List<IntRange> = emptyList(),
    val current: Int = -1,
)

/** Transient one-shot message for a snackbar. */
data class UiMessage(val text: String)

/**
 * A character-range rich-text override. Each span carries one (or more)
 * attributes; spans layer on top of each other (later wins per attribute).
 * Display-only for .txt; persisted when the document is exported to HTML.
 */
data class RichSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Int? = null,
    val bg: Int? = null,
    val sizeSp: Float? = null,
)

/**
 * One open document. Editable content lives in Compose state ([field]) so
 * keystrokes don't churn ViewModel-wide state. Undo/redo are snapshot based.
 */
class EditorTab(initialDoc: DocumentState, initialField: TextFieldValue) {

    val id: String = initialDoc.id

    var doc by mutableStateOf(initialDoc)
    var field by mutableStateOf(initialField)

    /** Hash + length of the last saved content, for modified detection. */
    var savedSignature: Long = signatureOf(initialField.text)

    // For large read-only paging.
    var pageStartLine by mutableIntStateOf(0)
    var totalLinesEstimate by mutableIntStateOf(0)

    /** Per-paragraph alignment override (line index -> 0 start/1 center/2 end/3 justify).
     *  Display-only; not stored in the plain-text file. */
    val lineAligns = androidx.compose.runtime.mutableStateMapOf<Int, Int>()

    /** Per-paragraph line-spacing multiplier (line index -> multiplier). Display-only. */
    val lineSpacings = androidx.compose.runtime.mutableStateMapOf<Int, Float>()

    /** Character-range rich-text formatting (bold/italic/color/size/bg). */
    val spans = androidx.compose.runtime.mutableStateListOf<RichSpan>()

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private val maxHistory = 200

    fun pushUndo(previous: TextFieldValue) {
        if (undoStack.lastOrNull()?.text == previous.text) return
        undoStack.addLast(previous)
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo(): TextFieldValue? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(field)
        return prev
    }

    fun redo(): TextFieldValue? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(field)
        return next
    }

    fun markSaved() {
        savedSignature = signatureOf(field.text)
    }

    fun isModified(): Boolean = signatureOf(field.text) != savedSignature

    companion object {
        fun signatureOf(text: String): Long =
            (text.length.toLong() shl 32) xor (text.hashCode().toLong() and 0xFFFFFFFFL)
    }
}
