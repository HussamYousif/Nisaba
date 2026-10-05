package com.nisaba.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryRulesTest {
    private fun remember(text: String, c: FactCategory = FactCategory.Books) = MemoryOp.Remember(c, text)

    @Test fun duplicatesOfKnownFactsAndWithinTheReplyAreDropped() {
        val plan = planMemory(
            listOf(remember("loved  dune."), remember("Reads Le Guin"), remember("reads le guin")),
            listOf(fact(1, "Loved Dune")),
        )
        assertEquals(listOf(remember("Reads Le Guin")), plan.add)
    }

    @Test fun forgetThenRememberReplacesAFact() {
        val plan = planMemory(listOf(MemoryOp.Forget(FactId(1)), remember("Loved Dune")), listOf(fact(1, "Loved Dune")))
        assertEquals(listOf(FactId(1)), plan.forget)
        assertEquals(listOf(remember("Loved Dune")), plan.add)
    }

    @Test fun unknownIdsBlanksAndExcessAreIgnored() {
        val ops = listOf(MemoryOp.Forget(FactId(99)), remember("  ")) + (1..8).map { remember("fact $it") }
        val plan = planMemory(ops, emptyList())
        assertTrue(plan.forget.isEmpty())
        assertEquals(MAX_ADDS_PER_REPLY, plan.add.size)
        assertEquals("fact 1", plan.add.first().text)
    }

    @Test fun longFactsAreCutAndWhitespaceCollapsed() {
        val plan = planMemory(listOf(remember("a\n  b " + "x".repeat(400))), emptyList())
        assertEquals(MAX_FACT_CHARS, plan.add.single().text.length)
        assertTrue(plan.add.single().text.startsWith("a b x"))
    }

    @Test fun contextGroupsByCategoryInFixedOrderOldestFirst() {
        val facts = listOf(
            fact(3, "Loved Dune", FactCategory.Books),
            fact(1, "Is a nurse", FactCategory.Me),
            fact(2, "Reads Le Guin", FactCategory.Books),
        )
        assertEquals("## me\n#1 Is a nurse\n\n## books\n#2 Reads Le Guin\n#3 Loved Dune", formatMemoryContext(facts, 1_000))
    }

    @Test fun overBudgetKeepsTheNewestFacts() {
        val facts = (1..100L).map { fact(it, "fact number $it") }
        val ctx = formatMemoryContext(facts, maxTokens = 20)!!
        assertTrue(ctx.contains("#100 "))
        assertTrue(!ctx.contains("#1 "))
    }

    @Test fun nothingKnownMeansNoContext() = assertNull(formatMemoryContext(emptyList(), 1_000))

    @Test fun noteSummarisesTheChange() {
        assertEquals("Remembered: Loved Dune", memoryNote(MemoryPlan(listOf(remember("Loved Dune")), emptyList())))
        assertEquals("Forgot one thing", memoryNote(MemoryPlan(emptyList(), listOf(FactId(1)))))
        val long = memoryNote(MemoryPlan(listOf(remember("x".repeat(100))), emptyList()))!!
        assertTrue(long.endsWith("…") && long.length <= 60)
        assertNull(memoryNote(MemoryPlan(emptyList(), emptyList())))
    }
}
