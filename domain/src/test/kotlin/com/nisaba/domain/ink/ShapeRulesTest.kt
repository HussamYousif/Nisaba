package com.nisaba.domain.ink

import com.nisaba.domain.shared.PagePoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeRulesTest {
    private val rnd = Random(7)

    /** A hand-drawn stroke through [corners]: densified with a little jitter. */
    private fun drawn(corners: List<PagePoint>, jitter: Float = 3f): InkPoints {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until corners.size - 1) {
            val a = corners[i]; val b = corners[i + 1]
            for (k in 0 until 30) {
                val t = k / 30f
                xs += a.x + (b.x - a.x) * t + rnd.nextFloat() * jitter
                ys += a.y + (b.y - a.y) * t + rnd.nextFloat() * jitter
            }
        }
        xs += corners.last().x; ys += corners.last().y
        return InkPoints(xs.toFloatArray(), ys.toFloatArray(), FloatArray(xs.size) { 0.5f }, IntArray(xs.size) { it * 8 })
    }

    private fun InkPoints.isClosed() = hypot(xs.first() - xs.last(), ys.first() - ys.last()) < 1f

    @Test fun rectangleDragGivesAClosedBoxOnTheDragsCorners() {
        val s = shapeStroke(ShapeKind.Rectangle, PagePoint(300f, 200f), PagePoint(100f, 400f))
        val b = s.bounds()
        assertEquals(100f, b.left); assertEquals(300f, b.right); assertEquals(200f, b.top); assertEquals(400f, b.bottom)
        assertTrue(s.isClosed())
    }

    @Test fun ellipseStaysInsideItsBox() {
        val s = shapeStroke(ShapeKind.Ellipse, PagePoint(0f, 0f), PagePoint(200f, 100f))
        for (i in 0 until s.size) {
            val nx = (s.xs[i] - 100f) / 100f; val ny = (s.ys[i] - 50f) / 50f
            assertEquals(1f, hypot(nx, ny), 0.02f)
        }
    }

    @Test fun nearlyLevelLineIsMadeLevel() {
        val s = shapeStroke(ShapeKind.Line, PagePoint(0f, 100f), PagePoint(400f, 120f))
        assertTrue(s.ys.all { it == 100f })
        assertEquals(400f, s.xs.last())
    }

    @Test fun arrowEndsWithTwoWingsAtTheTip() {
        val s = shapeStroke(ShapeKind.Arrow, PagePoint(0f, 0f), PagePoint(300f, 300f))
        val tipVisits = (0 until s.size).count { hypot(s.xs[it] - 300f, s.ys[it] - 300f) < 0.5f }
        assertTrue(tipVisits >= 2, "the pen returns to the tip between wings")
    }

    @Test fun roughRectangleSnapsToItsBoundingBox() {
        val corners = listOf(
            PagePoint(100f, 100f), PagePoint(500f, 104f), PagePoint(498f, 400f), PagePoint(102f, 397f), PagePoint(104f, 106f),
        )
        val raw = drawn(corners)
        val clean = assertNotNull(recognizeShape(raw))
        assertTrue(clean.isClosed())
        val b = clean.bounds()
        val onEdge = (0 until clean.size).all {
            val x = clean.xs[it]; val y = clean.ys[it]
            minOf(x - b.left, b.right - x, y - b.top, b.bottom - y) < 0.5f
        }
        assertTrue(onEdge, "every point lies on the box")
    }

    @Test fun roughTriangleKeepsThreeCorners() {
        val raw = drawn(listOf(PagePoint(300f, 100f), PagePoint(500f, 400f), PagePoint(100f, 400f), PagePoint(302f, 104f)))
        val clean = assertNotNull(recognizeShape(raw))
        assertTrue(clean.isClosed())
        val b = clean.bounds()
        assertEquals(100f, b.left, 6f); assertEquals(500f, b.right, 6f); assertEquals(100f, b.top, 6f); assertEquals(400f, b.bottom, 6f)
    }

    @Test fun roughCircleBecomesAnEllipse() {
        val n = 90
        val xs = FloatArray(n + 1) { 300f + 150f * cos(2 * PI * it / n).toFloat() + rnd.nextFloat() * 6f }
        val ys = FloatArray(n + 1) { 300f + 140f * sin(2 * PI * it / n).toFloat() + rnd.nextFloat() * 6f }
        val raw = InkPoints(xs, ys, FloatArray(n + 1) { 0.5f }, IntArray(n + 1) { it * 8 })
        val clean = assertNotNull(recognizeShape(raw))
        assertTrue(clean.isClosed())
    }

    @Test fun roughStraightStrokeBecomesALine() {
        val raw = drawn(listOf(PagePoint(100f, 100f), PagePoint(400f, 330f)))
        val clean = assertNotNull(recognizeShape(raw))
        assertEquals(100f, clean.xs.first(), 4f)
        assertEquals(400f, clean.xs.last(), 4f)
    }

    @Test fun writingAndTinyMarksAreLeftAlone() {
        val zig = listOf(PagePoint(100f, 100f), PagePoint(140f, 200f), PagePoint(180f, 100f), PagePoint(220f, 200f), PagePoint(260f, 100f))
        val squiggle = drawn(zig)
        assertNull(recognizeShape(squiggle))
        val dot = drawn(listOf(PagePoint(100f, 100f), PagePoint(110f, 108f), PagePoint(102f, 112f)), jitter = 1f)
        assertNull(recognizeShape(dot))
    }
}
