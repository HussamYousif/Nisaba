package com.nisaba.scribe.layout

/**
 * How one language looks to the highlighter. [charQuote] = `'` opens a character literal only when it closes within a
 * few characters (so Rust lifetimes and Haskell primes are not taken for strings). [identExtra] = characters that can
 * sit inside a name besides letters, digits and `_` (Clojure's `-?!`, Haskell's `'`). [keywordPrefix] = a character
 * that turns the following name into a literal (Clojure `:keyword`, Ruby/Elixir `:symbol`).
 */
internal class Lang(
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

internal fun words(s: String) = s.trim().split(Regex("""\s+""")).toSet()

internal const val C_BASE = "if else for while do switch case default break continue return goto sizeof struct union enum typedef " +
    "static const extern volatile register void char short int long float double signed unsigned auto inline true false NULL"

private val LANGS_A: Map<String, Lang> = buildMap {
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
}

/** Every language by its fence name (```python): the table is split over two files only for length. */
private val LANGS: Map<String, Lang> = LANGS_A + LANGS_B

/** Unknown languages: the obvious parts of most code (common keywords, `//` `#` `/* */` comments, quoted strings). */
private val GENERIC = Lang(
    listOf("python", "kotlin", "js", "c").flatMapTo(HashSet()) { LANGS.getValue(it).keywords },
    lineComments = listOf("//", "#"), quotes = "\"'`", charQuote = false,
)

internal fun langOf(language: String): Lang = LANGS[language.trim().lowercase()] ?: GENERIC
