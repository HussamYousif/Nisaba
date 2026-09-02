package com.nisaba.hand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scribe's hand invariants (docs/08 §3): deterministic output, finite
 * geometry, full character coverage, sane wrapping and bounds. Pure JVM —
 * no android imports in the module under test.
 */
class ScribeTest {

    private fun strokeArrays(r: Scribe.Result): List<Pair<FloatArray, FloatArray>> {
        val out = ArrayList<Pair<FloatArray, FloatArray>>()
        for (l in r.lines) for (s in l.strokes) out.add(s.xys to s.widths)
        return out
    }

    @Test
    fun sameSeedAndText_areIdentical() {
        val a = Scribe.writeRun("The quick brown fox jumps over the lazy dog.", 1800f, 42L)
        val b = Scribe.writeRun("The quick brown fox jumps over the lazy dog.", 1800f, 42L)
        val sa = strokeArrays(a)
        val sb = strokeArrays(b)
        assertEquals(sa.size, sb.size)
        for (i in sa.indices) {
            assertTrue(sa[i].first.contentEquals(sb[i].first))
            assertTrue(sa[i].second.contentEquals(sb[i].second))
        }
    }

    @Test
    fun differentSeeds_differ() {
        val a = strokeArrays(Scribe.writeRun("minimum minimum", 1800f, 7L))
        val b = strokeArrays(Scribe.writeRun("minimum minimum", 1800f, 8L))
        var diff = false
        for (i in 0 until minOf(a.size, b.size)) {
            if (!a[i].first.contentEquals(b[i].first)) { diff = true; break }
        }
        assertTrue("seeds must vary the hand", diff)
    }

    @Test
    fun geometry_isFiniteAndSane() {
        val corpus = listOf(
            "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.",
            "Nisaba is a magical notebook. When you pause, the ink reaches out and the scribe writes back.",
            "a b c d e f g h i j k l m n o p q r s t u v w x y z",
            "0 1 2 3 4 5 6 7 8 9 . , ! ? ' \" - : ; ( ) /",
            "Hello, World! It's 42° today — really? (Yes!)",
            "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs. " +
                "Nisaba is a magical notebook. When you pause, the ink reaches out and the scribe writes back.",
            "",
            "   ",
            "don't you think that's fine? — I do.",
            "P" + "q".repeat(300),
        )
        for ((ci, text) in corpus.withIndex()) {
            val r = Scribe.writeRun(text, 1900f, 1234L + ci, options = Scribe.Options(variation = 1f))
            for ((li, line) in r.lines.withIndex()) {
                assertTrue("line top sane", line.topUnits < 0f)
                assertTrue("line bottom sane", line.bottomUnits > 0f)
                assertTrue("pitch sane", line.pitchUnits > 100f)
                for (s in line.strokes) {
                    assertTrue("has points", s.pointCount >= 2)
                    for (i in 0 until s.pointCount) {
                        val x = s.xys[i * 2]
                        val y = s.xys[i * 2 + 1]
                        val w = s.widths[i]
                        assertTrue("no NaN on corpus $ci line $li", !x.isNaN() && !y.isNaN() && !w.isNaN())
                        assertTrue("no inf on corpus $ci", x.isFinite() && y.isFinite())
                        assertTrue("width positive corpus $ci w=$w", w > 0.5f && w < 20f)
                        // y stays within a generous band around the baseline
                        assertTrue("y in band corpus $ci", y > -260f && y < 120f)
                        assertTrue("x within width + slack corpus $ci", x < 2000f)
                    }
                    assertTrue("length positive", s.length > 0f)
                }
            }
            assertTrue("height sane", r.heightUnits >= 0f)
        }
    }

    @Test
    fun lineWrapping_respectsWidth() {
        val text = "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs. " +
            "Nisaba is a magical notebook. When you pause, the ink reaches out and the scribe writes back."
        val width = 1900f
        val r = Scribe.writeRun(text, width, 99L)
        assertTrue("wraps into lines", r.lines.size >= 3)
        for (line in r.lines) {
            var maxX = 0f
            for (s in line.strokes) {
                for (i in 0 until s.pointCount) maxX = maxOf(maxX, s.xys[i * 2])
            }
            assertTrue("line fits width (maxX=$maxX)", maxX <= width + 40f)
        }
    }

    @Test
    fun everyLowercaseLetter_writes() {
        for (c in 'a'..'z') {
            val r = Scribe.writeRun("x $c x", 500f, c.code.toLong())
            assertTrue("letter $c writes", !r.isBlank)
            var inked = 0.0
            for (l in r.lines) for (s in l.strokes) inked += s.length.toDouble()
            assertTrue("letter $c has ink", inked > 100f)
        }
    }

    @Test
    fun marks_areDrawnAfterTheirWord() {
        // i-dot and t-cross must be separate pen strokes from the word body,
        // and must sit above the x-height area (y-down: negative-ish region).
        val r = Scribe.writeRun("it", 500f, 5L, options = Scribe.Options(variation = 0f))
        val strokes = r.lines.first().strokes
        assertTrue("word body + two marks", strokes.size >= 3)
        // find the marks: strokes shorter than the body
        val body = strokes.maxBy { it.length }
        val marks = strokes.filter { it !== body }
        assertEquals("two marks", 2, marks.size)
        for (m in marks) {
            var minY = 0f
            for (i in 0 until m.pointCount) minY = minOf(minY, m.xys[i * 2 + 1])
            assertTrue("mark sits above the baseline", minY < -60f)
        }
    }

    @Test
    fun unknownCharacters_scratchInsteadOfDropping() {
        val r = Scribe.writeRun("fish & chips", 500f, 3L)
        // '&' has no glyph; the scratch keeps the word's ink going
        val inked = r.lines.sumOf { l -> l.strokes.sumOf { s -> s.length.toDouble() } }
        assertTrue("still writes around the unknown char", inked > 200.0)
        assertTrue(!r.isBlank)
    }

    @Test
    fun blankInput_isBlank() {
        assertTrue(Scribe.writeRun("", 500f, 1L).isBlank)
        assertTrue(Scribe.writeRun("   ", 500f, 1L).isBlank)
    }

    @Test
    fun wordWidths_areMeasuredConsistently() {
        // wrap must not cut words: every word's own width fits the line width
        val long = "antidisestablishmentarianism supercalifragilisticexpialidocious"
        val width = 400f
        val r = Scribe.writeRun(long, width, 77L, options = Scribe.Options(variation = 0f))
        assertTrue(!r.isBlank)
        for (l in r.lines) {
            for (s in l.strokes) {
                for (i in 0 until s.pointCount) {
                    assertTrue("no runaway x", s.xys[i * 2] < width + 60f)
                }
            }
        }
    }
}
