package com.nisaba.domain.conversation

import com.nisaba.domain.memory.fact
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationContextRulesTest {
    private fun ex(n: Int, heard: String, markup: String, status: ExchangeStatus = ExchangeStatus.Complete) = Exchange(
        ExchangeId("e$n"), NotebookId("n"), PageId("p"), heard, markup, status, n * 1000L, "m", 1, 0, 0, 0,
    )

    private val snap = PageSnapshot(byteArrayOf(1), byteArrayOf(2))
    private fun input(exchanges: List<Exchange>, pages: List<String> = emptyList(), page: Int? = null, s: PageSnapshot = snap) =
        ContextInput(pages, page, exchanges, s, Settings())

    @Test fun memoryGoesInTheRequestAndShrinksTheHistoryRoom() {
        assertNull(buildRequest(input(emptyList())).memoryContext)
        val facts = (1..2_000L).map { fact(it, "a fairly ordinary remembered fact number $it") }
        val history = (1..400).map { ex(it, "question $it " + "q".repeat(400), "answer $it " + "a".repeat(400)) }
        val withMemory = buildRequest(ContextInput(emptyList(), null, history, snap, Settings(), facts))
        val without = buildRequest(input(history))
        assertTrue(withMemory.memoryContext!!.contains("#2000 "))
        assertTrue(withMemory.history.size < without.history.size)
    }

    @Test fun aLessonNotebookAddsTheHiddenTeachingPromptAndOpensOnABlankPage() {
        assertEquals(SYSTEM_PROMPT, buildRequest(input(emptyList())).system)
        val blank = PageSnapshot(byteArrayOf(1), null)
        val lesson = buildRequest(
            ContextInput(listOf("Cells"), null, emptyList(), blank, Settings(), teachGoal = "exam Friday", purpose = Purpose.Opening),
        )
        assertTrue(lesson.system.startsWith(SYSTEM_PROMPT) && lesson.system.contains("# Teaching"))
        assertTrue(lesson.system.contains("\"exam Friday\""))
        assertTrue(lesson.instruction.startsWith(INSTRUCTION_BEGIN_LESSON))
        assertEquals(1, lesson.images.size)
        val noGoal = buildRequest(ContextInput(listOf("Cells"), null, emptyList(), snap, Settings(), teachGoal = ""))
        assertTrue(noGoal.system.contains("# Teaching") && !noGoal.system.contains("Their goal"))
    }

    @Test fun historyAlternatesUserAssistantOldestFirst() {
        val (turns, _) = historyTurns(listOf(ex(2, "b", "B"), ex(1, "a", "A")))
        assertEquals(listOf(Turn.User("a"), Turn.Assistant("A"), Turn.User("b"), Turn.Assistant("B")), turns)
    }

    @Test fun silentNotesFoldIntoTheNextQuestionAndFailuresAreSkipped() {
        val (turns, trailing) = historyTurns(
            listOf(ex(1, "buy milk", "", ExchangeStatus.Silent), ex(2, "boom", "", ExchangeStatus.Failed), ex(3, "q?", "A")),
        )
        assertEquals(2, turns.size)
        assertEquals("(notes on the page: buy milk)\nq?", (turns[0] as Turn.User).text)
        assertTrue(trailing.isEmpty())
        assertEquals(listOf("late note"), historyTurns(listOf(ex(1, "late note", "", ExchangeStatus.Silent))).second)
    }

    @Test fun interruptedAnswersStayInHistoryButEmptyOnesDoNot() {
        val (turns, _) = historyTurns(listOf(ex(1, "q", "partial", ExchangeStatus.Interrupted), ex(2, "q2", "  ")))
        assertEquals(2, turns.size)
    }

    @Test fun requestHasPageThenFocusImageAndDocumentPageHint() {
        val r = buildRequest(input(emptyList(), pages = listOf("alpha", "beta"), page = 1))
        assertEquals(listOf(ImageDetail.Auto, ImageDetail.High), r.images.map { it.detail })
        assertTrue(r.instruction.contains("p. 2"))
        assertEquals("[p.1] alpha\n\n[p.2] beta", r.documentContext)
        assertEquals(SYSTEM_PROMPT, r.system)
    }

    @Test fun withoutAFocusCropOnlyThePageIsSent() {
        val r = buildRequest(input(emptyList(), s = PageSnapshot(byteArrayOf(1), null)))
        assertEquals(1, r.images.size)
        assertEquals(INSTRUCTION_PAGE_ONLY, r.instruction)
    }

    @Test fun scannedDocumentsSendNoDocumentText() {
        assertNull(formatDocumentContext(listOf("", "  "), 0))
        assertNotNull(formatDocumentContext(listOf("x"), 0))
    }

    @Test fun hugeDocumentsAreWindowedAroundTheCurrentPage() {
        val pages = List(400) { "page-$it " + "x".repeat(2000) }
        val ctx = formatDocumentContext(pages, currentPage = 200, maxTokens = 60_000)!!
        assertTrue(ctx.contains("[p.201]") && ctx.contains("[p.171]") && ctx.contains("[p.231]"))
        assertTrue(!ctx.contains("[p.1]") && !ctx.contains("[p.400]"))
        assertTrue(estimateTokens(ctx) <= 60_000)
    }

    @Test fun oldestPairsAreDroppedWhenOverBudget() {
        val big = "y".repeat(35_000)                                            // ≈ 10k tokens each
        val history = (1..8).flatMap { listOf(Turn.User("u$it $big"), Turn.Assistant("a$it")) }
        val kept = trimHistory(history, budget = 35_000)
        assertTrue(kept.size < history.size && kept.size % 2 == 0)
        assertEquals(history.takeLast(kept.size), kept, "the newest turns survive")
    }

    @Test fun addingAnExchangeNeverChangesEarlierMessages() {
        val a = ex(1, "one", "A"); val b = ex(2, "two", "B")
        val r1 = buildRequest(input(listOf(a)))
        val r2 = buildRequest(input(listOf(a, b)))
        assertEquals(r1.history, r2.history.take(r1.history.size))
        assertEquals(r1.system, r2.system); assertEquals(r1.documentContext, r2.documentContext)
    }
}
