package com.nisaba.domain.book

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookParserTest {
    @Test fun ideasComeWithTheirPagesClampedToTheBook() {
        val reply = """
            <idea pages="12-15">Tiredness society :: Exhaustion as the age's illness</idea>
            <idea pages="40">Multitasking :: An animal's attention, not progress</idea>
            <idea pages="90-80">Out of range</idea>
        """.trimIndent()
        val ideas = parseIdeas(reply, pageCount = 60)
        assertEquals(3, ideas.size)
        assertEquals(BookIdea("Tiredness society", "Exhaustion as the age's illness", 12, 15), ideas[0])
        assertEquals(40 to 40, ideas[1].firstPage to ideas[1].lastPage)
        assertEquals(60 to 60, ideas[2].firstPage to ideas[2].lastPage)
    }

    @Test fun theMapHasAThesisAndEnoughConcepts() {
        val reply = """
            <thesis>We are no longer
            disciplined from outside; we exploit ourselves.</thesis>
            <concept pages="5-12">Achievement society :: Can replaces should</concept>
            <concept pages="13–20">Neuronal power :: Illnesses of excess positivity</concept>
            <concept pages="21-30">Profound boredom :: Rest is where the new comes from</concept>
            <concept pages="21-30">profound boredom :: a repeat</concept>
        """.trimIndent()
        val map = assertNotNull(parseBookMap(reply, "the-burnout-society", 70))
        assertEquals("We are no longer disciplined from outside; we exploit ourselves.", map.thesis)
        assertEquals(listOf("Achievement society", "Neuronal power", "Profound boredom"), map.concepts.map { it.title })
        assertEquals(13 to 20, map.concepts[1].firstPage to map.concepts[1].lastPage)
        assertNull(parseBookMap("<concept pages=\"1\">Only one</concept>", "Thin", 10))
        assertEquals("the-burnout-society", map.title) // no <book>: the file's name
        val named = parseBookMap("<book>The Burnout Society — Byung-Chul Han</book>\n$reply", "the-burnout-society", 70)!!
        assertEquals("The Burnout Society — Byung-Chul Han", named.title)
    }

    @Test fun nextIsAnInstructionNeverInk() {
        assertTrue(hasNextTag("<next/><heard>neste</heard>"))
        assertTrue(hasNextTag("<Next />"))
        assertFalse(hasNextTag("<answer>the next idea</answer>"))
        assertEquals("Good. ", stripNextTags("Good. <next/>"))
        assertNotNull(nextTagAtStart("<next/>\n<heard>next</heard>"))
        assertNull(nextTagAtStart("Yes. <next/>"))
    }
}
