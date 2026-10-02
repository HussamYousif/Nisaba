package com.nisaba.scribe.hand

import com.nisaba.domain.ink.InkPoints
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandRulesTest {
    private fun line(n: Int, p: Float) = InkPoints(
        FloatArray(n) { 100f + it * 10f }, FloatArray(n) { 200f }, FloatArray(n) { p }, IntArray(n) { it * 8 },
    )

    private fun ys(o: FloatArray) = (1 until o.size step 2).map { o[it] }
    private fun xs(o: FloatArray) = (0 until o.size step 2).map { o[it] }

    @Test fun widthGrowsWithPressureAndIsClamped() {
        val prof = PenProfile.DEFAULT
        assertTrue(strokeWidthAt(1f, prof) > strokeWidthAt(0.3f, prof))
        assertEquals(strokeWidthAt(1f, prof), strokeWidthAt(5f, prof))
        assertEquals(prof.baseWidth, strokeWidthAt(0f, prof))
    }

    @Test fun straightLineOutlineHasExpectedThickness() {
        val o = strokeOutline(line(20, 0.6f))
        val thick = strokeWidthAt(0.6f, PenProfile.DEFAULT)
        val height = ys(o).max() - ys(o).min()
        assertEquals(thick, height, 0.3f)
        assertTrue(xs(o).min() < 100f && xs(o).max() > 290f, "caps extend past the end points")
    }

    @Test fun singlePointBecomesADot() {
        val o = strokeOutline(line(1, 1f))
        assertEquals(14, o.size / 2)
        val w = xs(o).max() - xs(o).min()
        assertEquals(strokeWidthAt(1f, PenProfile.DEFAULT), w, 0.2f)
    }

    @Test fun repeatedIdenticalPointsDoNotProduceNaN() {
        val p = InkPoints(FloatArray(5) { 10f }, FloatArray(5) { 10f }, FloatArray(5) { .5f }, IntArray(5))
        assertTrue(strokeOutline(p).all { it.isFinite() })
    }

    @Test fun smoothingKeepsEndpointsAndBoundsGapSize() {
        val s = smoothStroke(line(6, .5f))
        assertEquals(100f, s.xs.first()); assertEquals(150f, s.xs.last())
        assertTrue(s.size > 6)
        assertTrue((1 until s.size).all { abs(s.xs[it] - s.xs[it - 1]) <= 2.5f * 1.5f })
    }

    @Test fun widthFollowsPressureAlongTheStroke() {
        val pts = InkPoints(
            FloatArray(30) { it * 8f }, FloatArray(30) { 50f }, FloatArray(30) { if (it < 15) 0.1f else 1f }, IntArray(30) { it * 8 },
        )
        val o = strokeOutline(pts)
        val n = (o.size / 2)
        // points on the upper edge (first ~half of the polygon minus cap): thin at start, thick at end
        val topStart = abs(o[1] - 50f); val topEnd = abs(o[(smoothStroke(pts).size - 1) * 2 + 1] - 50f)
        assertTrue(topEnd > topStart * 1.5f, "start=$topStart end=$topEnd n=$n")
    }
}
