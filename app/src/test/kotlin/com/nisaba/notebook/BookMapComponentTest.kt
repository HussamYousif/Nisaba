package com.nisaba.notebook

import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.Concept
import com.nisaba.domain.lesson.TopicStatus
import com.nisaba.domain.shared.PageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMapComponentTest {
    private val parts = listOf("Foundations", "Replication", "Sharding", "Transactions")
    private val long = BookMap(
        "DDIA", concepts = (0 until 40).map { i -> Concept("Concept $i", "", i * 15 + 1, i * 15 + 15, part = parts[i / 10]) },
    )

    @Test fun aLongMapIsShownAScreenfulAtATimeUnderItsParts() {
        val rows = mapRows(long)
        assertEquals(MapRow.Part("Foundations"), rows.first())
        assertEquals(MapRow.Step(null), rows.last()) // the whole picture
        assertEquals(40 + 4 + 1, rows.size)
        val screens = rows.chunkedKeepingParts(18)
        assertTrue(screens.all { it.size <= 18 })
        assertTrue("a part's name never ends a screen", screens.all { it.last() !is MapRow.Part })
        assertEquals(rows, screens.flatten())
    }

    @Test fun aShortMapHasNoPartsAndFitsOneScreen() {
        val short = BookMap("The Prince", concepts = (1..12).map { Concept("C$it", "", it, it) })
        val screens = mapRows(short).chunkedKeepingParts(18)
        assertEquals(1, screens.size)
        assertTrue(screens.single().none { it is MapRow.Part })
    }

    @Test fun theProgressLineCountsWalkedLearnedAndShaky() {
        val walked = long.copy(concepts = long.concepts.mapIndexed { i, c -> if (i < 3) c.copy(pageId = PageId("p$i")) else c })
        val judged = mapOf("concept 0" to TopicStatus.Learned, "concept 1" to TopicStatus.Shaky, "elsewhere" to TopicStatus.Learned)
        assertEquals("3 of 40 walked · 1 learned · 1 to go over", progressLine(walked, judged))
        assertEquals("0 of 40 walked", progressLine(long, emptyMap()))
    }
}
