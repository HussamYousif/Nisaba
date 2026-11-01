package com.nisaba.domain.answer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MathParserTest {
    private fun sym(s: String) = MathNode.Sym(s)
    private fun row(vararg n: MathNode) = MathNode.Row(n.toList())

    @Test fun plainCharactersBecomeSymbolsInARow() {
        assertEquals(row(sym("a"), sym("+"), sym("b"), sym("="), sym("c")), parseMath("a + b = c"))
        assertEquals(sym("x"), parseMath("x"))
    }

    @Test fun floorAndCeilingAreSymbolsNotLiteralCommands() { // a quorum's floor(n/2) + 1 was written as the raw command
        val quorum = row(sym("lfloor"), sym("n"), sym("/"), sym("2"), sym("rfloor"), sym("+"), sym("1"))
        assertEquals(quorum, parseMath("\\lfloor n/2 \\rfloor + 1"))
        for (s in listOf("lfloor", "rfloor", "lceil", "rceil")) assertEquals(sym(s), parseMath("\\$s"))
    }

    @Test fun scriptsAttachToTheAtomBeforeThem() {
        assertEquals(MathNode.Sup(sym("x"), sym("2")), parseMath("x^2"))
        assertEquals(MathNode.Sub(sym("a"), sym("1")), parseMath("a_1"))
        assertEquals(MathNode.SubSup(sym("x"), sym("i"), sym("2")), parseMath("x_i^2"))
        assertEquals(MathNode.Sup(sym("e"), row(sym("i"), sym("pi"))), parseMath("e^{i\\pi}"))
    }

    @Test fun fractionsNestAndSquareRootsTakeAnIndex() {
        val q = parseMath("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}")
        val frac = (q as MathNode.Row).items.last() as MathNode.Frac
        assertEquals(row(sym("2"), sym("a")), frac.den)
        assertTrue((frac.num as MathNode.Row).items.any { it is MathNode.Sqrt })
        assertEquals(MathNode.Sqrt(sym("3"), sym("x")), parseMath("\\sqrt[3]{x}"))
        val nested = parseMath("\\frac{1}{1+\\frac{1}{x}}") as MathNode.Frac
        assertTrue((nested.den as MathNode.Row).items.last() is MathNode.Frac)
    }

    @Test fun bigOperatorsCollectTheirBounds() {
        assertEquals(MathNode.BigOp("sum", sym("i"), sym("n")), parseMath("\\sum_{i}^{n}"))
        val integral = parseMath("\\int_0^1 x\\,dx") as MathNode.Row
        assertEquals(MathNode.BigOp("int", sym("0"), sym("1")), integral.items[0])
        assertEquals(listOf(sym("x"), sym("d"), sym("x")), integral.items.drop(1))          // thin space dropped
        assertEquals(MathNode.BigOp("lim", row(sym("x"), sym("to"), sym("infty")), null), parseMath("\\lim_{x \\to \\infty}"))
    }

    @Test fun greekAndSymbolCommandsMapToNames() {
        assertEquals(row(sym("alpha"), sym("beta"), sym("Omega")), parseMath("\\alpha\\beta\\Omega"))
        assertEquals(row(sym("le"), sym("ge"), sym("ne"), sym("approx")), parseMath("\\leq \\geq \\neq \\approx"))
        assertEquals(sym("infty"), parseMath("\\infty"))
    }

    @Test fun fencesAndMatrices() {
        val f = parseMath("\\left( \\frac{a}{b} \\right)") as MathNode.Fenced
        assertEquals("(" to ")", f.left to f.right)
        assertTrue(f.body is MathNode.Frac)
        val m = parseMath("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}") as MathNode.Matrix
        assertEquals("(" to ")", m.open to m.close)
        assertEquals(listOf(listOf(sym("a"), sym("b")), listOf(sym("c"), sym("d"))), m.rows)
    }

    @Test fun textCommandsKeepTheirWords() {
        assertEquals(row(sym("v"), sym("="), MathNode.Text("fast")), parseMath("v = \\text{fast}"))
    }

    @Test fun unknownCommandsAndBrokenInputNeverThrow() {
        assertEquals(MathNode.Text("\\weird"), parseMath("\\weird"))
        val broken = listOf("{", "}", "\\frac{1", "\\frac", "x^", "^2", "\\left(", "\\right)", "\\begin{pmatrix} 1 &", "\\sqrt[", "\\",
            "\\begin{pmatrix} \\frac{1}{2}} & 0 \\end{pmatrix}", "\\begin{matrix} } \\end{matrix}")
        for (bad in broken + listOf("a_{b_{c", "}}}{{{")) {
            parseMath(bad) // must not throw or loop
        }
    }

    @Test fun aStrayClosingBraceKeepsWhatFollows() {
        val row = parseMath("a } b") as MathNode.Row
        assertEquals(2, row.items.size)
        val m = parseMath("\\begin{pmatrix} 1} & 2 \\end{pmatrix}") as MathNode.Matrix
        assertEquals(2, m.rows.single().size)
    }

    @Test fun randomGarbageNeverThrowsOrHangs() {
        val rnd = Random(4)
        val alphabet = "abc123{}^_\\ &()[]+-=/frac sqrt left right begin end pmatrix sum int"
        repeat(2000) { parseMath(String(CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] })) }
    }

    @Test fun deepNestingIsCappedInsteadOfOverflowingTheStack() {
        parseMath("{".repeat(5000) + "x" + "}".repeat(5000))
        parseMath("\\frac{".repeat(2000) + "x" + "}{1}".repeat(2000))
    }

    @Test fun functionNamesAreWordsNotCommands() {
        val entropy = parseMath("S = k_B \\ln W") as MathNode.Row
        assertTrue(MathNode.Text("ln", function = true) in entropy.items, entropy.toString())
        assertTrue(entropy.items.none { it is MathNode.Text && it.s.startsWith("\\") }) // never "\\ln" on the page
        val sinSquared = (parseMath("\\sin^2 x") as MathNode.Row).items.first()
        assertEquals(MathNode.Sup(MathNode.Text("sin", function = true), MathNode.Sym("2")), sinSquared)
        assertEquals(MathNode.Text("\\foo"), parseMath("\\foo")) // an unknown command is still written as it came
    }
}
