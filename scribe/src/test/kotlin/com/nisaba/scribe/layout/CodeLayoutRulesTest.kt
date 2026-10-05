package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.HandFixtures
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.hand.wordStrokes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeLayoutRulesTest {
    private val hand = HandFixtures.hand()
    private val region = LayoutRegion.below(PageSize.PORTRAIT, 120f, 300f, 44f)
    private fun lay(b: Block.Code, r: LayoutRegion = region) = layoutBlock(b, LayoutCursor(0, r.top), r, { null }, hand)
    private fun text(w: LaidWord) = w.chars.joinToString("") { it.ch.toString() }

    private val fn = Block.Code("python", listOf("def double(x):", "    return x * 2"))

    @Test fun indentationAndColumnsSurviveOnAMonospaceGrid() {
        val (b, _) = lay(fn)
        val def = b.words.first { text(it) == "def" }
        val ret = b.words.first { text(it) == "return" }
        val x = b.words.first { text(it) == "double(x):" }
        val cell = (x.x - def.x) / 4f                                    // "double" starts in cell 4
        assertEquals(def.x + 4 * cell, ret.x, 0.5f)                      // four spaces of indent = four cells
        assertTrue(ret.baseline > def.baseline)
    }

    @Test fun theCodeSitsInARuledBoxDrawnFirst() {
        val (b, next) = lay(fn)
        assertTrue(b.pathsFirst)
        val box = b.paths.single()
        assertEquals(5, box.xs.size)                                     // a closed rectangle
        assertTrue(b.words.all { w -> w.baseline > box.ys.min() && w.baseline < box.ys.max() })
        assertTrue(next.lineTop > box.ys.max())
    }

    @Test fun longLinesWrapWithAHookAndStayInsideTheRegion() {
        val narrow = LayoutRegion.below(PageSize.PORTRAIT, 1200f, 300f, 44f)
        val (b, _) = lay(Block.Code("", listOf("x = some_function_name(argument_one, argument_two, argument_three)")), narrow)
        assertTrue(b.paths.size >= 2, "box plus at least one continuation hook")
        val over = b.words.filter { it.x + it.width > narrow.right + 1f }.map { "${text(it)}@${it.x}+${it.width}" }
        assertTrue(over.isEmpty(), "past ${narrow.right}: $over")
        assertTrue(b.words.map { it.baseline }.distinct().size >= 2, "rows: ${b.words.map { it.baseline }.distinct()}")
    }

    @Test fun codeLettersAreNeverJoined() {
        val (b, _) = lay(Block.Code("", listOf("minimum")))
        val w = b.words.single()
        val strokes = wordStrokes(w, 44f, 3, HumanParams.CASUAL)
        val natural = w.chars.sumOf { it.glyph.strokes.count { s -> !s.delayed } }
        assertEquals(natural, strokes.count { !it.delayed }, "one pen run per glyph stroke: no links")
    }

    @Test fun everyCommonCodeCharacterHasAGlyphInBothHands() {
        val chars = "abcxyzABCXYZ0123456789(){}[]<>=+-*/%&|!?:;.,'\"_#@^~\\`$"
        for (name in listOf("casual", "formal")) {
            val set = HandFixtures.hand(name)
            val missing = chars.filter { set.resolve(it) == null }
            assertEquals("", missing, "missing in $name")
        }
    }
}
