package com.nisaba.domain.book

import com.nisaba.domain.shared.PageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookRulesTest {
    private val map = BookMap(
        "The Burnout Society", "Achievement replaces discipline; we exploit ourselves.",
        listOf(
            Concept("Achievement society", "", 5, 12), Concept("Neuronal power", "", 13, 20),
            Concept("Profound boredom", "", 21, 30),
        ),
    )

    @Test fun aShortBookIsOnePartALongOneSplitsAtPages() {
        assertEquals(listOf(0..9), bookParts(List(10) { "x".repeat(2_000) }))
        val long = bookParts(List(300) { "y".repeat(2_500) }) // 750k characters
        assertTrue(long.size in 12..14, "${long.size} parts")
        assertEquals(0, long.first().first); assertEquals(299, long.last().last)
        assertTrue(long.zipWithNext().all { (a, b) -> a.last + 1 == b.first }, "no page lost or read twice")
    }

    @Test fun theWalkGoesMapThenEachConceptThenTheWholePicture() {
        var m = map
        assertEquals(BookStep.Overview, m.next)
        m = m.walkedOn(BookStep.Overview, PageId("p1"))
        assertEquals(BookStep.Concept(0), m.next)
        m = m.walkedOn(BookStep.Concept(0), PageId("p2")).walkedOn(BookStep.Concept(1), PageId("p3"))
        assertEquals(BookStep.Concept(2), m.next)
        m = m.walkedOn(BookStep.Concept(2), PageId("p5")).walkedOn(BookStep.Synthesis, PageId("p6"))
        assertNull(m.next)
        assertEquals(3, m.walked)
        assertNull(BookMap("Still reading", reading = BookReading(4)).next) // no walk before the map
    }

    @Test fun aPageBelongsToTheLastConceptWalkedOnItOrBefore() {
        val order = listOf("p1", "p2", "p3", "p4", "p5").map(::PageId)
        val m = map.walkedOn(BookStep.Overview, PageId("p1"))
            .walkedOn(BookStep.Concept(0), PageId("p2")).walkedOn(BookStep.Concept(1), PageId("p4"))
        assertNull(conceptAt(m, order, PageId("p1"))) // the map's page
        assertEquals(0, conceptAt(m, order, PageId("p3"))) // the first concept spilled over
        assertEquals(1, conceptAt(m, order, PageId("p5")))
    }

    @Test fun theInstructionsNameTheStepAndTheSystemPromptCarriesTheMap() {
        val c = stepInstruction(map, BookStep.Concept(1))
        assertTrue("\"2. Neuronal power\"" in c && "pp. 13–20" in c && "\"Achievement society\"" in c, c)
        assertTrue("\"Profound boredom\"" in c, "names what comes next: $c")
        assertTrue("the whole picture" in stepInstruction(map, BookStep.Concept(2)))
        val p = bookPrompt(map)
        assertTrue("3. Profound boredom (pp. 21–30)" in p && "<next/>" in p && "we exploit ourselves" in p, p)
        assertTrue("concept 2 of 3" in walkPlace(map, 1))
        assertFalse("concept" in walkPlace(map, null))
        assertEquals(15, centrePage(map.concepts[1])) // p. 16, the middle of pp. 13–20, 0-based
    }
}
