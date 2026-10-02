package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.DiagramLabel
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.HandFixtures
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagramLayoutRulesTest {
    private val hand = HandFixtures.hand()
    private val page = PageSize.PORTRAIT
    private val region = LayoutRegion.below(page, 120f, 300f, 44f)
    private fun lay(d: Block.Diagram) = layoutBlock(d, LayoutCursor(0, region.top), region, { null }, hand)

    private val sun = Block.Diagram(
        """<svg viewBox="0 0 1000 600"><circle cx="500" cy="300" r="70"/>""" +
            """<ellipse cx="500" cy="300" rx="380" ry="230"/><path d="M 500 300 L 880 300"/></svg>""",
        listOf(DiagramLabel("Sun", 500, 300), DiagramLabel("orbit", 500, 70), DiagramLabel("1 AU", 690, 285)),
    )

    @Test fun shapesComeFirstAndStayInsideTheRegion() {
        val (b, _) = lay(sun)
        assertTrue(b.pathsFirst && b.paths.size == 3)
        for (p in b.paths) {
            assertTrue(p.xs.all { it >= region.left - 1f && it <= region.right + 1f }, "x range ${p.xs.min()}..${p.xs.max()}")
            assertTrue(p.ys.all { it >= region.top - 1f && it <= region.bottom + 1f })
        }
    }

    @Test fun theDrawingIsFittedAndCentred() {
        val (b, _) = lay(sun)
        val minX = b.paths.minOf { it.xs.min() }; val maxX = b.paths.maxOf { it.xs.max() }
        assertTrue(maxX - minX <= 900.5f, "fits the target width: ${maxX - minX}")
        assertEquals((region.left + region.right) / 2f, (minX + maxX) / 2f, 2f * 0 + 25f)
    }

    @Test fun closedShapesOvershootTheirStartLikeAHand() {
        val (b, _) = lay(Block.Diagram("""<svg><circle cx="500" cy="500" r="100"/></svg>""", emptyList()))
        val p = b.paths.single()
        assertTrue(p.xs.size > 41, "extra points beyond the closing point")
        val gap = hypot(p.xs.last() - p.xs.first(), p.ys.last() - p.ys.first())
        assertTrue(gap > 1f, "the pen runs past where it started")
    }

    @Test fun labelsDoNotTouchStrokesOrEachOther() {
        val (b, _) = lay(sun)
        val boxes = b.words.map { w -> floatArrayOf(w.x, w.baseline - w.scale * 1.1f, w.x + w.width, w.baseline + w.scale * 0.3f) }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val c = boxes[j]
            assertTrue(a[2] <= c[0] || c[2] <= a[0] || a[3] <= c[1] || c[3] <= a[1], "labels $i and $j overlap")
        }
        for (box in boxes) {
            val cx = (box[0] + box[2]) / 2f; val cy = (box[1] + box[3]) / 2f
            for (p in b.paths) for (k in 0 until p.xs.size - 1) {
                assertTrue(hypot(cx - p.xs[k], cy - p.ys[k]) > 8f, "label centre sits on a stroke")
            }
        }
    }

    @Test fun anUnparsableDrawingFallsBackToItsLabelsAsText() {
        val (b, _) = lay(Block.Diagram("<svg><text>nope</text></svg>", listOf(DiagramLabel("Mars", 1, 1), DiagramLabel("Venus", 2, 2))))
        assertTrue(b.paths.isEmpty())
        assertEquals(listOf("Mars", "Venus"), b.words.map { w -> w.chars.map { it.ch }.joinToString("") })
        val (none, _) = lay(Block.Diagram("garbage", emptyList()))
        assertEquals(2, none.words.size, "\"(a drawing)\" is still said")
    }

    @Test fun aDrawingThatDoesNotFitMovesToTheTopOfTheNextPage() {
        val low = LayoutCursor(0, page.h - 500f)                               // far too little room left
        val (b, next) = layoutBlock(sun, low, region, { null }, hand)
        assertEquals(1, next.page)
        assertTrue(b.paths.all { it.page == 1 })
        val top = b.paths.minOf { it.ys.min() }
        val why = "starts at the top margin (${region.pageTop}), not the question's anchor (${region.top}): $top"
        assertTrue(top < region.pageTop + 60f, why)
    }

    @Test fun theCursorMovesBelowTheDrawing() {
        val (b, next) = lay(sun)
        assertTrue(next.lineTop > b.bounds!!.bottom)
    }
}
