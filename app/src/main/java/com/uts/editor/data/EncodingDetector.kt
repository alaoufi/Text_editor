package com.uts.editor.data

import com.uts.editor.model.TextEncoding
import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Result of automatic encoding detection.
 *
 * @param encoding best guess
 * @param confidence 0..100
 * @param needsConfirmation true when the app should prompt the user to pick
 *        an encoding manually (with live preview) instead of opening silently.
 */
data class DetectionResult(
    val encoding: TextEncoding,
    val confidence: Int,
    val needsConfirmation: Boolean,
)

/**
 * Multi-strategy charset detection tuned so Arabic text is never silently
 * corrupted. Order of evidence, strongest first:
 *
 *  1. Byte-order mark (definitive).
 *  2. Strict UTF-8 validation (extremely low false-positive rate).
 *  3. Mozilla universalchardet statistical guess.
 *  4. Decode-and-score across the single-byte Arabic/Latin candidates, scoring
 *     by how many decoded characters land in expected Unicode ranges.
 *
 * When no strategy is confident, we still return a best guess but flag
 * [DetectionResult.needsConfirmation] so the UI asks the user.
 */
object EncodingDetector {

    private const val CONFIDENT = 90
    // Only fall back to the manual dialog when detection essentially has no
    // signal; otherwise open automatically with the best guess.
    private const val PROMPT_BELOW = 50

    /**
     * Heuristic check for whether [sample] is a *binary* (non-text) file such as
     * an image, archive, font or executable. The app uses this to refuse to open
     * such files as text (and to avoid uselessly prompting for an encoding).
     *
     * Text — including every supported legacy/Unicode encoding — passes; the
     * giveaways for binary are NUL bytes (outside the regular pattern UTF-16/32
     * produce) and a high proportion of non-text control bytes.
     */
    fun looksBinary(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false
        if (detectBom(sample) != null) return false           // BOM => a Unicode text file
        if (isStrictUtf8(sample)) return false                 // valid UTF-8 => text
        if (utf16Guess(sample) != null) return false          // UTF-16 (no BOM) text

        // NUL / control analysis: real text in a single-byte/UTF-8 encoding has no
        // NUL bytes and few C0 controls. (UTF-16 was already ruled in above.)
        val n = sample.size
        var nul = 0
        var control = 0
        for (i in 0 until n) {
            val b = sample[i].toInt() and 0xFF
            when {
                b == 0x00 -> nul++
                b == 0x09 || b == 0x0A || b == 0x0D -> { /* tab/newline: fine */ }
                b < 0x20 -> control++                           // other C0 controls
                b == 0x7F -> control++
            }
        }
        if (nul > 0) return true                                 // NUL but not UTF-16 => binary
        return control.toDouble() / n > 0.10                     // dense controls => binary
    }

    /**
     * Detect UTF-16 (little- or big-endian) text that has no BOM — common for
     * Arabic files exported from Windows. Decodes both endiannesses and accepts
     * the one that yields clean, mostly-textual content. Single-byte or UTF-8
     * data decoded as UTF-16 turns into noise and scores too low to match, so
     * this never steals genuinely single-byte files.
     */
    private fun utf16Guess(sample: ByteArray): ScoredEncoding? {
        if (sample.size < 16) return null
        val le = scoreUtf16Variant(sample, littleEndian = true)
        val be = scoreUtf16Variant(sample, littleEndian = false)
        val best = listOf(le, be).filterNotNull().maxByOrNull { it.confidence } ?: return null
        return if (best.confidence >= 85) best else null
    }

    /**
     * Score one UTF-16 endianness using two independent signals:
     *  - structural: in real UTF-16 text almost every 16-bit unit has a small
     *    high byte (0x00 for ASCII/Latin, 0x06/0x07 for Arabic, etc.). Random
     *    single-byte data decoded as UTF-16 has high bytes spread across 0..255,
     *    so this alone rejects mis-decoded Latin/Arabic codepages.
     *  - textual: the decoded characters are mostly Arabic/Latin/whitespace.
     */
    private fun scoreUtf16Variant(sample: ByteArray, littleEndian: Boolean): ScoredEncoding? {
        val pairs = sample.size / 2
        if (pairs < 8) return null
        var textyHigh = 0
        for (k in 0 until pairs) {
            val hi = sample[if (littleEndian) 2 * k + 1 else 2 * k].toInt() and 0xFF
            // High bytes seen in normal text: ASCII/Latin/Cyrillic/Hebrew/Arabic
            // blocks (<=0x09), spaces (0x20/0x21), and Arabic Presentation Forms
            // (0xFB-0xFE) which Windows Arabic files frequently use.
            if (hi <= 0x09 || hi == 0x20 || hi == 0x21 || hi in 0xFB..0xFE) textyHigh++
        }
        val highRatio = textyHigh.toDouble() / pairs
        if (highRatio < 0.90) return null

        val enc = if (littleEndian) TextEncoding.UTF_16LE else TextEncoding.UTF_16BE
        val text = decodeLossy(sample, enc) ?: return null
        var good = 0
        for (ch in text) {
            val code = ch.code
            val ok = code in 0x0600..0x06FF || code in 0x0750..0x077F ||  // Arabic
                code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF ||       // Arabic Presentation Forms
                code == 0x09 || code == 0x0A || code == 0x0D ||           // whitespace
                code in 0x20..0x7E ||                                     // ASCII printable
                code in 0xA0..0x24F                                        // Latin-1/extended
            if (ok) good++
        }
        if (text.isEmpty()) return null
        val textRatio = good.toDouble() / text.length
        if (textRatio < 0.80) return null

        val confidence = (minOf(highRatio, textRatio) * 100).toInt().coerceIn(0, 96)
        return ScoredEncoding(enc, confidence)
    }

    fun detect(sample: ByteArray): DetectionResult {
        if (sample.isEmpty()) {
            return DetectionResult(TextEncoding.UTF_8, 100, needsConfirmation = false)
        }

        detectBom(sample)?.let { return DetectionResult(it, 100, needsConfirmation = false) }

        // UTF-16 without a BOM (common for Windows-exported Arabic files) MUST be
        // checked before the UTF-8/ASCII shortcuts: UTF-16LE Arabic bytes are
        // coincidentally valid UTF-8 (Arabic low bytes are ASCII-range, high bytes
        // are 0x06/0x07), so a UTF-8 check would otherwise claim and mangle them.
        // The structural high-byte test keeps genuine UTF-8/ASCII from matching.
        utf16Guess(sample)?.let {
            return DetectionResult(it.encoding, it.confidence, needsConfirmation = false)
        }

        // Pure ASCII is a valid UTF-8 subset — open as UTF-8.
        if (sample.all { it >= 0 }) {
            return DetectionResult(TextEncoding.UTF_8, 99, needsConfirmation = false)
        }

        // Strict UTF-8: if it decodes cleanly and uses multibyte sequences, trust it.
        if (isStrictUtf8(sample)) {
            return DetectionResult(TextEncoding.UTF_8, 97, needsConfirmation = false)
        }

        val universal = universalGuess(sample)
        val arabicBest = scoreCandidates(sample)

        // Prefer a strong Arabic single-byte score when universalchardet is unsure
        // or disagrees on Arabic content — this is the project's top priority.
        val candidates = buildList {
            universal?.let { add(it) }
            arabicBest?.let { add(it) }
        }.sortedByDescending { it.confidence }

        val best = candidates.firstOrNull()
            ?: ScoredEncoding(TextEncoding.WINDOWS_1252, 40)

        return DetectionResult(
            encoding = best.encoding,
            confidence = best.confidence,
            needsConfirmation = best.confidence < PROMPT_BELOW,
        )
    }

    private fun detectBom(b: ByteArray): TextEncoding? {
        fun starts(vararg bytes: Int): Boolean =
            b.size >= bytes.size && bytes.indices.all { (b[it].toInt() and 0xFF) == bytes[it] }
        return when {
            // UTF-32 must be checked before UTF-16 (shared FF FE prefix).
            starts(0x00, 0x00, 0xFE, 0xFF) -> TextEncoding.UTF_32BE
            starts(0xFF, 0xFE, 0x00, 0x00) -> TextEncoding.UTF_32LE
            starts(0xEF, 0xBB, 0xBF) -> TextEncoding.UTF_8_BOM
            starts(0xFE, 0xFF) -> TextEncoding.UTF_16BE
            starts(0xFF, 0xFE) -> TextEncoding.UTF_16LE
            else -> null
        }
    }

    private fun isStrictUtf8(b: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(b))
            true
        } catch (_: Exception) {
            false
        }
    }

    private data class ScoredEncoding(val encoding: TextEncoding, val confidence: Int)

    private fun universalGuess(sample: ByteArray): ScoredEncoding? {
        val detector = UniversalDetector(null)
        detector.handleData(sample, 0, sample.size)
        detector.dataEnd()
        val name = detector.detectedCharset
        detector.reset()
        if (name.isNullOrBlank()) return null
        val enc = mapName(name) ?: return null
        // universalchardet doesn't expose a numeric confidence; treat a concrete
        // hit as moderately strong, but never as strong as UTF-8 validation.
        return ScoredEncoding(enc, 80)
    }

    private fun mapName(raw: String): TextEncoding? {
        val n = raw.trim().uppercase().replace("_", "-")
        TextEncoding.ALL.firstOrNull { it.charset().name().uppercase().replace("_", "-") == n }
            ?.let { return it }
        return when {
            n.contains("UTF-8") -> TextEncoding.UTF_8
            n.contains("1256") -> TextEncoding.WINDOWS_1256
            n.contains("8859-6") -> TextEncoding.ISO_8859_6
            n.contains("1252") -> TextEncoding.WINDOWS_1252
            n.contains("8859-1") || n == "LATIN1" -> TextEncoding.ISO_8859_1
            n.contains("SHIFT") || n.contains("SJIS") -> TextEncoding.SHIFT_JIS
            n.contains("EUC-KR") -> TextEncoding.EUC_KR
            n.contains("GB") -> TextEncoding.GBK
            else -> {
                // Unknown but platform-supported charset (e.g. windows-1251): wrap it.
                runCatching {
                    if (java.nio.charset.Charset.isSupported(raw)) {
                        TextEncoding(raw.lowercase(), raw, raw)
                    } else null
                }.getOrNull()
            }
        }
    }

    /**
     * Decode the sample with each single-byte candidate and choose between them.
     *
     * The decisive, direction-safe rule: a charset that produces a *substantial
     * density* of Arabic letters is almost certainly the right Arabic codepage,
     * because Latin codepages produce ~zero Arabic from the same bytes. If no
     * candidate yields meaningful Arabic, the text is Latin/other and we pick the
     * cleanest Latin decoding (fewest replacement / C1-control characters). This
     * prevents both failure modes: Arabic mis-read as Latin, and Latin text with
     * a few accents mis-read as Arabic.
     */
    private fun scoreCandidates(sample: ByteArray): ScoredEncoding? {
        val arabicCandidates = listOf(
            TextEncoding.WINDOWS_1256,
            TextEncoding.ISO_8859_6,
            TextEncoding.CP720,
        ).filter { it.isAvailable() }
        val latinCandidates = listOf(
            TextEncoding.WINDOWS_1252,
            TextEncoding.ISO_8859_1,
        ).filter { it.isAvailable() }

        val scans = (arabicCandidates + latinCandidates).mapNotNull { enc ->
            decodeLossy(sample, enc)?.let { enc to scan(it) }
        }
        if (scans.isEmpty()) return null

        // Best Arabic decoding by absolute Arabic-letter count.
        val bestArabic = scans
            .filter { it.first in arabicCandidates }
            .maxByOrNull { it.second.arabic }

        bestArabic?.let { (_, s) ->
            // Genuine Arabic forms multi-letter words (contiguous runs); European
            // accents mis-decoded as Arabic appear as isolated single characters
            // wedged between Latin letters. The cluster ratio separates the two.
            val clustered = if (s.arabic == 0) 0.0 else s.arabicInRuns.toDouble() / s.arabic
            // Pick the Arabic codepage whenever there are a few *clustered* Arabic
            // letters — even in a mostly-English file. Decoding as an Arabic
            // codepage leaves ASCII untouched, so it only ever fixes the Arabic
            // and never harms the English. This is what lets files open correctly
            // without ever asking the user to choose.
            if (s.arabic >= 4 && clustered >= 0.6) {
                val confidence = (78 + clustered * 18).toInt().coerceIn(78, 96)
                // Among Arabic codepages, prefer the one with the fewest decode
                // gaps (e.g. windows-1256 over ISO-8859-6 when both fit).
                val cleanest = scans
                    .filter { it.first in arabicCandidates && it.second.arabic >= s.arabic * 0.9 }
                    .minByOrNull { it.second.replacement }!!
                return ScoredEncoding(cleanest.first, confidence)
            }
        }

        // No meaningful Arabic -> the text is Latin/other. Rank only the Latin
        // candidates so a stray accent decoded as Arabic can't hijack the result;
        // windows-1252 (a superset of ISO-8859-1) is the safe default on a tie.
        val latinScans = scans.filter { it.first in latinCandidates }
        val best = (latinScans.ifEmpty { scans }).maxByOrNull { weighted(it.second) } ?: return null
        // Latin/ASCII text is only moderately certain (1252 vs 8859-1 ambiguity).
        val conf = if (best.second.replacement == 0 && best.second.c1 == 0) 72 else 55
        return ScoredEncoding(best.first, conf)
    }

    private fun decodeLossy(b: ByteArray, enc: TextEncoding): String? = runCatching {
        val decoder = enc.charset().newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        decoder.decode(ByteBuffer.wrap(b)).toString()
    }.getOrNull()

    private data class Scan(
        val arabic: Int,
        val arabicInRuns: Int,  // Arabic chars belonging to a run of length >= 2
        val ascii: Int,
        val highLatin: Int,
        val replacement: Int,
        val c1: Int,
        val control: Int,
        val total: Int,
    )

    private fun isArabic(code: Int) = code in 0x0600..0x06FF || code in 0x0750..0x077F

    private fun scan(text: String): Scan {
        var arabic = 0; var ascii = 0; var highLatin = 0
        var replacement = 0; var c1 = 0; var control = 0
        var arabicInRuns = 0; var run = 0
        for (ch in text) {
            val code = ch.code
            if (isArabic(code)) {
                arabic++; run++
            } else {
                if (run >= 2) arabicInRuns += run
                run = 0
                when {
                    ch == '�' -> replacement++
                    code == 0x09 || code == 0x0A || code == 0x0D -> ascii++
                    code in 0x20..0x7E -> ascii++
                    code in 0x80..0x9F -> c1++             // C1 controls: strong "wrong charset" signal
                    code in 0xA0..0x24F -> highLatin++
                    code < 0x20 -> control++
                    else -> { /* other scripts: neutral */ }
                }
            }
        }
        if (run >= 2) arabicInRuns += run
        return Scan(arabic, arabicInRuns, ascii, highLatin, replacement, c1, control, text.length)
    }

    /** Absolute readability weight (Arabic-agnostic): used only as a fallback ranker. */
    private fun weighted(s: Scan): Int =
        s.arabic * 3 + s.ascii + s.highLatin - s.replacement * 5 - s.c1 * 4 - s.control * 3
}
