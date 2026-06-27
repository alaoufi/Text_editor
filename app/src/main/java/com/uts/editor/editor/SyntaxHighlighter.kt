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

    /** A token category; mapped to a concrete colour from the active theme at draw time. */
    private enum class TokenKind { COMMENT, STRING, KEYWORD, NUMBER, TAG, ATTRIBUTE }

    private data class Rule(val regex: Regex, val kind: TokenKind)

    /**
     * Compiled regex rules are independent of the theme colours, so they are
     * built once per language and reused — recompiling them on every
     * recomposition was the main highlighting cost.
     */
    private val ruleCache = HashMap<SyntaxLanguage, List<Rule>>()

    fun highlight(text: String, language: SyntaxLanguage, colors: SyntaxColors): AnnotatedString {
        if (language == SyntaxLanguage.PLAIN || text.length > MAX_HIGHLIGHT_CHARS) {
            return AnnotatedString(text)
        }
        val rules = ruleCache.getOrPut(language) { rulesFor(language) }
        return buildAnnotatedString {
            append(text)
            // Track consumed ranges so a keyword inside a string isn't recoloured.
            val taken = BooleanArray(text.length)
            for (rule in rules) {
                val style = styleFor(rule.kind, colors)
                for (m in rule.regex.findAll(text)) {
                    val r = m.range
                    if (r.isEmpty()) continue
                    if (taken[r.first]) continue
                    addStyle(style, r.first, r.last + 1)
                    for (i in r.first..r.last) taken[i] = true
                }
            }
        }
    }

    private fun styleFor(kind: TokenKind, c: SyntaxColors): SpanStyle = when (kind) {
        TokenKind.COMMENT -> SpanStyle(color = c.comment, fontWeight = FontWeight.Normal)
        TokenKind.STRING -> SpanStyle(color = c.string)
        TokenKind.KEYWORD -> SpanStyle(color = c.keyword, fontWeight = FontWeight.Bold)
        TokenKind.NUMBER -> SpanStyle(color = c.number)
        TokenKind.TAG -> SpanStyle(color = c.tag, fontWeight = FontWeight.Bold)
        TokenKind.ATTRIBUTE -> SpanStyle(color = c.attribute)
    }

    private fun rulesFor(language: SyntaxLanguage): List<Rule> {
        // Order is significant: comments and strings must win over keywords.
        val commentRules: List<Rule> = when (language) {
            SyntaxLanguage.SQL -> listOf(Rule(Regex("--[^\n]*"), TokenKind.COMMENT), Rule(Regex("/\\*[\\s\\S]*?\\*/"), TokenKind.COMMENT))
            SyntaxLanguage.PYTHON -> listOf(Rule(Regex("#[^\n]*"), TokenKind.COMMENT))
            SyntaxLanguage.HTML, SyntaxLanguage.XML -> listOf(Rule(Regex("<!--[\\s\\S]*?-->"), TokenKind.COMMENT))
            SyntaxLanguage.CSS -> listOf(Rule(Regex("/\\*[\\s\\S]*?\\*/"), TokenKind.COMMENT))
            // JSON has no comments; everything else uses C-style comments.
            SyntaxLanguage.JSON -> emptyList()
            else -> listOf(Rule(Regex("//[^\n]*"), TokenKind.COMMENT), Rule(Regex("/\\*[\\s\\S]*?\\*/"), TokenKind.COMMENT))
        }

        val stringRules = listOf(
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), TokenKind.STRING),
            Rule(Regex("'(?:\\\\.|[^'\\\\])*'"), TokenKind.STRING),
            Rule(Regex("`(?:\\\\.|[^`\\\\])*`"), TokenKind.STRING),
        )

        val numberRule = Rule(Regex("\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b"), TokenKind.NUMBER)

        val keywordRule: Rule? = keywordsFor(language)?.let { words ->
            Rule(Regex("\\b(?:${words.joinToString("|")})\\b"), TokenKind.KEYWORD)
        }

        val tagRules: List<Rule> = when (language) {
            SyntaxLanguage.HTML, SyntaxLanguage.XML -> listOf(
                Rule(Regex("</?[A-Za-z_][\\w:-]*"), TokenKind.TAG),
                Rule(Regex(">"), TokenKind.TAG),
                Rule(Regex("[A-Za-z_][\\w:-]*(?==)"), TokenKind.ATTRIBUTE),
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
        SyntaxLanguage.JSON -> listOf("true", "false", "null")
        else -> null
    }
}
