package com.nisaba.scribe.layout

/** What is still open at the end of a line: a block comment, or a triple-quoted string. */
private sealed interface Open {
    data object Nothing : Open
    data class Block(val end: String, val tone: InkTone) : Open
}

private const val CHAR_LITERAL_MAX = 4 // 'a' or '\n'; anything longer is a lifetime, a prime or a stray quote

/**
 * Tones for each character of each code line, so the hand can switch pens: keywords, literals (strings, numbers,
 * Clojure/Ruby keywords) and comments. A small, forgiving lexer per language (see [LANGS]); block comments and
 * triple-quoted strings carry on across lines. Never throws; unknown languages get a generic mix.
 */
fun highlightCode(lines: List<String>, language: String): List<Array<InkTone>> {
    val lang = langOf(language)
    var open: Open = Open.Nothing
    return lines.map { line ->
        val lexer = Lexer(line, lang, open)
        open = lexer.run()
        lexer.tones
    }
}

/** Tones one line, token by token; [open] is what the previous line left open, and what this one leaves. */
private class Lexer(private val line: String, private val lang: Lang, private var open: Open) {
    val tones = Array(line.length) { InkTone.Plain }

    fun run(): Open {
        var i = 0
        while (i < line.length) i = step(i)
        return open
    }

    private fun fill(from: Int, to: Int, tone: InkTone) { for (k in from until to.coerceAtMost(line.length)) tones[k] = tone }

    private fun step(i: Int): Int = when (val o = open) {
        is Open.Block -> closeBlock(i, o)
        Open.Nothing -> token(i)
    }

    private fun closeBlock(i: Int, o: Open.Block): Int {
        val at = line.indexOf(o.end, i)
        if (at < 0) { fill(i, line.length, o.tone); return line.length }
        fill(i, at + o.end.length, o.tone)
        open = Open.Nothing
        return at + o.end.length
    }

    private fun opens(i: Int, start: String, end: String, tone: InkTone): Int {
        fill(i, i + start.length, tone)
        open = Open.Block(end, tone)
        return i + start.length
    }

    private fun token(i: Int): Int {
        val c = line[i]
        val block = lang.blockComments.firstOrNull { line.startsWith(it.first, i) }
        val triple = if (lang.tripleQuotes) listOf("\"\"\"", "'''").firstOrNull { line.startsWith(it, i) } else null
        return when {
            block != null -> opens(i, block.first, block.second, InkTone.Comment) // before line comments: `--[[` is a block
            lang.lineComments.any { line.startsWith(it, i) } -> { fill(i, line.length, InkTone.Comment); line.length }
            triple != null -> opens(i, triple, triple, InkTone.Literal)
            c in lang.quotes -> string(i, c)
            c == '\'' && lang.charQuote -> charLiteral(i)
            c.isDigit() && !afterNamePart(i) -> run(i, InkTone.Literal) { it.isLetterOrDigit() || it == '.' || it == '_' }
            c == lang.keywordPrefix && i + 1 < line.length && isNameStart(line[i + 1]) -> run(i, InkTone.Literal, ::isNamePart)
            isNameStart(c) -> word(i)
            else -> i + 1
        }
    }

    private fun isNameStart(c: Char) = c.isLetter() || c == '_'
    private fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_' || c in lang.identExtra
    private fun afterNamePart(i: Int) = i > 0 && isNamePart(line[i - 1])

    /** A quoted string up to its closing quote (backslash escapes), or to the end of the line. */
    private fun string(i: Int, quote: Char): Int {
        var j = i + 1
        while (j < line.length && line[j] != quote) j += if (line[j] == '\\') 2 else 1
        val end = (j + 1).coerceAtMost(line.length)
        fill(i, end, InkTone.Literal)
        return end
    }

    /** `'a'` or `'\n'`; a lone `'` (a lifetime, a prime) is left plain. */
    private fun charLiteral(i: Int): Int {
        val close = line.indexOf('\'', i + 1)
        if (close < 0 || close - i > CHAR_LITERAL_MAX) return i + 1
        fill(i, close + 1, InkTone.Literal)
        return close + 1
    }

    private inline fun run(i: Int, tone: InkTone, keep: (Char) -> Boolean): Int {
        var j = i + 1
        while (j < line.length && keep(line[j])) j++
        fill(i, j, tone)
        return j
    }

    private fun word(i: Int): Int {
        var j = i + 1
        while (j < line.length && isNamePart(line[j])) j++
        val word = line.substring(i, j)
        if ((if (lang.ignoreCase) word.lowercase() else word) in lang.keywords) fill(i, j, InkTone.Keyword)
        return j
    }
}
