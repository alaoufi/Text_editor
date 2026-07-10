package com.uts.editor.data

/**
 * Best-effort readable-text recovery from legacy binary Office files (.doc/.xls
 * OLE2/BIFF) that have no light exact parser. Recovers UTF-16LE text runs (what
 * these formats store body/string text as, including Arabic) and keeps runs that
 * look like real words. Layout is not preserved; strings come through.
 */
object BinaryTextRecovery {

    private const val MIN_RUN = 4

    fun recover(bytes: ByteArray): String {
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
            if (isReadable(code)) run.append(code.toChar()) else flushRun(run, sb)
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
