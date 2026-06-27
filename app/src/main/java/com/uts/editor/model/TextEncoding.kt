package com.uts.editor.model

import java.nio.charset.Charset

/**
 * A text encoding the editor can read and write.
 *
 * [charsetName] / [aliases] are tried in order against the platform's
 * [Charset] registry, so the same logical encoding works across the
 * different names different Android versions ship with.
 *
 * [bom] is the byte-order-mark written on save (and stripped on load). It is
 * non-null only for the explicit BOM variants; the plain variants never emit one.
 */
data class TextEncoding(
    val id: String,
    val displayName: String,
    private val charsetName: String,
    private val aliases: List<String> = emptyList(),
    val bom: ByteArray? = null,
) {
    /** Resolves to a usable [Charset], falling back to UTF-8 if unavailable. */
    fun charset(): Charset {
        for (name in listOf(charsetName) + aliases) {
            try {
                if (Charset.isSupported(name)) return Charset.forName(name)
            } catch (_: Exception) {
                // try next alias
            }
        }
        return Charsets.UTF_8
    }

    /** True if this encoding is actually available on this device. */
    fun isAvailable(): Boolean =
        (listOf(charsetName) + aliases).any {
            runCatching { Charset.isSupported(it) }.getOrDefault(false)
        }

    override fun equals(other: Any?) = other is TextEncoding && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        val UTF_8 = TextEncoding("utf-8", "UTF-8", "UTF-8")
        val UTF_8_BOM = TextEncoding(
            "utf-8-bom", "UTF-8 (BOM)", "UTF-8",
            bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        )
        val UTF_16LE = TextEncoding(
            "utf-16le", "UTF-16 LE", "UTF-16LE",
            bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        )
        val UTF_16BE = TextEncoding(
            "utf-16be", "UTF-16 BE", "UTF-16BE",
            bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        )
        val UTF_32LE = TextEncoding(
            "utf-32le", "UTF-32 LE", "UTF-32LE", listOf("X-UTF-32LE"),
            bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
        )
        val UTF_32BE = TextEncoding(
            "utf-32be", "UTF-32 BE", "UTF-32BE", listOf("X-UTF-32BE"),
            bom = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())
        )
        val ASCII = TextEncoding("ascii", "ASCII (US-ASCII)", "US-ASCII")
        val ISO_8859_1 = TextEncoding("iso-8859-1", "ISO-8859-1 (Latin-1)", "ISO-8859-1")
        val ISO_8859_6 = TextEncoding("iso-8859-6", "ISO-8859-6 (Arabic)", "ISO-8859-6")
        val WINDOWS_1256 = TextEncoding(
            "windows-1256", "Windows-1256 (Arabic)", "windows-1256", listOf("cp1256", "MS1256")
        )
        val WINDOWS_1252 = TextEncoding(
            "windows-1252", "Windows-1252 (Latin)", "windows-1252", listOf("cp1252")
        )
        val CP720 = TextEncoding("cp720", "CP720 (Arabic DOS)", "IBM720", listOf("cp720", "x-IBM720"))
        val GBK = TextEncoding("gbk", "GBK (Chinese)", "GBK", listOf("GB2312", "GB18030"))
        val SHIFT_JIS = TextEncoding("shift-jis", "Shift-JIS (Japanese)", "Shift_JIS", listOf("MS932", "windows-31j"))
        val EUC_KR = TextEncoding("euc-kr", "EUC-KR (Korean)", "EUC-KR", listOf("MS949"))

        /** Every encoding the UI offers, in a sensible order (Arabic ones grouped early). */
        val ALL: List<TextEncoding> = listOf(
            UTF_8, UTF_8_BOM, UTF_16LE, UTF_16BE, UTF_32LE, UTF_32BE,
            WINDOWS_1256, ISO_8859_6, CP720,
            WINDOWS_1252, ISO_8859_1, ASCII,
            GBK, SHIFT_JIS, EUC_KR,
        )

        /** Only the encodings actually supported by this device. */
        fun available(): List<TextEncoding> = ALL.filter { it.isAvailable() }

        fun byId(id: String?): TextEncoding? = id?.let { ALL.firstOrNull { e -> e.id == it } }
    }
}
