package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.HandFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableLayoutRulesTest {
    private val hand = HandFixtures.hand()
    private val region = LayoutRegion.below(PageSize.PORTRAIT, 120f, 300f, 44f)
    private fun cell(t: String) = listOf<Inline>(Inline.Text(t))
    private fun lay(t: Block.Table, r: LayoutRegion = region, start: LayoutCursor = LayoutCursor(0, r.top)) =
        layoutBlock(t, start, r, { null }, hand)

    private val planets = Block.Table(
        listOf(cell("Planet"), cell("Moons"), cell("Notes")),
        listOf(
            listOf(cell("Mercury"), cell("0"), cell("Closest to the Sun and very hot in daytime")),
            listOf(cell("Jupiter"), cell("95"), cell("Gas giant with a big storm that has lasted for centuries")),
        ),
    )

    @Test fun theGridIsRuledBeforeTheCellsAreWritten() {
        val (b, _) = lay(planets)
        assertTrue(b.pathsFirst)
        assertTrue(b.paths.size >= 3 + 4 + 1, "row rules + double header rule + column rules + top rule: ${b.paths.size}")
        assertTrue(b.words.isNotEmpty())
    }

    @Test fun everyWordStaysInsideItsColumnAndTheRegion() {
        val (b, _) = lay(planets)
        val verticals = b.paths.filter { it.xs[0] == it.xs[1] }.map { it.xs[0] }.sorted().distinct()
        assertEquals(4, verticals.size, "three columns need four vertical rules")
        for (w in b.words) {
            val col = verticals.indexOfLast { it <= w.x + 0.5f }
            assertTrue(col in 0..2, "word starts outside the table")
            assertTrue(w.x + w.width <= verticals[col + 1] + 0.5f, "word '${w.chars.map { it.ch }.joinToString("")}' overflows its column")
        }
        assertTrue(verticals.last() <= region.right + 1f && verticals.first() >= region.left - 1f)
    }

    @Test fun longCellTextWrapsIntoSeveralLines() {
        val (b, _) = lay(planets)
        val notes = b.words.filter { w -> w.line != b.words.first().line }.map { it.baseline }.distinct()
        assertTrue(notes.size >= 4, "wrapped cells produce extra lines: $notes")
    }

    @Test fun aTableThatFitsKeepsItsNaturalWidths() {
        val small = Block.Table(listOf(cell("a"), cell("b")), listOf(listOf(cell("1"), cell("2"))))
        val (b, _) = lay(small)
        val xs = b.paths.filter { it.xs[0] == it.xs[1] }.map { it.xs[0] }.sorted()
        assertTrue(xs.last() - xs.first() < region.width * 0.5f, "a tiny table is not stretched across the page")
    }

    @Test fun aTallTableBreaksBetweenRowsAndRepeatsItsHeader() {
        val rows = List(40) { i -> listOf(cell("Row $i"), cell("$i"), cell("text that wraps around the cell a bit more ".repeat(2))) }
        val (b, next) = lay(Block.Table(listOf(cell("Name"), cell("N"), cell("Note")), rows))
        val pages = b.words.map { it.page }.distinct().sorted()
        assertTrue(pages.size >= 2, "should span pages: $pages")
        assertEquals(pages.last(), next.page)
        val header = b.words.filter { it.bold }
        assertTrue(header.map { it.page }.distinct().size >= 2, "the header is written again on each page")
        assertTrue(b.words.all { it.baseline <= region.bottom + 1f })
    }

    @Test fun fitColumnsNeverGoBelowTheLongestWordWhenThereIsRoom() {
        val w = fitColumns(natural = listOf(300f, 200f, 1500f), minimum = listOf(250f, 150f, 200f), available = 1000f)
        assertTrue(w[0] >= 250f && w[1] >= 150f, "$w")
        assertEquals(1000f, w.sum(), 1f)
        assertTrue(w[2] > w[0], "the wrappable column gets the spare room")
        assertEquals(listOf(300f, 200f), fitColumns(listOf(300f, 200f), listOf(10f, 10f), 1000f))
        val squeezed = fitColumns(listOf(900f, 900f), listOf(800f, 800f), 1000f)
        assertEquals(1000f, squeezed.sum(), 1f)
    }

    @Test fun anEmptyTableChangesNothing() {
        val start = LayoutCursor(0, region.top)
        val (b, next) = lay(Block.Table(emptyList(), emptyList()), start = start)
        assertTrue(b.words.isEmpty() && b.paths.isEmpty()); assertEquals(start, next)
    }
}
