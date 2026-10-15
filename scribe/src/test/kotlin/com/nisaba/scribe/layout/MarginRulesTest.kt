package com.nisaba.scribe.layout

import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarginRulesTest {
    private val page = PageSize.PORTRAIT

    @Test fun aMarkSitsRightOfItsLineOnTheSameBaselineElseInTheMarginElseBelow() {
        val short = PageRect(200f, 400f, 700f, 450f)
        val r = markRegion(page, short, 30f)
        assertTrue(r.left > short.right && r.left < short.right + 60f)
        assertEquals(short.bottom, r.top + 30f * 1.9f, 0.5f) // baseline (top + 1.7 xh) a little above the line's bottom
        val toTheEdge = PageRect(400f, 400f, page.w - 40f, 450f)
        assertTrue(markRegion(page, toTheEdge, 30f).right < toTheEdge.left)
        val everywhere = PageRect(20f, 400f, page.w - 20f, 450f)
        assertTrue(markRegion(page, everywhere, 30f).top >= everywhere.bottom)
    }

    @Test fun aGlossGoesInTheMarginBesideThePrintedText() {
        // printed text in a column from x = 200 to 1500: the right margin (1500..1860) is free
        val printed = Occupancy.of(page, (0 until 30).map { PageRect(200f, 200f + it * 60f, 1500f, 230f + it * 60f) }, pad = 0f)
        val inMargin = PageRect(1560f, 600f, 1800f, 650f) // they wrote "?" in the right margin
        val r = assertNotNull(glossRegion(page, printed, inMargin, 22f))
        assertTrue(r.left >= 1500f && r.right <= page.w)
        assertTrue(r.top > inMargin.bottom) // under their question, in the same margin
        val circledInText = PageRect(600f, 800f, 900f, 840f)
        val beside = assertNotNull(glossRegion(page, printed, circledInText, 22f))
        assertTrue(beside.left >= 1500f && beside.top == circledInText.top) // the widest margin, level with it
    }

    @Test fun aSparsePageWithRoomUnderTheQuestionWritesThereAsUsual() {
        // two printed lines at the top, then blank: a question on them is answered just below the text
        val sparse = Occupancy.of(page, listOf(PageRect(200f, 300f, 1500f, 340f), PageRect(200f, 360f, 1500f, 400f)), pad = 0f)
        assertNull(glossRegion(page, sparse, PageRect(250f, 280f, 900f, 360f), 22f))
    }

    @Test fun noGlossWithoutPrintedTextAlongsideOrWithoutRoom() {
        assertNull(glossRegion(page, Occupancy.empty(page), PageRect(200f, 300f, 600f, 340f), 22f))
        val wall = Occupancy.of(page, (0 until 40).map { PageRect(10f, 100f + it * 55f, page.w - 10f, 130f + it * 55f) }, pad = 0f)
        assertNull(glossRegion(page, wall, PageRect(200f, 300f, 600f, 340f), 22f))
    }
}
