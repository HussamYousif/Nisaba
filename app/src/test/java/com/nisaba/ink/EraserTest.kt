package com.nisaba.ink

import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke
import com.nisaba.notebook.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stroke-level eraser (docs/04 §5, F2.3, audit P3-3): intersection via
 * point-to-segment distance, the degenerate-segment branch, radius boundary,
 * and eraser-tool strokes never being removed.
 */
class EraserTest {

    private fun pageWith(vararg strokes: Stroke): NotebookPage {
        val p = NotebookPage(index = 0)
        p.strokes = strokes.toList()
        return p
    }

    private fun stroke(id: String, vararg pts: Pair<Float, Float>): Stroke = Stroke(
        id = id,
        points = pts.map { StrokePoint(it.first, it.second, 0.5f, 0L) },
    )

    private fun eraserPath(vararg pts: Pair<Float, Float>): List<StrokePoint> =
        pts.map { StrokePoint(it.first, it.second, 0.5f, 0L) }

    @Test
    fun erase_removesOnlyIntersectingStrokes() {
        val page = pageWith(
            stroke("near", 100f to 100f, 200f to 100f),
            stroke("far", 1000f to 1000f, 1100f to 1000f),
        )
        val removed = Eraser.erase(page, eraserPath(105f to 100f), radius = 16f)
        assertEquals(listOf("near"), removed.map { it.id })
        assertEquals(listOf("far"), page.strokes.map { it.id })
    }

    @Test
    fun erase_hitsAnyPointOfTheStroke() {
        // The eraser passes near the middle of a long stroke — the segment
        // distance (not just endpoints) must catch it.
        val page = pageWith(stroke("long", 0f to 0f, 500f to 0f))
        val removed = Eraser.erase(page, eraserPath(250f to 5f), radius = 16f)
        assertEquals(listOf("long"), removed.map { it.id })
    }

    @Test
    fun erase_neverReturnsEraserToolStrokes() {
        // Eraser-tool strokes never land on the page (InkSurfaceViewTest); if
        // one somehow exists, the erase must not report it as removed ink.
        val eraserStroke = Stroke(
            id = "e1",
            tool = "eraser",
            points = listOf(StrokePoint(100f, 100f, 0.5f, 0L), StrokePoint(200f, 100f, 0.5f, 0L)),
        )
        val page = pageWith(eraserStroke)
        val removed = Eraser.erase(page, eraserPath(150f to 100f))
        assertTrue("eraser strokes must never be reported as removed", removed.isEmpty())
    }

    @Test
    fun erase_degenerateSegment_isTreatedAsAPoint() {
        // distanceToSegment's len2 <= 0 branch: a stroke whose points are
        // identical still has a distance.
        val page = pageWith(stroke("dot", 100f to 100f, 100f to 100f))
        val removed = Eraser.erase(page, eraserPath(108f to 100f), radius = 16f)
        assertEquals(listOf("dot"), removed.map { it.id })
    }

    @Test
    fun erase_radiusBoundary_isExclusive() {
        val page = pageWith(stroke("edge", 100f to 100f, 200f to 100f))
        // Eraser point exactly 16 px from the segment: distance < radius is
        // false, so the stroke survives.
        val removed = Eraser.erase(page, eraserPath(150f to 116f), radius = 16f)
        assertTrue("exactly-at-radius must not erase", removed.isEmpty())
        assertEquals(1, page.strokes.size)
    }

    @Test
    fun erase_emptyPath_isANoOp() {
        val page = pageWith(stroke("s", 0f to 0f, 10f to 10f))
        val removed = Eraser.erase(page, emptyList())
        assertTrue(removed.isEmpty())
        assertEquals(1, page.strokes.size)
    }
}
