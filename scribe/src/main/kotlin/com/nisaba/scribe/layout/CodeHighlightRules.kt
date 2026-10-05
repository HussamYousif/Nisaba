package com.nisaba.scribe.layout

/**
 * How one language looks to the highlighter. [charQuote] = `'` opens a character literal only when it closes within a
 * few characters (so Rust lifetimes and Haskell primes are not taken for strings). [identExtra] = characters that can
 * sit inside a name besides letters, digits and `_` (Clojure's `-?!`, Haskell's `'`). [keywordPrefix] = a character
 * that turns the following name into a literal (Clojure `:keyword`, Ruby/Elixir `:symbol`).
 */
private class Lang(
    keywords: Set<String>,
    val lineComments: List<String> = listOf("//"),
    val blockComments: List<Pair<String, String>> = listOf("/*" to "*/"),
    val quotes: String = "\"",
    val charQuote: Boolean = true,
    val tripleQuotes: Boolean = false,
    val ignoreCase: Boolean = false,
    val identExtra: String = "",
    val keywordPrefix: Char? = null,
) {
    /** Keywords as looked up (lower-cased for case-insensitive languages). */
    val keywords: Set<String> = if (ignoreCase) keywords.mapTo(HashSet()) { it.lowercase() } else keywords
}

private fun words(s: String) = s.trim().split(Regex("""\s+""")).toSet()

private const val C_BASE = "if else for while do switch case default break continue return goto sizeof struct union enum typedef " +
    "static const extern volatile register void char short int long float double signed unsigned auto inline true false NULL"

private val LANGS: Map<String, Lang> = buildMap {
    fun add(names: String, lang: Lang) { names.split(' ').forEach { put(it, lang) } }
    add(
        "py python python3",
        Lang(
            words(
                "def class return if elif else for while in not and or is None True False import from as with try except finally " +
                    "raise lambda yield pass break continue global nonlocal assert del async await self match case",
            ),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false, tripleQuotes = true,
        ),
    )
    add(
        "kt kotlin kts",
        Lang(
            words(
                "fun val var class object interface if else when for while do return in is as null true false import package " +
                    "private public internal protected override open data sealed enum companion suspend try catch finally throw " +
                    "this super by init lateinit const typealias inline reified",
            ),
        ),
    )
    add(
        "java",
        Lang(
            words(
                "public private protected class interface extends implements static final void int long double float boolean " +
                    "char byte short new return if else for while do switch case default break continue try catch finally throw " +
                    "throws import package this super null true false enum var record abstract synchronized instanceof",
            ),
        ),
    )
    add(
        "cs csharp c# dotnet .net",
        Lang(
            words(
                "using namespace class struct interface enum record public private protected internal static readonly const " +
                    "void var new return if else for foreach while do switch case default break continue try catch finally throw " +
                    "async await get set init null true false this base override virtual abstract sealed partial string int bool " +
                    "double decimal float long char byte object dynamic lock yield in out ref is as typeof nameof where select " +
                    "from orderby group join let into delegate event operator implicit explicit params",
            ),
        ),
    )
    add(
        "fs fsharp f#",
        Lang(
            words(
                "let mutable rec in fun function match with if then elif else for to downto while do done type module open " +
                    "namespace member static override abstract interface inherit new yield return async true false null not and or " +
                    "of when as use begin end",
            ),
            blockComments = listOf("(*" to "*)"), identExtra = "'",
        ),
    )
    add(
        "vb vbnet vb.net visualbasic",
        Lang(
            words(
                "dim as new sub function end if then else elseif for each next while wend do loop return class module public " +
                    "private protected friend shared imports namespace try catch finally throw select case integer string boolean " +
                    "double true false nothing and or not is mod byval byref property get set with",
            ),
            lineComments = listOf("'"), blockComments = emptyList(), charQuote = false, ignoreCase = true,
        ),
    )
    add(
        "js javascript ts typescript jsx tsx node mjs",
        Lang(
            words(
                "function const let var return if else for while do switch case default break continue new class extends " +
                    "import export from as async await try catch finally throw typeof instanceof null undefined true false this " +
                    "of in interface type enum implements yield static get set readonly",
            ),
            quotes = "\"'`", charQuote = false,
        ),
    )
    add("c h", Lang(words("$C_BASE include define ifdef ifndef endif")))
    add(
        "cpp c++ cc cxx hpp",
        Lang(
            words(
                "$C_BASE class public private protected namespace using template typename new delete this nullptr bool " +
                    "virtual override explicit friend operator constexpr noexcept try catch throw std",
            ),
        ),
    )
    add(
        "go golang",
        Lang(
            words(
                "func package import var const type struct interface map chan go defer select range return if else for switch " +
                    "case default break continue fallthrough nil true false string int bool error byte rune float64",
            ),
            quotes = "\"`",
        ),
    )
    add(
        "rs rust",
        Lang(
            words(
                "fn let mut pub impl trait struct enum match if else for while loop return use mod crate self Self super where " +
                    "as in ref move async await dyn true false const static unsafe type Some None Ok Err",
            ),
        ),
    )
    add(
        "swift",
        Lang(
            words(
                "func let var class struct enum protocol extension import return if else guard for in while repeat switch case " +
                    "default break continue throw throws try catch do self Self nil true false init deinit public private internal " +
                    "static override async await some any",
            ),
            charQuote = false,
        ),
    )
    add(
        "scala sc",
        Lang(
            words(
                "def val var class object trait extends with case match if else for while do yield return import package new " +
                    "this super null true false implicit given using lazy sealed abstract override private protected type",
            ),
        ),
    )
    add(
        "dart",
        Lang(
            words(
                "void var final const class extends implements with abstract import export library return if else for while do " +
                    "switch case default break continue new this super null true false async await yield try catch finally throw " +
                    "dynamic late required static",
            ),
            quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "rb ruby",
        Lang(
            words(
                "def end class module if elsif else unless while until for in do return yield begin rescue ensure raise then " +
                    "case when nil true false self super require attr_accessor and or not puts lambda proc",
            ),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false, identExtra = "?!",
            keywordPrefix = ':',
        ),
    )
    add(
        "php",
        Lang(
            words(
                "function class public private protected static return if else elseif for foreach while do switch case default " +
                    "break continue new echo print namespace use try catch finally throw null true false array as fn match",
            ),
            lineComments = listOf("//", "#"), quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "lua",
        Lang(
            words("function local end if then elseif else for in do while repeat until return break nil true false and or not"),
            lineComments = listOf("--"), blockComments = listOf("--[[" to "]]"), quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "r",
        Lang(
            words("function if else for while repeat in next break return TRUE FALSE NULL NA library"),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false, identExtra = ".",
        ),
    )
    add(
        "ex exs elixir",
        Lang(
            words(
                "def defp defmodule defstruct do end fn if else unless case cond when with for in import alias use require " +
                    "nil true false and or not raise try rescue",
            ),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false, identExtra = "?!",
            keywordPrefix = ':',
        ),
    )
    add(
        "erl erlang",
        Lang(
            words("module export import fun case of if when end receive after try catch begin let true false"),
            lineComments = listOf("%"), blockComments = emptyList(), charQuote = false,
        ),
    )
    add(
        "pl perl",
        Lang(
            words("my our sub if elsif else unless while until for foreach return use package print last next undef"),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "jl julia",
        Lang(
            words("function end if elseif else for while in return module using import struct mutable let begin do true false nothing"),
            lineComments = listOf("#"), blockComments = listOf("#=" to "=#"),
        ),
    )
    add(
        "matlab octave",
        Lang(
            words("function end if elseif else for while switch case otherwise return break continue true false"),
            lineComments = listOf("%"), blockComments = listOf("%{" to "%}"), quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "hs haskell",
        Lang(
            words(
                "module where import qualified as hiding data type newtype class instance deriving let in if then else case of " +
                    "do mdo forall infix infixl infixr True False Nothing Just Left Right return",
            ),
            lineComments = listOf("--"), blockComments = listOf("{-" to "-}"), identExtra = "'",
        ),
    )
    add(
        "clj cljs cljc edn clojure",
        Lang(
            words(
                "def defn defn- defmacro defrecord defprotocol deftype ns let letfn fn if if-not when when-not cond condp case " +
                    "do loop recur doseq dotimes for try catch finally throw quote require import use nil true false and or not",
            ),
            lineComments = listOf(";"), blockComments = emptyList(), charQuote = false, identExtra = "-?!*+<>=/.", keywordPrefix = ':',
        ),
    )
    add(
        "lisp scheme scm racket rkt elisp el",
        Lang(
            words(
                "define defun defvar defparameter defmacro lambda let let* letrec if cond case when unless begin progn loop do " +
                    "and or not quote setq setf nil t else",
            ),
            lineComments = listOf(";"), blockComments = listOf("#|" to "|#"), charQuote = false, identExtra = "-?!*+<>=/",
            keywordPrefix = ':',
        ),
    )
    add(
        "sql mysql sqlite tsql plsql mariadb",
        Lang(
            words(
                "select from where insert into values update set delete create table drop alter join left right inner outer on " +
                    "group by order having limit as and or not null is in like distinct count sum avg min max primary key foreign " +
                    "references index view union all case when then else end with returning",
            ),
            lineComments = listOf("--"), quotes = "'\"", charQuote = false, ignoreCase = true,
        ),
    )
    add(
        // PostgreSQL and PL/pgSQL: double quotes are identifiers ("User"), not strings; `$$` bodies are code, so they
        // are highlighted as such rather than as one long string.
        "postgres postgresql psql pgsql plpgsql pg",
        Lang(
            words(
                "select from where insert into values update set delete create table drop alter join left right inner outer full " +
                    "cross natural lateral using on group by order having limit offset fetch as and or not null is in like ilike " +
                    "similar distinct exists between case when then else end with recursive returning union intersect except all " +
                    "any some over partition window filter within primary key foreign references unique check default constraint " +
                    "index view materialized schema sequence extension trigger function procedure returns return language " +
                    "declare begin commit rollback do loop while for foreach exit continue if elsif raise notice exception " +
                    "perform execute grant revoke cascade restrict truncate vacuum analyze explain copy conflict nothing " +
                    "integer int bigint smallint serial bigserial numeric decimal real double precision boolean bool text " +
                    "varchar char uuid json jsonb date time timestamp timestamptz interval bytea array record void setof " +
                    "true false coalesce nullif greatest least now count sum avg min max cast",
            ),
            lineComments = listOf("--"), quotes = "'", charQuote = false, ignoreCase = true,
        ),
    )
    add(
        "sh bash shell zsh console fish ps powershell ps1",
        Lang(
            words("if then else elif fi for while do done case esac in function return echo export local cd"),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false,
        ),
    )
}

/** Unknown languages: the obvious parts of most code (common keywords, `//` `#` `/* */` comments, quoted strings). */
private val GENERIC = Lang(
    listOf("python", "kotlin", "js", "c").flatMapTo(HashSet()) { LANGS.getValue(it).keywords },
    lineComments = listOf("//", "#"), quotes = "\"'`", charQuote = false,
)

private fun langOf(language: String): Lang = LANGS[language.trim().lowercase()] ?: GENERIC

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
