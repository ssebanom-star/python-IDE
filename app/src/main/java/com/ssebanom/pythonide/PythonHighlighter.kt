package com.ssebanom.pythonide

import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * Regex-based Python syntax highlighter. A single alternation pattern is
 * scanned left-to-right so earlier alternatives (strings, comments) take
 * priority over keywords found inside them.
 */
class PythonHighlighter(
    private val keywordColor: Int,
    private val stringColor: Int,
    private val commentColor: Int,
    private val numberColor: Int,
    private val builtinColor: Int,
    private val decoratorColor: Int,
    private val selfColor: Int
) {
    companion object {
        private const val KEYWORDS =
            "False|None|True|and|as|assert|async|await|break|class|continue|" +
            "def|del|elif|else|except|finally|for|from|global|if|import|in|" +
            "is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield|match|case"

        private const val BUILTINS =
            "abs|all|any|ascii|bin|bool|bytearray|bytes|callable|chr|classmethod|" +
            "compile|complex|delattr|dict|dir|divmod|enumerate|eval|exec|filter|" +
            "float|format|frozenset|getattr|globals|hasattr|hash|help|hex|id|" +
            "input|int|isinstance|issubclass|iter|len|list|locals|map|max|" +
            "memoryview|min|next|object|oct|open|ord|pow|print|property|range|" +
            "repr|reversed|round|set|setattr|slice|sorted|staticmethod|str|sum|" +
            "super|tuple|type|vars|zip|__import__|Exception|ValueError|TypeError|" +
            "KeyError|IndexError|RuntimeError|StopIteration|ZeroDivisionError|" +
            "FileNotFoundError|NotImplementedError|AttributeError|OSError"

        private val PATTERN: Pattern = Pattern.compile(
            "(?<STR>\"\"\"[\\s\\S]*?(?:\"\"\"|$)|'''[\\s\\S]*?(?:'''|$)|" +
                "[rRbBfFuU]{0,2}\"(?:[^\"\\\\\\n]|\\\\.)*\"?|" +
                "[rRbBfFuU]{0,2}'(?:[^'\\\\\\n]|\\\\.)*'?)" +
                "|(?<COM>#[^\\n]*)" +
                "|(?<DEC>@[A-Za-z_][\\w.]*)" +
                "|(?<KW>\\b(?:$KEYWORDS)\\b)" +
                "|(?<SELF>\\b(?:self|cls)\\b)" +
                "|(?<BI>\\b(?:$BUILTINS)\\b)" +
                "|(?<NUM>\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|" +
                "\\d[\\d_]*(?:\\.[\\d_]+)?(?:[eE][+-]?\\d+)?[jJ]?)\\b)"
        )
    }

    fun highlight(text: Editable) {
        for (span in text.getSpans(0, text.length, ForegroundColorSpan::class.java)) {
            text.removeSpan(span)
        }
        val m = PATTERN.matcher(text)
        while (m.find()) {
            val color = when {
                m.group("STR") != null -> stringColor
                m.group("COM") != null -> commentColor
                m.group("DEC") != null -> decoratorColor
                m.group("KW") != null -> keywordColor
                m.group("SELF") != null -> selfColor
                m.group("BI") != null -> builtinColor
                m.group("NUM") != null -> numberColor
                else -> continue
            }
            text.setSpan(
                ForegroundColorSpan(color), m.start(), m.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
