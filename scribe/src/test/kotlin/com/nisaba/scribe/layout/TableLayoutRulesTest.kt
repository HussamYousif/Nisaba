package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.HandFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableLayoutRulesTest {
    private val hand = HandFixtures.hand()
    private val region = LayoutRegion.below(PageSize.PORTRAIT, 120f, 300f, 44f)
    private fun cell(t: String) = listOf<Inline>(Inline.Text(t))
    private fun lay(
        t: Block.Table,
        r: LayoutRegion = region,
        start: LayoutCursor = LayoutCursor(0, r.top),
        occ: Occupancy? = null,
    ) = layoutBlock(t, start, r, { if (it == 0) occ else null }, hand)

    /** Every word starts inside a column and ends before that column's right rule. */
    private fun assertWordsInColumns(b: LaidBlock) {
        val verticals = b.paths.filter { it.xs[0] == it.xs[1] }.map { it.xs[0] }.sorted().distinct()
        for (w in b.words) {
            val col = verticals.indexOfLast { it <= w.x + 0.5f }
            assertTrue(col in 0 until verticals.lastIndex, "word starts outside the table")
            assertTrue(w.x + w.width <= verticals[col + 1] + 0.5f, "word '${w.chars.map { it.ch }.joinToString("")}' overflows its column")
        }
    }

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

    @Test fun rowsNeverDrawOverInkAlreadyOnThePage() {
        val occ = Occupancy.empty(PageSize.PORTRAIT)
        val note = PageRect(0f, region.top + 400f, PageSize.PORTRAIT.w, region.top + 600f) // a note across the page
        occ.mark(note)
        val rows = List(8) { i -> listOf(cell("Row $i"), cell("$i"), cell("short")) }
        val (b, _) = lay(Block.Table(listOf(cell("Name"), cell("N"), cell("Note")), rows), occ = occ)
        for (w in b.words.filter { it.page == 0 }) {
            val top = w.baseline - w.scale * 1.5f
            assertTrue(w.baseline < note.top || top > note.bottom, "a word at ${w.baseline} sits on the note")
        }
        for (p in b.paths.filter { it.page == 0 }) {
            assertTrue(p.ys.max() <= note.top + 1f || p.ys.min() >= note.bottom - 1f, "a rule crosses the note: ${p.ys.toList()}")
        }
        assertEquals(8, b.words.count { w -> w.chars.joinToString("") { it.ch.toString() } == "short" }, "every row is written")
    }

    @Test fun aWideTableInANarrowSpaceSqueezesItsWordsIntoTheColumns() {
        val narrow = LayoutRegion.below(PageSize.PORTRAIT, 1150f, 300f, 44f)
        val head = listOf("Element", "Symbol", "Number", "Mass", "Category", "Discovered").map(::cell)
        val row = listOf("Hydrogen", "H", "1", "1.008", "Nonmetal", "Cavendish").map(::cell)
        val (b, _) = lay(Block.Table(head, listOf(row, row)), r = narrow)
        assertWordsInColumns(b)
    }
}
