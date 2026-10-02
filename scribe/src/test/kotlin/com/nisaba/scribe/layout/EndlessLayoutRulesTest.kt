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
}
