package com.nisaba.scribe.layout

import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndlessLayoutRulesTest {
    private val o = PageSize.ENDLESS_ORIGIN

    @Test fun endlessRegionIsAColumnFromTheAnchor() {
        val r = LayoutRegion.below(PageSize.ENDLESS, o + 300f, o + 900f, 40f)
        assertEquals(o + 300f, r.left)
        assertEquals(LayoutRegion.ENDLESS_ANSWER_WIDTH, r.width)
        assertEquals(o + 900f + LayoutRegion.ENDLESS_ANSWER_HEIGHT, r.bottom)
    }

    @Test fun pagedRegionStillFillsThePage() {
        val r = LayoutRegion.below(PageSize.PORTRAIT, 200f, 300f, 40f)
        assertEquals(PageSize.PORTRAIT.w - LayoutRegion.MARGIN, r.right)
    }

    @Test fun windowOccupancyMarksInPageCoordinates() {
        val occ = Occupancy.window(PageRect(o, o, o + 800f, o + 800f))
        occ.mark(PageRect(o + 100f, o + 100f, o + 200f, o + 150f))
        assertFalse(occ.isFree(PageRect(o + 120f, o + 110f, o + 140f, o + 130f)))
        assertTrue(occ.isFree(PageRect(o + 400f, o + 400f, o + 500f, o + 500f)))
    }

    @Test fun inkFarOutsideTheWindowNeitherMarksNorBlocks() {
        val occ = Occupancy.window(PageRect(o, o, o + 800f, o + 800f))
        occ.mark(PageRect(o - 5000f, o - 5000f, o - 4000f, o - 4000f))
        occ.mark(PageRect(o + 9000f, o + 100f, o + 9100f, o + 200f))
        assertTrue(occ.isFree(PageRect(o, o, o + 800f, o + 800f)))
        assertTrue(occ.isFree(PageRect(o - 9000f, o, o - 8000f, o + 50f)))
    }

    @Test fun aLongAnswerOnACanvasGrowsDownInsteadOfSpillingToAPage() {
        val hand = com.nisaba.scribe.hand.HandFixtures.hand()
        val region = LayoutRegion.below(PageSize.ENDLESS, o + 300f, o + 900f, 44f)
        val words = "a long reply that goes on and on ".repeat(900)
        val para = com.nisaba.domain.answer.Block.Paragraph(listOf(com.nisaba.domain.answer.Inline.Text(words)))
        val (block, _) = layoutBlock(para, LayoutCursor(0, region.top), region, OccupancyLookup { null }, hand)
        assertTrue(block.words.all { it.page == 0 }, "a canvas has no next page")
        assertTrue(block.words.maxOf { it.baseline } > region.bottom, "the column went on past the usual height")
    }

    @Test fun aPageTooShortForOneLineStillEnds() {
        val hand = com.nisaba.scribe.hand.HandFixtures.hand()
        val strip = PageSize(4000f, 300f) // wider than 4:1: not even one line fits under the margins
        val region = LayoutRegion.below(strip, 200f, 150f, 44f)
        val para = com.nisaba.domain.answer.Block.Paragraph(listOf(com.nisaba.domain.answer.Inline.Text("still written")))
        val (block, _) = layoutBlock(para, LayoutCursor(0, region.top), region, OccupancyLookup { null }, hand)
        assertEquals(2, block.words.size) // and the layout came back
    }
}
