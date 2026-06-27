package com.uts.editor.util

import com.uts.editor.viewmodel.RichSpan

/**
 * Serializes the editor's text plus its rich formatting (per-character bold /
 * italic / colour / size / highlight, and per-paragraph alignment / line
 * spacing) into a standalone HTML document that preserves the look when opened
 * in a browser or word processor.
 */
object HtmlExporter {

    private data class CharStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val color: Int? = null,
        val bg: Int? = null,
        val size: Float? = null,
    )

    fun toHtml(
        title: String,
        text: String,
        spans: List<RichSpan>,
        lineAligns: Map<Int, Int>,
        lineSpacings: Map<Int, Float>,
        defaultSizeSp: Float,
        defaultSpacing: Float,
    ): String {
        val n = text.length
        // Effective style per character (later spans win per attribute).
        val styles = Array(n) { CharStyle() }
        for (sp in spans) {
            val s = sp.start.coerceIn(0, n)
            val e = sp.end.coerceIn(s, n)
            for (i in s until e) {
                val cur = styles[i]
                styles[i] = cur.copy(
                    bold = cur.bold || sp.bold,
                    italic = cur.italic || sp.italic,
                    underline = cur.underline || sp.underline,
                    color = sp.color ?: cur.color,
                    bg = sp.bg ?: cur.bg,
                    size = sp.sizeSp ?: cur.size,
                )
            }
        }

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">")
        sb.append("<title>").append(escape(title)).append("</title></head>\n")
        sb.append("<body style=\"font-family:sans-serif; font-size:")
            .append(defaultSizeSp.toInt()).append("px;\">\n")

        val paragraphs = text.split("\n")
        var offset = 0
        paragraphs.forEachIndexed { lineIdx, para ->
            val align = when (lineAligns[lineIdx] ?: 0) {
                1 -> "center"; 2 -> "end"; 3 -> "justify"; else -> "start"
            }
            val spacing = lineSpacings[lineIdx] ?: defaultSpacing
            sb.append("<p dir=\"auto\" style=\"margin:0; text-align:")
                .append(align).append("; line-height:").append(spacing).append(";\">")
            if (para.isEmpty()) {
                sb.append("<br>")
            } else {
                appendStyledRuns(sb, para, styles, offset)
            }
            sb.append("</p>\n")
            offset += para.length + 1 // + the '\n'
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun appendStyledRuns(sb: StringBuilder, para: String, styles: Array<CharStyle>, base: Int) {
        var i = 0
        while (i < para.length) {
            val style = styles.getOrElse(base + i) { CharStyle() }
            var j = i + 1
            while (j < para.length && styles.getOrElse(base + j) { CharStyle() } == style) j++
            val chunk = para.substring(i, j)
            val css = cssFor(style)
            if (css.isEmpty()) {
                sb.append(escape(chunk))
            } else {
                sb.append("<span style=\"").append(css).append("\">").append(escape(chunk)).append("</span>")
            }
            i = j
        }
    }

    private fun cssFor(s: CharStyle): String {
        val parts = mutableListOf<String>()
        if (s.bold) parts.add("font-weight:bold")
        if (s.italic) parts.add("font-style:italic")
        if (s.underline) parts.add("text-decoration:underline")
        s.color?.let { parts.add("color:" + hex(it)) }
        s.bg?.let { parts.add("background-color:" + hex(it)) }
        s.size?.let { parts.add("font-size:" + it.toInt() + "px") }
        return parts.joinToString(";")
    }

    private fun hex(argb: Int): String =
        "#%06X".format(argb and 0xFFFFFF)

    private fun escape(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            else -> out.append(c)
        }
        return out.toString()
    }
}
