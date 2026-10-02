package com.nisaba.domain.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedRulesTest {
    private val a = PageRect(0f, 0f, 10f, 10f)

    @Test fun unionInflateIntersect() {
        assertEquals(PageRect(0f, 0f, 30f, 20f), a.union(PageRect(20f, 5f, 30f, 20f)))
        assertEquals(PageRect(-2f, -2f, 12f, 12f), a.inflate(2f))
        assertTrue(a.intersects(PageRect(10f, 10f, 20f, 20f)))
        assertFalse(a.intersects(PageRect(11f, 0f, 20f, 10f)))
        assertTrue(a.contains(5f, 5f)); assertFalse(a.contains(11f, 5f))
    }

    @Test fun fitTransformCentresAndRoundTrips() {
        val t = fitTransform(PageSize(1860f, 2480f), 930f, 1400f)
        assertEquals(0.5f, t.scale)
        assertEquals(80f, t.dy)
        val v = t.toView(100f, 200f)
        val p = t.toPage(v.x, v.y)
        assertEquals(100f, p.x, 0.001f); assertEquals(200f, p.y, 0.001f)
    }
}
