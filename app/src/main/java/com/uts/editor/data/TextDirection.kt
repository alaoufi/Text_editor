package com.uts.editor.data

/**
 * Detects the dominant writing direction of a piece of text by counting strong
 * Arabic vs. Latin letters. Used to set a document's base direction explicitly
 * instead of relying on the first character (which flips a whole Arabic document
 * to left-to-right the moment it starts with a digit or a Latin word).
 */
object TextDirection {

    fun isRtl(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (c in text) {
            when (c) {
                in '؀'..'ۿ',   // Arabic
                in 'ݐ'..'ݿ',   // Arabic Supplement
                in 'ࢠ'..'ࣿ',   // Arabic Extended-A
                in 'ﭐ'..'﷿',   // Arabic Presentation Forms-A
                in 'ﹰ'..'﻿',   // Arabic Presentation Forms-B
                -> rtl++
                in 'A'..'Z', in 'a'..'z' -> ltr++
            }
        }
        return rtl > 0 && rtl >= ltr
    }

    fun dominant(text: String): String = if (isRtl(text)) "rtl" else "ltr"
}
