package com.nisaba.domain.ink

import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkRulesTest {
    private val s = lineStroke("s", 100f, 300f, 500f)

    @Test fun crossingPathHits() {
        val hit = hitStrokes(listOf(s), listOf(PagePoint(200f, 400f), PagePoint(200f, 600f)), radius = 5f)
        assertEquals(listOf(StrokeId("s")), hit)
    }

    @Test fun nearMissDoesNotHitButWidthAndRadiusExtendReach() {
        val path = listOf(PagePoint(200f, 480f), PagePoint(260f, 480f))
        assertTrue(hitStrokes(listOf(s), path, radius = 5f).isEmpty())        // 20 away > 5 + 2
        assertEquals(1, hitStrokes(listOf(s), path, radius = 19f).size)        // 19 + 2 >= 20
    }

    @Test fun singlePointPathHitsNearbyStroke() {
        assertEquals(1, hitStrokes(listOf(s), listOf(PagePoint(150f, 505f)), 5f).size)
        assertTrue(hitStrokes(listOf(s), listOf(PagePoint(150f, 540f)), 5f).isEmpty())
    }

    @Test fun middleCutSplitsIntoTwoPieces() {
        var n = 0
        val out = splitByEraser(s, listOf(PagePoint(200f, 480f), PagePoint(200f, 520f)), 12f) { StrokeId("n${n++}") }
        assertEquals(2, out.size)
        assertTrue(out[0].points.xs.last() < 200f && out[1].points.xs.first() > 200f)
        assertTrue(out.all { it.points.size >= 2 })
        assertEquals(setOf(StrokeId("n0"), StrokeId("n1")), out.map { it.id }.toSet())
    }

    @Test fun endCutLeavesOnePieceAndFullWipeLeavesNone() {
        val end = splitByEraser(s, listOf(PagePoint(300f, 480f), PagePoint(300f, 520f)), 15f) { StrokeId("x") }
        assertEquals(1, end.size)
        val wipe = splitByEraser(s, listOf(PagePoint(90f, 500f), PagePoint(310f, 500f)), 10f) { StrokeId("y") }
        assertTrue(wipe.isEmpty())
    }

    @Test fun untouchedStrokeIsReturnedAsIs() {
        val out = splitByEraser(s, listOf(PagePoint(0f, 0f)), 5f) { StrokeId("z") }
        assertSame(s, out.single())
    }

    @Test fun xHeightDefaultsAndClamps() {
        assertEquals(DEFAULT_X_HEIGHT, estimateXHeight(emptyList()))
        assertEquals(MIN_X_HEIGHT, estimateXHeight(List(5) { tallStroke("a$it", 0f, 0f, 20f) }))
        assertEquals(MAX_X_HEIGHT, estimateXHeight(List(5) { tallStroke("b$it", 0f, 0f, 300f) }))
    }

    @Test fun xHeightIgnoresTallOutliersAndTracksHandSize() {
        val small = List(9) { tallStroke("s$it", it * 40f, 0f, 70f) } + tallStroke("loop", 0f, 0f, 400f)
        val big = List(9) { tallStroke("t$it", it * 40f, 0f, 100f) } + tallStroke("loop", 0f, 0f, 500f)
        assertEquals(70f * 0.55f, estimateXHeight(small), 0.01f)
        assertTrue(estimateXHeight(big) > estimateXHeight(small))
    }

    @Test fun utteranceBoundsUnionsStrokes() {
        assertEquals(PageRect(100f, 500f, 400f, 620f), utteranceBounds(listOf(s, lineStroke("t", 350f, 400f, 620f))))
        assertEquals(null, utteranceBounds(emptyList()))
    }
}
