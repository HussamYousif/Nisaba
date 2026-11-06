package com.nisaba.scribe.hand

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlyphSetTest {
    @Test fun bothBundledHandsCoverAllPrintableAscii() {
        for (name in listOf("casual", "formal")) {
            val hand = HandFixtures.hand(name)
            for (c in ' '..'~') assertNotNull(hand.glyphs[c], "$name lacks '$c'")
            assertEquals(name, hand.name)
            assertTrue(hand.glyphs['a']!!.strokes.isNotEmpty() && hand.glyphs['a']!!.adv > 0f)
        }
    }

    @Test fun capitalsAreTamedPerHand() {
        assertEquals(1f, HandFixtures.hand("casual").capScale)          // print caps are already ~1.5 x-heights
        assertTrue(HandFixtures.hand("formal").capScale < 0.9f)         // cursive caps are much taller
    }

    @Test fun dotsAndBarsAreDelayedAndComeLast() {
        val i = HandFixtures.hand().glyphs['i']!!
        assertEquals(listOf(false, true), i.strokes.map { it.delayed })
    }

    @Test fun norwegianAndAccentedLettersAreComposed() {
        val hand = HandFixtures.hand()
        for (c in "æøåÆØÅéèêëñüöäçÉÜ") {
            val g = hand.resolve(c)
            assertNotNull(g, "cannot compose '$c'")
            assertTrue(g.strokes.isNotEmpty() && g.adv > 0f, "empty glyph for '$c'")
        }
        val a = hand.glyphs['a']!!; val aring = hand.resolve('å')!!
        assertEquals(a.adv, aring.adv)
        assertTrue(aring.strokes.size > a.strokes.size, "ring accent adds a stroke")
        assertTrue(aring.strokes.last().delayed)
    }

    @Test fun accentOnACapitalSitsAtTheHandsCapHeight() {
        val hand = HandFixtures.hand()
        val ring = hand.resolve('Å')!!.strokes.last().pts
        val ringBottom = (1 until ring.size step 2).minOf { ring[it] }
        assertTrue(ringBottom >= hand.capHeight, "ring bottom $ringBottom vs cap ${hand.capHeight}")
        assertTrue(ringBottom < hand.capHeight + 0.4f, "ring floats too high: $ringBottom")
    }

    @Test fun ticksAndCrossesAreWrittenNotQuestioned() { // unknown chars become "?": "4-10+6=0 ?" read as doubt
        for (c in "✓✔✗✘") assertTrue(HandFixtures.hand().resolve(c)!!.strokes.isNotEmpty(), "no glyph for '$c'")
    }

    @Test fun aMiddleDotIsAPeriodHalfwayUpTheLetters() { // "In 3 steps: A · B · C" read as full stops
        val hand = HandFixtures.hand()
        val dot = hand.resolve('·')!!.strokes.flatMap { s -> s.pts.filterIndexed { i, _ -> i % 2 == 1 } }
        val period = hand.glyphs['.']!!.strokes.flatMap { s -> s.pts.filterIndexed { i, _ -> i % 2 == 1 } }
        assertTrue(dot.min() > period.max(), "dot $dot vs period $period")
    }

    @Test fun unknownCharactersCannotBeComposed() {
        assertEquals(null, HandFixtures.hand().resolve('€'))
    }
}
