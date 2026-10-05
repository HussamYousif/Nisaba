package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.HandFixtures
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.hand.wordStrokes
import com.nisaba.scribe.layout.InkTone.Comment
import com.nisaba.scribe.layout.InkTone.Keyword
import com.nisaba.scribe.layout.InkTone.Literal
import com.nisaba.scribe.layout.InkTone.Plain
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.speedProfile
import com.nisaba.domain.settings.HandSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeHighlightRulesTest {
    /** Tone of each character of the one line, as a string of letters: P lain, K eyword, L iteral, C omment. */
    private fun tones(line: String, lang: String) = highlightCode(listOf(line), lang).single().joinToString("") { it.name.take(1) }
    private fun at(line: String, lang: String, word: String) = highlightCode(listOf(line), lang).single()[line.indexOf(word)]

    @Test fun pythonKeywordsStringsNumbersAndComments() {
        val line = "def f(x): return \"hi\" + 42  # done"
        assertEquals(Keyword, at(line, "python", "def"))
        assertEquals(Plain, at(line, "python", "f("))
        assertEquals(Keyword, at(line, "python", "return"))
        assertEquals(Literal, at(line, "python", "\"hi\""))
        assertEquals(Literal, at(line, "python", "42"))
        assertEquals(Comment, at(line, "python", "# done"))
        assertTrue(tones(line, "py").endsWith("CCCCCC"))
    }

    @Test fun identifiersThatContainKeywordsOrDigitsArePlain() {
        assertEquals("PPPPPPP", tones("define2", "python"))
        assertEquals("PP", tones("x2 = y", "python").take(2)) // the 2 belongs to the name, it is not a number
    }

    @Test fun blockCommentsAndTripleQuotesCarryAcrossLines() {
        val k = highlightCode(listOf("val a = 1 /* start", "still comment */ fun"), "kotlin")
        assertEquals(Keyword, k[0][0])
        assertEquals(Comment, k[0]["val a = 1 /* start".indexOf("/*")])
        assertTrue(k[1].take("still comment */".length).all { it == Comment })
        assertEquals(Keyword, k[1]["still comment */ fun".indexOf("fun")])
        val p = highlightCode(listOf("s = \"\"\"one", "two\"\"\" if"), "python")
        assertTrue(p[1].take(6).all { it == Literal })
        assertEquals(Keyword, p[1][7])
    }

    @Test fun escapedQuotesStayInsideTheString() {
        val line = "\"a\\\"b\" + c"
        assertTrue(highlightCode(listOf(line), "js").single().take(6).all { it == Literal })
        assertEquals(Plain, at(line, "js", "c"))
    }

    @Test fun sqlIsCaseInsensitiveAndUsesDashComments() {
        assertEquals(Keyword, at("SELECT name FROM t -- all", "sql", "SELECT"))
        assertEquals(Keyword, at("SELECT name FROM t -- all", "sql", "FROM"))
        assertEquals(Comment, at("SELECT name FROM t -- all", "sql", "-- all"))
    }

    @Test fun postgresTypesCastsQuotedIdentifiersAndPlpgsql() {
        val q = "SELECT \"User\".id::text, COALESCE(name, 'n/a') FROM \"User\" WHERE created > now() - interval '1 day' -- pg"
        assertEquals(Keyword, at(q, "postgresql", "SELECT"))
        assertEquals(Plain, at(q, "postgres", "\"User\""))                  // a quoted identifier, not a string
        assertEquals(Keyword, at(q, "postgres", "text"))                      // the ::text cast
        assertEquals(Keyword, at(q, "postgres", "COALESCE"))
        assertEquals(Literal, at(q, "postgres", "'n/a'"))
        assertEquals(Keyword, at(q, "postgres", "interval"))
        assertEquals(Comment, at(q, "postgres", "-- pg"))
        val fn = highlightCode(
            listOf("CREATE FUNCTION add(a int) RETURNS int AS $$", "BEGIN RAISE NOTICE 'x'; RETURN a + 1; END;", "$$ LANGUAGE plpgsql;"),
            "plpgsql",
        )
        assertEquals(Keyword, fn[1][0])                                        // BEGIN inside the body is code, not a string
        assertEquals(Keyword, fn[2]["$$ LANGUAGE plpgsql;".indexOf("LANGUAGE")])
        assertEquals(Literal, at("SELECT 1 /* a */", "pg", "1"))
        assertEquals(Comment, at("SELECT 1 /* a */", "pg", "/* a */"))
    }

    @Test fun cPreprocessorLinesAreNotComments() {
        assertEquals(Plain, at("#include <stdio.h>", "c", "#include"))
        assertEquals(Comment, at("x = 1 // y", "c", "// y"))
    }

    @Test fun haskellCommentsPrimesAndCharacters() {
        val line = "go x' = if x' > 0 then 'a' else 'b' -- pick"
        assertEquals(Keyword, at(line, "haskell", "if"))
        assertEquals(Plain, at(line, "haskell", "x'"))                      // a prime is part of the name
        assertEquals(Literal, at(line, "haskell", "'a'"))
        assertEquals(Comment, at(line, "haskell", "-- pick"))
        val block = highlightCode(listOf("{- a note", "still -} main = 1"), "hs")
        assertTrue(block[0].all { it == Comment } && block[1].take(8).all { it == Comment })
        assertEquals(Literal, block[1]["still -} main = 1".indexOf("1")])
    }

    @Test fun clojureKeywordsSemicolonCommentsAndDashedNames() {
        val line = "(defn greet-user [name] (str \"Hi \" name :done)) ; say hi"
        assertEquals(Keyword, at(line, "clojure", "defn"))
        assertEquals(Plain, at(line, "clojure", "greet-user"))
        assertEquals(Plain, at(line, "clojure", "user"))                   // not split at the dash
        assertEquals(Literal, at(line, "clojure", "\"Hi \""))
        assertEquals(Literal, at(line, "clojure", ":done"))
        assertEquals(Comment, at(line, "clojure", "; say hi"))
    }

    @Test fun dotNetLanguages() {
        val cs = "public async Task<int> Get() { var x = await db.Find(\"a\"); return x; } // c#"
        assertEquals(Keyword, at(cs, "csharp", "public")); assertEquals(Keyword, at(cs, "c#", "await"))
        assertEquals(Plain, at(cs, "cs", "Task")); assertEquals(Comment, at(cs, "cs", "// c#"))
        val fs = "let rec fact n = if n = 0 then 1 else n * fact (n - 1) (* done *)"
        assertEquals(Keyword, at(fs, "fsharp", "rec")); assertEquals(Comment, at(fs, "f#", "(* done *)"))
        val vb = "Dim total As Integer = 0 ' running sum"
        assertEquals(Keyword, at(vb, "vb", "Dim")); assertEquals(Keyword, at(vb, "vb.net", "As"))
        assertEquals(Comment, at(vb, "vb", "' running sum"))
    }

    @Test fun systemsLanguages() {
        val rs = "fn longest<'a>(x: &'a str) -> &'a str { x } // rust"
        assertEquals(Keyword, at(rs, "rust", "fn"))
        assertEquals(Plain, at(rs, "rust", "'a>"))                           // a lifetime, not a char
        assertEquals(Comment, at(rs, "rs", "// rust"))
        val go = "func main() { defer f(); s := `raw` }"
        assertEquals(Keyword, at(go, "go", "defer")); assertEquals(Literal, at(go, "golang", "`raw`"))
        assertEquals(Keyword, at("std::vector<int> v; // c++", "cpp", "int"))
        assertEquals(Keyword, at("guard let x = y else { return }", "swift", "guard"))
    }

    @Test fun scriptingLanguages() {
        assertEquals(Literal, at("puts :sym if ok? # ruby", "ruby", ":sym"))
        assertEquals(Plain, at("puts :sym if ok? # ruby", "rb", "ok?"))
        assertEquals(Comment, at("local x = 1 --[[ note ]] y", "lua", "--[[ note ]]"))
        assertEquals(Keyword, at("local x = 1 --[[ note ]] y", "lua", "local"))
        assertEquals(Keyword, at("defmodule A do", "elixir", "defmodule"))
        assertEquals(Comment, at("x = 1; % erlang", "erlang", "% erlang"))
        assertEquals(Keyword, at("for i = 1:10 % loop", "matlab", "for"))
        assertEquals(Keyword, at("my \$x = 1;", "perl", "my"))
        assertEquals(Keyword, at("echo \$name # php", "php", "echo"))
    }

    @Test fun anUnknownLanguageStillGetsTheObviousParts() {
        assertEquals(Keyword, at("return \"x\"", "", "return"))
        assertEquals(Literal, at("return \"x\"", "", "\"x\""))
    }

    @Test fun tonesReachTheLaidWordsAndThePenStrokes() {
        val region = LayoutRegion.below(PageSize.PORTRAIT, 120f, 300f, 44f)
        val hand = HandFixtures.hand()
        val (b, _) = layoutBlock(Block.Code("python", listOf("return \"hi\"  # c")), LayoutCursor(0, region.top), region, { null }, hand)
        assertEquals(listOf(Keyword, Literal, Comment, Comment), b.words.map { it.tone })
        assertTrue(wordStrokes(b.words[0], 44f, 1, HumanParams.CASUAL).all { it.tone == Keyword })
        val plan = ScribePlanUseCase(hand, region, { null }, 1, HumanParams.CASUAL, speedProfile(HandSpeed.Natural))
        val step = plan(plan.start(), Block.Code("python", listOf("return 1")))
        assertEquals(setOf(Keyword, Literal), step.strokes.filter { it.points.size > 2 }.map { it.tone }.toSet() - Plain)
    }
}
