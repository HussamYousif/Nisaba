package com.nisaba.scribe.hand

import com.nisaba.scribe.layout.LaidWord
import com.nisaba.scribe.layout.PlacedChar
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HandHumanizeRulesTest {
    private val hand = HandFixtures.hand()

    private fun word(text: String, bold: Boolean = false, index: Int = 0): LaidWord {
        var x = 0f
        val xh = 44f
        val chars = text.map { c -> val g = hand.resolve(c)!!; PlacedChar(c, g, x, 1f).also { x += g.adv * xh } }
        return LaidWord(0, 200f, 500f, xh, chars, x, bold, 0, index)
    }

    private fun flat(w: List<LaidStroke>) = w.flatMap { s -> s.xs.toList() + s.ys.toList() }

    @Test fun sameSeedSameInkDifferentSeedDifferentInk() {
        val a = wordStrokes(word("hello"), 44f, 7, HumanParams.CASUAL)
        val b = wordStrokes(word("hello"), 44f, 7, HumanParams.CASUAL)
        val c = wordStrokes(word("hello"), 44f, 8, HumanParams.CASUAL)
        assertEquals(flat(a), flat(b))
        assertNotEquals(flat(a), flat(c))
    }

    @Test fun repeatedLettersAreNeverIdentical() {
        val w = wordStrokes(word("ll"), 44f, 3, HumanParams.CASUAL)
        val byChar = w.groupBy { it.charIndex }.values.map { g -> g.flatMap { it.xs.toList() } }
        val shifted = byChar[1].map { it - byChar[1][0] }; val first = byChar[0].map { it - byChar[0][0] }
        assertNotEquals(first, shifted)
    }

    @Test fun perfectHandIsAPureScaleOfTheGlyphs() {
        val w = wordStrokes(word("l"), 44f, 1, HumanParams.PERFECT)
        val g = hand.glyphs['l']!!.strokes[0].pts
        val s = w[0]
        assertEquals(200f + g[0] * 44f, s.xs[0], 0.01f)
        assertEquals(500f - g[1] * 44f, s.ys[0], 0.01f)          // y flips: glyph y is up, page y is down
    }

    @Test fun distortionStaysBoundedAroundThePerfectRender() {
        val perfect = wordStrokes(word("water"), 44f, 1, HumanParams.PERFECT)
        val human = wordStrokes(word("water"), 44f, 1, HumanParams.CASUAL)
        assertEquals(perfect.size, human.size)
        val maxOff = perfect.zip(human).maxOf { (p, h) -> p.xs.indices.maxOf { i -> abs(p.xs[i] - h.xs[i]) + abs(p.ys[i] - h.ys[i]) } }
        assertTrue(maxOff < 44f * 0.9f, "too wild: $maxOff px")
        assertTrue(maxOff > 0.5f, "should differ from perfect")
    }

    @Test fun delayedStrokesComeAfterTheRegularOnes() {
        val w = wordStrokes(word("it"), 44f, 1, HumanParams.CASUAL)
        val firstDelayed = w.indexOfFirst { it.delayed }
        assertTrue(firstDelayed > 0)
        assertTrue(w.drop(firstDelayed).all { it.delayed })
    }

    @Test fun pressureIsBoundedBoldIsHeavierAndDotsAreStabs() {
        val normal = wordStrokes(word("l"), 44f, 1, HumanParams.PERFECT)[0].ps
        val bold = wordStrokes(word("l", bold = true), 44f, 1, HumanParams.PERFECT)[0].ps
        assertTrue(normal.all { it in 0f..1f } && bold.all { it in 0f..1f })
        assertTrue(bold.average() > normal.average())
        assertTrue(normal.first() < normal.max() && normal.last() < normal.max(), "soft landing and lift-off")
        val dot = wordStrokes(word("i"), 44f, 1, HumanParams.PERFECT).first { it.delayed }
        assertTrue(dot.ps.all { it >= 0.85f }, "dots are heavy")
    }

    @Test fun noiseIsDeterministicAndBounded() {
        assertEquals(unitNoise(5, 1, 2), unitNoise(5, 1, 2))
        assertTrue((0 until 500).all { unitNoise(9, it) in -1f..1f })
        assertTrue((0 until 500).all { gaussNoise(9, it) in -1.5f..1.5f })
        val mean = (0 until 2000).map { unitNoise(1, it) }.average()
        assertTrue(abs(mean) < 0.1, "roughly centred: $mean")
    }

    @Test fun theBaselineSlopeRunsFromTheStartOfTheLineNotTheWord() {
        val slopeOnly = HumanParams.PERFECT.copy(baseSlopeDeg = 3f)
        val w = word("o")
        fun startY(lineX: Float) = wordStrokes(
            LaidWord(w.page, 1200f, w.baseline, w.scale, w.chars, w.width, false, 0, 0, lineX), 44f, 7, slopeOnly,
        ).first().ys.first()
        val asLineStart = startY(1200f)
        val laterInTheLine = startY(200f)
        assertTrue(abs(laterInTheLine - asLineStart) > 1f, "a word far along the line has drifted: ${laterInTheLine - asLineStart}")
    }

    @Test fun linesDriftButOnlyALittle() {
        val xs = (0..1500 step 100).map { lineWander(11, 2, it.toFloat(), 0f, 44f, HumanParams.CASUAL) }
        assertTrue(xs.distinct().size > 3)
        assertTrue(xs.all { abs(it) < 44f * 0.6f })
        assertEquals(0f, lineWander(11, 2, 500f, 0f, 44f, HumanParams.PERFECT))
    }
}
