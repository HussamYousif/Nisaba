package com.nisaba.scribe.layout

import com.nisaba.domain.answer.parseMath
import com.nisaba.scribe.hand.HandFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MathLayoutRulesTest {
    private val hand = HandFixtures.hand()
    private fun box(latex: String, style: MathStyle = MathStyle.Display) = layoutMath(parseMath(latex), hand, style)
    private fun xs(b: MathBox) = b.strokes.flatMap { s -> s.pts.filterIndexed { i, _ -> i % 2 == 0 } }
    private fun ys(b: MathBox) = b.strokes.flatMap { s -> s.pts.filterIndexed { i, _ -> i % 2 == 1 } }

    @Test fun everyStrokeStaysInsideItsBoxHorizontally() {
        val formulas = listOf(
            "x^2+y^2=z^2", "\\frac{a}{b}", "\\sqrt{x+1}", "\\sum_{i=1}^{n} i", "\\int_0^1 x\\,dx",
            "\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}", "\\left(\\frac{a}{b}\\right)",
        )
        for (latex in formulas) {
            val b = box(latex)
            assertTrue(b.width > 0f, latex)
            val inside = xs(b).all { it >= -0.05f && it <= b.width + 0.05f }
            assertTrue(inside, "$latex escapes its box: ${xs(b).min()}..${xs(b).max()} vs ${b.width}")
            assertTrue(ys(b).max() <= b.ascent + 0.05f && ys(b).min() >= b.descent - 0.05f, "$latex vertical extent")
        }
    }

    @Test fun aFractionIsTallerThanItsPartsAndStacksNumeratorAboveDenominator() {
        val f = box("\\frac{a}{b}"); val a = box("a")
        assertTrue(f.ascent > a.ascent && f.descent < a.descent)
        assertTrue(f.ascent > 1.2f && f.descent < -0.2f)
        val bar = f.strokes.first { it.pts.size == 4 && it.pts[1] == it.pts[3] }               // the horizontal fraction bar
        assertEquals(0.5f, bar.pts[1], 0.01f)                                                    // on the math axis
    }

    @Test fun superscriptsRiseAndSubscriptsDropAndBothAreSmaller() {
        val base = box("x"); val sup = box("x^2"); val sub = box("x_2")
        assertTrue(sup.ascent > base.ascent && sup.width > base.width)
        assertTrue(sub.descent < base.descent)
        val smallTwo = box("2", MathStyle.Script).ascent
        assertTrue(smallTwo < box("2").ascent)
    }

    @Test fun rowsAddUpInWidthAndBinaryOperatorsGetSpace() {
        val a = box("a"); val b = box("b"); val plus = box("+")
        val row = box("a+b")
        assertTrue(row.width > a.width + plus.width + b.width, "operator spacing")
        val tight = box("ab")
        assertTrue(tight.width < row.width)
    }

    @Test fun squareRootHasAnOverlineAtLeastAsWideAsItsBody() {
        val r = box("\\sqrt{x+1}"); val body = box("x+1")
        assertTrue(r.width > body.width)
        val over = r.strokes.first { it.pts.size >= 10 }                                           // the radical sign with its overline
        assertTrue(over.pts[over.pts.size - 2] - over.pts[over.pts.size - 4] >= body.width - 0.01f)
    }

    @Test fun bigOperatorsPutTheirLimitsAboveAndBelowButIntegralsBesideTheSign() {
        val sum = box("\\sum_{i=1}^{n}"); val bare = box("\\sum")
        assertTrue(sum.ascent > bare.ascent && sum.descent < bare.descent)
        val integral = box("\\int_0^1"); val bareInt = box("\\int")
        assertTrue(integral.width > bareInt.width, "integral limits sit to the right")
    }

    @Test fun matricesAreFencedAndTwoRowsTallerThanOne() {
        val m = box("\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}")
        assertTrue(m.ascent - m.descent > 2.2f)
        assertTrue(m.strokes.size > 4)
        val one = box("\\begin{pmatrix}a&b\\end{pmatrix}")
        assertTrue(m.ascent - m.descent > one.ascent - one.descent + 0.8f)
    }

    @Test fun greekLettersAndSymbolsHaveGlyphsAndUnknownThingsFallBackToText() {
        for (latex in listOf("\\alpha", "\\Omega", "\\pi", "\\varepsilon", "\\infty", "\\pm", "\\le", "\\to", "\\approx", "\\times")) {
            assertTrue(box(latex).strokes.isNotEmpty(), latex)
        }
        assertTrue(box("\\notacommand").strokes.isNotEmpty(), "written out literally")
    }

    @Test fun layoutIsDeterministic() {
        assertEquals(xs(box("\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}")), xs(box("\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}")))
    }

    @Test fun aFunctionNameIsFollowedByAThinSpaceButNotBeforeAParenthesis() {
        assertTrue(box("\\ln W").width > box("\\text{ln}W").width + 0.1f) // "ln W", not "lnW"
        assertEquals(box("\\text{sin}(x)").width, box("\\sin(x)").width, 0.001f) // "sin(x)"
    }
}
