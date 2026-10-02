package com.nisaba.domain.answer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagramParserTest {
    private fun svg(body: String, vb: String = "0 0 1000 1000") = """<svg viewBox="$vb">$body</svg>"""

    @Test fun linesRectsAndPolylines() {
        val body = """<line x1="0" y1="0" x2="100" y2="50"/><rect x="10" y="20" width="30" height="40"/>""" +
            """<polyline points="0,0 10,10 20,0"/>"""
        val d = parseDiagram(svg(body))!!
        assertEquals(3, d.shapes.size)
        assertEquals(listOf(0f, 100f), d.shapes[0].xs.toList()); assertEquals(listOf(0f, 50f), d.shapes[0].ys.toList())
        assertTrue(d.shapes[1].closed && d.shapes[1].xs.size == 5)
        assertTrue(!d.shapes[2].closed && d.shapes[2].xs.size == 3)
    }

    @Test fun circlesAndEllipsesBecomeClosedOutlinesOfTheRightSize() {
        val c = parseDiagram(svg("""<circle cx="500" cy="500" r="100"/>"""))!!.shapes.single()
        assertTrue(c.closed)
        assertEquals(500f, (c.xs.min() + c.xs.max()) / 2f, 1f)
        assertEquals(200f, c.xs.max() - c.xs.min(), 2f)
        val e = parseDiagram(svg("""<ellipse cx="0" cy="0" rx="50" ry="20"/>"""))!!.shapes.single()
        assertEquals(100f, e.xs.max() - e.xs.min(), 2f); assertEquals(40f, e.ys.max() - e.ys.min(), 2f)
        assertEquals(c.xs.first(), c.xs.last(), 0.01f)                                               // an outline that closes
    }

    @Test fun pathsSupportAbsoluteRelativeAndImplicitCommands() {
        val abs = parseDiagram(svg("""<path d="M 10 10 L 90 10 L 90 90 Z"/>"""))!!.shapes.single()
        assertTrue(abs.closed); assertEquals(4, abs.xs.size); assertEquals(10f, abs.xs.last()); assertEquals(10f, abs.ys.last())
        val rel = parseDiagram(svg("""<path d="m 10 10 l 20 0 0 30 h -20 v -30"/>"""))!!.shapes.single()
        assertEquals(listOf(10f, 30f, 30f, 10f, 10f), rel.xs.toList()); assertEquals(listOf(10f, 10f, 40f, 40f, 10f), rel.ys.toList())
        val implicit = parseDiagram(svg("""<path d="M0 0 10 0 10 10"/>"""))!!.shapes.single()
        assertEquals(3, implicit.xs.size)
    }

    @Test fun curvesAreFlattenedAndEndWhereTheyShould() {
        val q = parseDiagram(svg("""<path d="M0 0 Q 50 100 100 0"/>"""))!!.shapes.single()
        assertTrue(q.xs.size > 5); assertEquals(100f, q.xs.last(), 0.01f); assertEquals(0f, q.ys.last(), 0.01f)
        assertTrue(q.ys.max() in 40f..60f)                                                            // the peak of that parabola is 50
        val c = parseDiagram(svg("""<path d="M0 0 C 0 100 100 100 100 0"/>"""))!!.shapes.single()
        assertEquals(100f, c.xs.last(), 0.01f)
    }

    @Test fun severalSubpathsAreSeparateShapes() {
        assertEquals(2, parseDiagram(svg("""<path d="M0 0 L10 10 M50 50 L60 60"/>"""))!!.shapes.size)
    }

    @Test fun viewBoxIsReadWithASafeDefault() {
        assertEquals(400f, parseDiagram(svg("<line x1='0' y1='0' x2='1' y2='1'/>", "0 0 400 200"))!!.viewW)
        assertEquals(1000f, parseDiagram("<svg><line x1='0' y1='0' x2='1' y2='1'/></svg>")!!.viewW)
    }

    @Test fun unsupportedContentOversizeAndEmptyDrawingsAreRejected() {
        assertNull(parseDiagram("<svg></svg>"))
        assertNull(parseDiagram(svg("<text x='0' y='0'>hello</text>")))                              // no <text>: labels go beside the svg
        assertNull(parseDiagram(svg("<line x1='0' y1='0' x2='1' y2='1'/>".repeat(61))))
        assertNull(parseDiagram(svg("<!-- ${"x".repeat(5000)} --><line x1='0' y1='0' x2='1' y2='1'/>")))
        assertNotNull(parseDiagram(svg("<line x1='0' y1='0' x2='1' y2='1'/>".repeat(60))))
    }

    @Test fun garbageNeverThrows() {
        val rnd = Random(9)
        val alphabet = "MmLlHhVvQqCcSsTtAaZz0123456789 .,-<>/=\"'path d line rect circle svg"
        repeat(3000) { parseDiagram(String(CharArray(rnd.nextInt(0, 90)) { alphabet[rnd.nextInt(alphabet.length)] })) }
        parseDiagram(svg("""<path d="M 1e999 -1e999 L NaN Q Q Q C 1 2 A 1 2 3"/>"""))
    }
}
