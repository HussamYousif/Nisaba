package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.HandFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextLayoutRulesTest {
    private val hand = HandFixtures.hand()
    private val page = PageSize.PORTRAIT
    private val region = LayoutRegion.below(page, 120f, 300f, 44f)
    private val free = OccupancyLookup { null }
    private fun para(text: String) = Block.Paragraph(listOf(Inline.Text(text)))
    private fun lay(
        b: Block,
        region: LayoutRegion = this.region,
        occ: OccupancyLookup = free,
        cursor: LayoutCursor = LayoutCursor(0, region.top),
    ) =
        layoutBlock(b, cursor, region, occ, hand)

    private val long = "The quick brown fox jumps over the lazy dog and keeps running through the fields until sunset " +
        "when everything turns quiet and the stars come out one by one over the hills"

    @Test fun wrapsWithinTheRegionAndKeepsWordsInOrder() {
        val (block, _) = lay(para(long))
        assertTrue(block.words.map { it.line }.distinct().size > 1, "text should wrap")
        for (w in block.words) assertTrue(w.x + w.width <= region.right + 0.5f, "word overflows: ${w.x + w.width}")
        assertEquals(long.split(' ').size, block.words.size)
        assertTrue(block.words.zipWithNext().all { (a, b) -> b.index == a.index + 1 })
    }

    @Test fun linesAdvanceByTheLineHeightAndTheCursorMovesBelow() {
        val (block, next) = lay(para(long))
        val lines = block.words.groupBy { it.line }.values.map { it.first().baseline }
        assertTrue(lines.zipWithNext().all { (a, b) -> kotlin.math.abs((b - a) - region.lineHeight) < 0.01f })
        assertTrue(next.lineTop > block.bounds!!.bottom)
        assertEquals(block.words.size, next.wordCount)
    }

    @Test fun layoutIsDeterministic() {
        val a = lay(para(long)).first.words.map { it.x to it.baseline }
        val b = lay(para(long)).first.words.map { it.x to it.baseline }
        assertEquals(a, b)
    }

    @Test fun flowsAroundExistingInkByMovingDown() {
        val blocker = Occupancy.of(page, listOf(PageRect(100f, 290f, 900f, 420f)))
        val (block, _) = lay(para("hello world"), occ = { blocker })
        val y = block.words.first().baseline
        assertTrue(y - region.xHeight * 1.7f >= 420f - 12f, "first line must clear the ink, baseline=$y")
        val (free, _) = lay(para("hello world"))
        assertTrue(y > free.words.first().baseline)
    }

    @Test fun spillsToTheNextPageWhenItRunsOutOfRoom() {
        val (block, next) = lay(para(long.repeat(12)))
        assertTrue(block.words.any { it.page == 1 }, "should spill")
        assertEquals(0, block.words.first().page)
        assertEquals(block.words.maxOf { it.page }, next.page)
        val onSecond = block.words.filter { it.page == 1 }
        assertTrue(onSecond.first().baseline - region.xHeight * 1.7f <= region.pageTop + 1f, "new page starts at the top margin")
    }

    @Test fun aSlightlyTooLongWordIsSqueezedOntoTheLine() {
        val (block, _) = lay(para("x".repeat(36)))
        val w = block.words.single()
        assertTrue(w.x + w.width <= region.right + 0.5f)
        assertTrue(w.scale < region.xHeight, "squeezed")
    }

    @Test fun anImpossiblyLongWordIsBrokenAcrossLines() {
        val (block, _) = lay(para("x".repeat(200)))
        assertTrue(block.words.size > 1)
        assertTrue(block.words.all { it.x + it.width <= region.right + 0.5f })
        assertEquals(200, block.words.sumOf { it.chars.size })
    }

    @Test fun headingsAreLargerBoldAndUnderlined() {
        val (h, _) = lay(Block.Heading(1, listOf(Inline.Text("Title"))))
        val (p, _) = lay(para("Title"))
        assertTrue(h.words.first().scale > p.words.first().scale)
        assertTrue(h.words.first().bold)
        assertEquals(1, h.paths.size)
        assertEquals(0, p.paths.size)
    }

    @Test fun listItemsHangTheirContinuationLines() {
        val (b, _) = lay(Block.ListItem(false, "-", 0, listOf(Inline.Text(long))))
        val second = b.words.first { it.line != b.words.first().line }
        assertTrue(second.x > b.words.first().x, "continuation is indented past the marker")
    }

    @Test fun boldRunsAreMarkedAndMathFallsBackToText() {
        val (b, _) = lay(Block.Paragraph(listOf(Inline.Text("plain "), Inline.Text("strong", true), Inline.Math("x^2"))))
        assertEquals(listOf(false, true, false), b.words.map { it.bold })
        val (m, _) = lay(Block.MathBlock("a+b"))
        assertEquals(1, m.words.size)
    }

    @Test fun ruleBlocksProduceOneRuleAndNoWords() {
        val (b, next) = lay(Block.Rule)
        assertTrue(b.words.isEmpty()); assertEquals(1, b.paths.size)
        assertTrue(next.lineTop > region.top)
    }

    @Test fun emptyTextChangesNothing() {
        val start = LayoutCursor(0, region.top)
        val (b, next) = lay(para("   "), cursor = start)
        assertTrue(b.words.isEmpty()); assertEquals(start, next)
    }

    @Test fun aCrossCarriesItsCorrectionWithItsMath() {
        val inl = markInlines(Block.Mark(6, false, "so ${'$'}x=3${'$'}"))
        assertEquals(Inline.Text("✗ ", bold = true), inl.first())
        assertTrue(inl.any { it is Inline.Math }) // not "$x=3$" written out
        assertEquals(listOf(Inline.Text("✓", bold = true)), markInlines(Block.Mark(1, true, "")))
    }
}
