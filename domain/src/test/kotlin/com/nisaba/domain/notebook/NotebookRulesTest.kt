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
}
