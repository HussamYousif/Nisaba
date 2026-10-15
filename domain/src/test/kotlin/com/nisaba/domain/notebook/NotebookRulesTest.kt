package com.nisaba.domain.notebook

import com.nisaba.domain.shared.PageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotebookRulesTest {
    @Test fun trimsAndBoundsName() {
        assertEquals("Physics", validateNotebookName("  Physics  ", emptyList()))
        assertEquals(60, validateNotebookName("x".repeat(200), emptyList()).length)
    }

    @Test fun blankNamesGetCountedDefaults() {
        assertEquals("Untitled", validateNotebookName("  ", emptyList()))
        assertEquals("Untitled 2", validateNotebookName(null, listOf("Untitled")))
        assertEquals("Untitled 3", validateNotebookName("", listOf("untitled", "Untitled 2")))
    }

    @Test fun positionsBetweenNeighbours() {
        assertEquals(1024.0, positionBetween(null, null))
        assertEquals(2048.0, positionBetween(1024.0, null))
        assertEquals(0.0, positionBetween(null, 1024.0))
        assertEquals(1536.0, positionBetween(1024.0, 2048.0))
    }

    @Test fun repeatedMidpointInsertsEventuallyCollapseTheGap() {
        var lo = 1024.0; val hi = 2048.0; var n = 0
        while (!gapTooSmall(lo, hi)) { lo = positionBetween(lo, hi); n++ }
        assertTrue(n in 15..60, "gap collapsed after $n inserts")
    }

    @Test fun rebalanceSpacesEvenly() {
        val r = rebalancedPositions(listOf(PageId("a"), PageId("b"), PageId("c")))
        assertEquals(listOf(1024.0, 2048.0, 3072.0), r.map { it.second })
    }

    @Test fun pdfPagesAreCalledByTheirPrintedNumberAndAddedOnesAfterIt() {
        val doc = com.nisaba.domain.shared.DocumentId("d")
        val nb = com.nisaba.domain.shared.NotebookId("n")
        fun page(i: Int, bg: PageBackground) =
            Page(com.nisaba.domain.shared.PageId("p$i"), nb, i.toDouble(), com.nisaba.domain.shared.PageSize.PORTRAIT, bg)
        val pages = listOf(
            page(0, PageBackground.DocumentPage(doc, 0)), page(1, PageBackground.Blank), page(2, PageBackground.Blank),
            page(3, PageBackground.DocumentPage(doc, 1)),
        )
        assertEquals(listOf("p. 1", "p. 1 +1", "p. 1 +2", "p. 2"), pages.indices.map { pageLabel(pages, it) })
        assertEquals(3, indexOfDocumentPage(pages, 2)); assertEquals(null, indexOfDocumentPage(pages, 9))
        val plain = listOf(page(0, PageBackground.Blank), page(1, PageBackground.Blank))
        assertEquals("2 / 2", pageLabel(plain, 1))
    }
}
