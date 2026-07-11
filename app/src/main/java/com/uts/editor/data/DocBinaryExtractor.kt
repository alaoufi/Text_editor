package com.uts.editor.data

import java.nio.charset.Charset

/**
 * Extracts clean, in-order text from a legacy binary Word document (.doc) by
 * actually parsing its structure — with no third-party library:
 *
 *   OLE2 / Compound File Binary  ->  the "WordDocument" and table streams
 *   FIB (File Information Block)  ->  location of the piece table (CLX)
 *   piece table (PlcPcd)         ->  each text piece, 8-bit (code page) or UTF-16
 *
 * This yields the document's real text in reading order, unlike a raw byte scan
 * which also drags in style names and internal stream names as noise. Returns
 * null when the file isn't a parseable .doc, so the caller can fall back to the
 * best-effort recovery.
 */
object DocBinaryExtractor {

    private val CFB_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    fun extract(bytes: ByteArray): String? = runCatching { parse(bytes) }.getOrNull()

    private fun parse(data: ByteArray): String? {
        if (data.size < 512 || !data.copyOfRange(0, 8).contentEquals(CFB_SIGNATURE)) return null

        val sectorSize = 1 shl u16(data, 30)
        val miniSize = 1 shl u16(data, 32)
        val firstDir = u32i(data, 48)
        val miniCutoff = u32i(data, 56)
        val firstMiniFat = u32i(data, 60)
        val firstDifat = u32i(data, 68)

        fun sector(n: Int): ByteArray {
            val off = (n + 1) * sectorSize
            return if (off + sectorSize <= data.size) data.copyOfRange(off, off + sectorSize) else ByteArray(sectorSize)
        }

        // DIFAT -> FAT
        val difat = ArrayList<Int>()
        for (k in 0 until 109) difat.add(u32i(data, 76 + k * 4))
        var dn = firstDifat
        var guard = 0
        while (dn in 0..(data.size / sectorSize) && guard++ < 4096) {
            val s = sector(dn)
            val perSector = sectorSize / 4
            for (k in 0 until perSector - 1) difat.add(u32i(s, k * 4))
            dn = u32i(s, (perSector - 1) * 4)
        }
        val fat = ArrayList<Int>()
        for (fs in difat) {
            if (fs < 0) continue
            val s = sector(fs)
            for (k in 0 until sectorSize / 4) fat.add(u32i(s, k * 4))
        }
        fun chain(start: Int): List<Int> {
            val out = ArrayList<Int>()
            var n = start
            var g = 0
            while (n in 0 until fat.size && g++ < 1_000_000) { out.add(n); n = fat[n] }
            return out
        }
        fun readStream(start: Int, size: Int): ByteArray {
            val buf = java.io.ByteArrayOutputStream()
            for (s in chain(start)) buf.write(sector(s))
            val b = buf.toByteArray()
            return if (size in 0..b.size) b.copyOfRange(0, size) else b
        }

        // Directory
        val dirBytes = readStream(firstDir, Int.MAX_VALUE)
        data class Entry(val name: String, val type: Int, val start: Int, val size: Int)
        val entries = ArrayList<Entry>()
        var i = 0
        while (i + 128 <= dirBytes.size) {
            val nlen = u16(dirBytes, i + 64)
            val name = if (nlen >= 2) String(dirBytes, i, nlen - 2, Charsets.UTF_16LE) else ""
            entries.add(Entry(name, dirBytes[i + 66].toInt() and 0xFF, u32i(dirBytes, i + 116), u32i(dirBytes, i + 120)))
            i += 128
        }
        val root = entries.firstOrNull { it.type == 5 } ?: return null
        val miniStream = readStream(root.start, root.size)

        val miniFat = ArrayList<Int>()
        for (s in chain(firstMiniFat)) {
            val sec = sector(s)
            for (k in 0 until sectorSize / 4) miniFat.add(u32i(sec, k * 4))
        }
        fun readMini(start: Int, size: Int): ByteArray {
            val buf = java.io.ByteArrayOutputStream()
            var n = start
            var g = 0
            while (n in 0 until miniFat.size && g++ < 1_000_000) {
                val off = n * miniSize
                if (off + miniSize <= miniStream.size) buf.write(miniStream, off, miniSize)
                n = miniFat[n]
            }
            val b = buf.toByteArray()
            return if (size in 0..b.size) b.copyOfRange(0, size) else b
        }
        fun getStream(name: String): ByteArray? {
            val e = entries.firstOrNull { it.name == name && it.type == 2 } ?: return null
            return if (e.size < miniCutoff) readMini(e.start, e.size) else readStream(e.start, e.size)
        }

        val wd = getStream("WordDocument") ?: return null
        if (wd.size < 0x1A6 + 4) return null
        val flags = u16(wd, 0x0A)
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = getStream(tableName) ?: return null
        val fcClx = u32i(wd, 0x01A2)
        val lcbClx = u32i(wd, 0x01A6)
        if (lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > table.size) return null
        val clx = table.copyOfRange(fcClx, fcClx + lcbClx)

        // Walk the CLX to the Pcdt (piece table).
        var p = 0
        var plc: ByteArray? = null
        while (p < clx.size) {
            when (clx[p].toInt() and 0xFF) {
                1 -> { val cb = u16(clx, p + 1); p += 3 + cb }
                2 -> { val lcb = u32i(clx, p + 1); plc = clx.copyOfRange(p + 5, minOf(p + 5 + lcb, clx.size)); break }
                else -> break
            }
        }
        val pieces = plc ?: return null
        val n = (pieces.size - 4) / 12
        if (n <= 0) return null
        val cps = IntArray(n + 1) { u32i(pieces, it * 4) }
        val base = (n + 1) * 4

        val cp1256 = runCatching { Charset.forName("windows-1256") }.getOrDefault(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        for (k in 0 until n) {
            val pcdOff = base + k * 8
            if (pcdOff + 8 > pieces.size) break
            val fcRaw = u32(pieces, pcdOff + 2)
            val compressed = fcRaw and 0x40000000L != 0L
            val fc = (fcRaw and 0x3FFFFFFFL).toInt()
            val count = cps[k + 1] - cps[k]
            if (count <= 0) continue
            if (compressed) {
                val off = fc / 2
                if (off in 0..wd.size) sb.append(String(wd, off, minOf(count, wd.size - off), cp1256))
            } else {
                val off = fc
                if (off in 0..wd.size) sb.append(String(wd, off, minOf(count * 2, wd.size - off), Charsets.UTF_16LE))
            }
        }
        return clean(sb.toString()).ifBlank { null }
    }

    /** Normalise Word's control marks and collapse the runs of blank lines that
     *  make diagram/SmartArt text look scattered. */
    private fun clean(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when (c.code) {
                0x0D, 0x0B, 0x0C, 0x0A -> sb.append('\n') // CR / line break / page break / LF
                0x07, 0x09 -> sb.append('\t')             // table cell mark / tab
                else -> if (c.code >= 0x20) sb.append(c)
            }
        }
        return sb.toString()
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun u32i(b: ByteArray, off: Int): Int = u32(b, off).toInt()
}
