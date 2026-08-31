package com.nisaba.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PageTransform is pure math (docs/04 §5): contain-fit scaling, centering,
 * and lossless round-trips between page and view space.
 */
class PageTransformTest {

    @Test
    fun exactMatch_viewportUsesScaleOne() {
        val t = PageTransform().apply {
            pageWidth = 2480
            pageHeight = 1860
            setViewport(2480, 1860)
        }
        assertEquals(1f, t.scale, 1e-6f)
        assertEquals(0f, t.offsetX, 1e-3f)
        assertEquals(0f, t.offsetY, 1e-3f)
    }

    @Test
    fun landscapeViewport_centersVertically() {
        val t = PageTransform().apply {
            pageWidth = 2480
            pageHeight = 1860
            setViewport(2400, 1080)
        }
        // scale = min(2400/2480, 1080/1860)
        assertEquals(1080f / 1860f, t.scale, 1e-6f)
        assertEquals((2400 - 2480 * t.scale) / 2f, t.offsetX, 1e-3f)
        assertEquals(0f, t.offsetY, 1e-3f)
    }

    @Test
    fun portraitViewport_centersHorizontally() {
        val t = PageTransform().apply {
            pageWidth = 2480
            pageHeight = 1860
            setViewport(1080, 2400)
        }
        assertEquals(1080f / 2480f, t.scale, 1e-6f)
        assertEquals(0f, t.offsetX, 1e-3f)
        assertEquals((2400 - 1860 * t.scale) / 2f, t.offsetY, 1e-3f)
        // The page box must stay fully inside the viewport.
        assertTrue(t.toViewY(0f) >= 0f)
        assertTrue(t.toViewY(1860f) <= 2400f)
    }

    @Test
    fun roundTrip_isLossless() {
        val t = PageTransform().apply {
            pageWidth = 2480
            pageHeight = 1860
            setViewport(1600, 1000)
        }
        for (pair in listOf(0f to 0f, 1234.5f to 987.25f, 2480f to 1860f)) {
            val vx = t.toViewX(pair.first)
            val vy = t.toViewY(pair.second)
            assertEquals(pair.first, t.toPageX(vx), 1e-2f)
            assertEquals(pair.second, t.toPageY(vy), 1e-2f)
        }
    }

    @Test
    fun neverUpscalesBeyondNativeResolution() {
        val t = PageTransform().apply {
            pageWidth = 2480
            pageHeight = 1860
            setViewport(4960, 3720)
        }
        assertEquals(1f, t.scale, 1e-6f) // capped at MAX_SCALE
    }
}
