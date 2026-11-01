package com.nisaba.canvas

import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasZoomRulesTest {
    @Test fun aPinchSnapsToTheNearestLevelByRatio() {
        assertEquals(1f, nearestZoom(1.1f))
        assertEquals(0.7f, nearestZoom(0.6f)) // 0.6/0.5 = 1.2 is further than 0.7/0.6 ≈ 1.17
        assertEquals(0.25f, nearestZoom(0.01f))
        assertEquals(2f, nearestZoom(9f))
    }

    @Test fun stepsGoOneLevelAndStopAtTheEnds() {
        assertEquals(1.4f, stepZoom(1f, +1))
        assertEquals(0.7f, stepZoom(1f, -1))
        assertEquals(2f, stepZoom(2f, +1))
        assertEquals(0.25f, stepZoom(0.25f, -1))
    }

    @Test fun theSpotUnderTheFingersStaysPut() {
        val o = PagePoint(1000f, 2000f)
        val fx = 300f; val fy = 500f
        val before = PagePoint(o.x + fx / 1f, o.y + fy / 1f)
        val n = zoomOrigin(o, 1f, 0.5f, fx, fy)
        assertEquals(before.x, n.x + fx / 0.5f, 0.01f)
        assertEquals(before.y, n.y + fy / 0.5f, 0.01f)
    }

    @Test fun fitPicksTheClosestLevelThatShowsItAll() {
        assertEquals(1f, fitZoom(PageRect(0f, 0f, 900f, 1200f), 1860f, 2480f)) // already fits: no closer than 100 %
        assertEquals(0.5f, fitZoom(PageRect(0f, 0f, 3000f, 1000f), 1860f, 2480f))
        assertEquals(0.25f, fitZoom(PageRect(0f, 0f, 50_000f, 1000f), 1860f, 2480f))
        assertEquals("35%", zoomLabel(0.35f))
    }
}
