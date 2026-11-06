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

    private val ddia = BookMap(
        "Designing Data-Intensive Applications", "Trade-offs everywhere.",
        listOf(
            Concept("Leaders and followers", "", 151, 160, part = "Replication"),
            Concept("Replication lag", "", 161, 170, part = "Replication"),
            Concept("Partitioning by key", "", 199, 210, part = "Sharding"),
        ),
    )

    @Test fun aLongBookGetsAMapAboutOneConceptPerSevenPagesOfText() {
        assertEquals(5..12, conceptRange(List(80) { "x".repeat(3_800) }))   // The Prince: its core ideas
        assertEquals(53..80, conceptRange(List(600) { "x".repeat(2_600) })) // DDIA: at most 80
        assertEquals(28..42, conceptRange(List(300) { "x".repeat(2_500) }))
        assertEquals(5..12, conceptRange(List(300) { "x".repeat(200) }))    // 300 slides are no long book
        val long = mapInstructionWhole("DDIA", 600, 20..40)
        assertTrue("Between 20 and 40 concepts" in long && "<part>" in long, long)
        val short = mapInstructionWhole("The Prince", 80, 5..12)
        assertTrue("Between 5 and 12 concepts" in short && "<part>" !in short, short)
        val fromIdeas = mapInstructionFromIdeas(
            "DDIA", 600,
            listOf(BookIdea("5. Replication", "", 151, 151, chapter = true), BookIdea("Leaders", "one writer", 151, 156)), 20..40,
        )
        assertTrue("p.151 Chapter: 5. Replication\np.151–156 Leaders: one writer" in fromIdeas, fromIdeas)
        assertTrue("<chapter pages=" in readInstruction("DDIA", 0..24))
    }

    @Test fun aGroupedMapTeachesByPartAndJudgesTheirAnswers() {
        val p = bookPrompt(ddia)
        assertTrue("Part: Replication\n1. Leaders and followers (pp. 151–160)\n2. Replication lag" in p, p)
        assertTrue("Part: Sharding\n3. Partitioning by key" in p, p)
        assertTrue("<topic status=\"shaky\">Name</topic>" in p && "naming the concept exactly as the map does" in p, p)
        assertTrue("one line per part" in stepInstruction(ddia, BookStep.Overview))
        assertTrue("one line per part" !in stepInstruction(map, BookStep.Overview)) // a short map: a numbered list
        val first = stepInstruction(ddia, BookStep.Concept(0))
        assertTrue("It opens the part \"Replication\"" in first && "guess before you explain" in first, first)
        assertTrue("In 3 steps: A · B · C" in first && "Nothing more" in first, first)
        assertTrue("opens the part" !in stepInstruction(ddia, BookStep.Concept(1)))
        assertTrue("It opens the part \"Sharding\"" in stepInstruction(ddia, BookStep.Concept(2)))
        assertEquals(150..159, conceptSpan(ddia.concepts[0]))
    }

    @Test fun eachConceptIsTaughtInStepsWithAQuestionARetryAndANudge() {
        val p = bookPrompt(map)
        assertTrue("guess before you explain" in p && "one step per reply" in p && "**2/3 Name**" in p, p)
        assertTrue("ask one new, easier question on it" in p && "do not give the answer yet" in p, p)
        assertTrue("second miss on the same point" in p, p)
        assertTrue("never reply <next/>" in p && "Nudge them once" in p && "never asked again" in p, p)
        assertTrue("one to three handwritten lines" in p, p)
    }

    @Test fun aVeryLongMapListsOnlyItsPartsOnTheFirstPage() {
        val long = BookMap("DDIA", "", List(60) { Concept("C$it", "", it + 1, it + 1, part = "Part ${it / 10}") })
        val overview = stepInstruction(long, BookStep.Overview)
        assertTrue("no concept names" in overview && "all 60" in overview, overview)
        assertTrue("no concept names" !in stepInstruction(ddia, BookStep.Overview))
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
        assertTrue("\"Achievement society\") where it connects" in c, c)
        assertFalse("where it connects" in stepInstruction(map, BookStep.Concept(0))) // nothing came before
        val p = bookPrompt(map)
        assertTrue("3. Profound boredom (pp. 21–30)" in p && "<next/>" in p && "we exploit ourselves" in p, p)
        assertTrue("concept 2 of 3" in walkPlace(map, 1) && "\"next\" leads to \"Profound boredom\"" in walkPlace(map, 1))
        assertTrue("leads to the whole picture" in walkPlace(map, 2))
        assertTrue("first \"next\" gets a nudge" in walkPlace(map, 1)) // still being taught
        assertTrue("gets <next/> at once" in walkPlace(map, 1, done = true) && "nudge" !in walkPlace(map, 1, done = true))
        assertFalse("concept" in walkPlace(map, null))
        assertEquals(15, centrePage(map.concepts[1])) // p. 16, the middle of pp. 13–20, 0-based
    }
}
