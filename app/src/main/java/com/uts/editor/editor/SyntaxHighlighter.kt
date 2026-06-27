package com.uts.editor.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.uts.editor.model.SyntaxLanguage

/** Palette used by the highlighter; supplied by the active theme. */
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val tag: Color,
    val attribute: Color,
    val punctuation: Color,
)

/**
 * Lightweight, regex-based syntax highlighting. It is intentionally tokenizer-
 * light (no full parse) so it stays fast, and it is capped by length so large
 * documents never block the UI thread.
 */
object SyntaxHighlighter {

    /** Don't attempt to highlight beyond this many characters (keeps typing smooth). */
    const val MAX_HIGHLIGHT_CHARS = 120_000

    private data class Rule(val regex: Regex, val style: SpanStyle)

    fun highlight(text: String, language: SyntaxLanguage, colors: SyntaxColors): AnnotatedString {
        if (language == SyntaxLanguage.PLAIN || text.length > MAX_HIGHLIGHT_CHARS) {
            return AnnotatedString(text)
        }
        val rules = rulesFor(language, colors)
        return buildAnnotatedString {
            append(text)
            // Track consumed ranges so a keyword inside a string isn't recoloured.
            val taken = BooleanArray(text.length)
            for (rule in rules) {
                for (m in rule.regex.findAll(text)) {
                    val r = m.range
                    if (r.isEmpty()) continue
                    if (taken[r.first]) continue
                    addStyle(rule.style, r.first, r.last + 1)
                    for (i in r.first..r.last) taken[i] = true
                }
            }
        }
    }

    private fun rulesFor(language: SyntaxLanguage, c: SyntaxColors): List<Rule> {
        val str = SpanStyle(color = c.string)
        val com = SpanStyle(color = c.comment, fontWeight = FontWeight.Normal)
        val kw = SpanStyle(color = c.keyword, fontWeight = FontWeight.Bold)
        val num = SpanStyle(color = c.number)
        val tag = SpanStyle(color = c.tag, fontWeight = FontWeight.Bold)
        val attr = SpanStyle(color = c.attribute)

        // Order is significant: comments and strings must win over keywords.
        val commentRules: List<Rule> = when (language) {
            SyntaxLanguage.SQL -> listOf(Rule(Regex("--[^\n]*"), com), Rule(Regex("/\\*[\\s\\S]*?\\*/"), com))
            SyntaxLanguage.PYTHON -> listOf(Rule(Regex("#[^\n]*"), com))
            SyntaxLanguage.HTML, SyntaxLanguage.XML -> listOf(Rule(Regex("<!--[\\s\\S]*?-->"), com))
            SyntaxLanguage.CSS -> listOf(Rule(Regex("/\\*[\\s\\S]*?\\*/"), com))
            else -> listOf(Rule(Regex("//[^\n]*"), com), Rule(Regex("/\\*[\\s\\S]*?\\*/"), com))
        }

        val stringRules = listOf(
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), str),
            Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), str),
            Rule(Regex("`(?:\\\\.|[^`\\\\])*`"), str),
        )

        val numberRule = Rule(Regex("\\b\\d+(?:\\.\\d+)?\\b"), num)

        val keywordRule: Rule? = keywordsFor(language)?.let { words ->
            Rule(Regex("\\b(?:${words.joinToString("|")})\\b"), kw)
        }

        val tagRules: List<Rule> = when (language) {
            SyntaxLanguage.HTML, SyntaxLanguage.XML -> listOf(
                Rule(Regex("</?[A-Za-z_][\\w:-]*"), tag),
                Rule(Regex(">"), tag),
                Rule(Regex("[A-Za-z_][\\w:-]*(?==)"), attr),
            )
            else -> emptyList()
        }

        return buildList {
            addAll(commentRules)
            addAll(stringRules)
            addAll(tagRules)
            keywordRule?.let { add(it) }
            add(numberRule)
        }
    }

    private fun keywordsFor(language: SyntaxLanguage): List<String>? = when (language) {
        SyntaxLanguage.SQL -> listOf(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE",
            "ALTER", "DROP", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP",
            "BY", "ORDER", "HAVING", "LIMIT", "VALUES", "INTO", "SET", "AND", "OR",
            "NOT", "NULL", "AS", "DISTINCT", "INDEX", "PRIMARY", "KEY", "FOREIGN",
            "select", "from", "where", "insert", "update", "delete", "join", "and", "or",
        )
        SyntaxLanguage.JAVASCRIPT -> listOf(
            "function", "var", "let", "const", "if", "else", "for", "while", "return",
            "class", "new", "this", "typeof", "instanceof", "try", "catch", "finally",
            "throw", "switch", "case", "break", "continue", "default", "import", "export",
            "from", "async", "await", "yield", "true", "false", "null", "undefined",
            "interface", "type", "enum", "implements", "extends", "public", "private",
        )
        SyntaxLanguage.PYTHON -> listOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import",
            "from", "as", "try", "except", "finally", "raise", "with", "lambda", "yield",
            "global", "nonlocal", "pass", "break", "continue", "and", "or", "not", "in",
            "is", "None", "True", "False", "self", "async", "await",
        )
        SyntaxLanguage.JAVA -> listOf(
            "public", "private", "protected", "class", "interface", "extends", "implements",
            "static", "final", "void", "int", "long", "double", "float", "boolean", "char",
            "if", "else", "for", "while", "return", "new", "this", "super", "try", "catch",
            "finally", "throw", "throws", "import", "package", "switch", "case", "break",
            "continue", "default", "abstract", "synchronized", "volatile", "true", "false", "null",
        )
        SyntaxLanguage.KOTLIN -> listOf(
            "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum",
            "if", "else", "for", "while", "when", "return", "is", "as", "in", "import",
            "package", "private", "public", "internal", "protected", "override", "open",
            "abstract", "suspend", "companion", "init", "constructor", "by", "lazy",
            "true", "false", "null", "this", "super", "try", "catch", "finally", "throw",
        )
        SyntaxLanguage.PHP -> listOf(
            "function", "class", "public", "private", "protected", "static", "if", "else",
            "elseif", "foreach", "for", "while", "return", "echo", "print", "new", "try",
            "catch", "finally", "throw", "use", "namespace", "extends", "implements",
            "abstract", "interface", "true", "false", "null", "array", "var", "const",
        )
        SyntaxLanguage.CSS -> listOf(
            "important", "px", "em", "rem", "rgb", "rgba", "hsl", "url", "none",
            "flex", "grid", "block", "inline", "absolute", "relative", "fixed",
        )
        else -> null
    }
}
