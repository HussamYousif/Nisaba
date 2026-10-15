package com.nisaba.canvas

import com.nisaba.domain.ink.lineStroke
import com.nisaba.domain.ink.movedBy
import kotlin.test.Test
import kotlin.test.assertEquals

class PageRedrawRulesTest {
    private val a = lineStroke("a", 100f, 200f, 300f)
    private val b = lineStroke("b", 100f, 200f, 400f)
    private val c = lineStroke("c", 100f, 200f, 500f)

    @Test fun writingAppendsAndEverythingElseRebuilds() {
        assertEquals(Redraw.Append(2), redrawOf(listOf(a, b), listOf(a, b, c))) // a new stroke: paint just it
        assertEquals(Redraw.Append(0), redrawOf(emptyList(), listOf(a))) // the first stroke on a blank page
        assertEquals(Redraw.Rebuild, redrawOf(emptyList(), listOf(a, b))) // a page with ink just opened: one pass
        assertEquals(Redraw.Rebuild, redrawOf(listOf(a, b), listOf(a))) // undo / erase
        assertEquals(Redraw.Rebuild, redrawOf(listOf(a, b), listOf(a, b.movedBy(10f, 0f)))) // the lasso moved one: same id, new object
        assertEquals(Redraw.Rebuild, redrawOf(listOf(a, b), listOf(b, a))) // reordered
    }
}
