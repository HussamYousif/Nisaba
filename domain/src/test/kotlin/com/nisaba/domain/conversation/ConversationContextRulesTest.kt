package com.nisaba.domain.conversation

import com.nisaba.domain.memory.fact
import com.nisaba.domain.persona.PersonaRegistry
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
        val book = systemPrompt(PersonaRegistry.default)
        assertEquals(book, buildRequest(input(emptyList())).system)
        val blank = PageSnapshot(byteArrayOf(1), null)
        val lesson = buildRequest(
            ContextInput(listOf("Cells"), null, emptyList(), blank, Settings(), teachGoal = "exam Friday", purpose = Purpose.Opening),
        )
        assertTrue(lesson.system.startsWith(book) && lesson.system.contains("# Teaching"))
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
        assertEquals(systemPrompt(PersonaRegistry.default), r.system)
    }

    @Test fun theNotebooksVoiceLeadsTheSystemPromptAndSetsLengthAndTemperature() {
        val zagreus = PersonaRegistry.of("zagreus")
        val r = buildRequest(ContextInput(emptyList(), null, emptyList(), snap, Settings(), persona = zagreus))
        assertTrue(r.system.startsWith(zagreus.voice))
        assertTrue(r.system.contains("at most ${zagreus.words} words"))
        assertTrue(!r.system.contains("{words}") && !r.system.contains("Grimoire Weiss"))
        assertEquals(zagreus.temperature, r.options.temperature)
        assertTrue(r.instruction.endsWith(voiceReminder(zagreus, lesson = false)))
        // a tidy copy is the person's notes, not a performance: no voice line, a cool temperature
        val tidyInput =
            ContextInput(emptyList(), null, emptyList(), snap, Settings(), purpose = Purpose.Tidy(PageId("p")), persona = zagreus)
        val tidy = buildRequest(tidyInput)
        assertEquals(INSTRUCTION_TIDY, tidy.instruction)
        assertEquals(TIDY_TEMPERATURE, tidy.options.temperature)
        // a lesson keeps the voice and adds the teaching prompt after it
        val lesson = buildRequest(ContextInput(listOf("doc"), null, emptyList(), snap, Settings(), teachGoal = "", persona = zagreus))
        assertTrue(lesson.system.startsWith(systemPrompt(zagreus) + "\n\n# Teaching"))
        assertTrue(lesson.instruction.endsWith(voiceReminder(zagreus, lesson = true)))
    }

    @Test fun questionsOnlyKeepsNotesSilentAndBriefShortensTheAnswers() {
        val style = com.nisaba.domain.notebook.AnswerStyle(
            com.nisaba.domain.notebook.AnswerMode.QuestionsOnly, com.nisaba.domain.notebook.AnswerLength.Brief,
        )
        val r = buildRequest(ContextInput(emptyList(), null, emptyList(), snap, Settings(), style = style))
        assertTrue(r.system.contains("Answer only writing that is addressed to you") && !r.system.contains("Respond to whatever"))
        assertTrue(r.system.contains("at most 30 words") && r.instruction.contains("at most 30 words")) // the book's 50 × 0.6
        val everything = buildRequest(input(emptyList()))
        assertTrue(everything.system.contains("Respond to whatever") && everything.system.contains("at most 50 words"))
    }

    @Test fun replyCeilingsFollowThePurpose() {
        assertEquals(1200, buildRequest(input(emptyList())).options.maxTokens)
        val tidy = ContextInput(emptyList(), null, emptyList(), snap, Settings(), purpose = Purpose.Tidy(PageId("p")))
        assertEquals(2400, buildRequest(tidy).options.maxTokens)
        assertEquals(2400, buildRequest(ContextInput(emptyList(), null, emptyList(), snap, Settings(thinking = true))).options.maxTokens)
    }

    @Test fun everyVoiceSharesTheSameReplyContract() {
        val contracts = PersonaRegistry.all.map { systemPrompt(it).removePrefix(it.voice).replace(Regex("at most \\d+ words"), "") }
        assertEquals(1, contracts.toSet().size)
        assertTrue(contracts.first().contains("<heard>") && contracts.first().contains("<remember"))
    }

    @Test fun withoutAFocusCropOnlyThePageIsSent() {
        val r = buildRequest(input(emptyList(), s = PageSnapshot(byteArrayOf(1), null)))
        assertEquals(1, r.images.size)
        assertTrue(r.instruction.startsWith(INSTRUCTION_PAGE_ONLY))
    }

    @Test fun scannedDocumentsSendNoDocumentText() {
        assertNull(formatDocumentContext(listOf("", "  "), 0))
        assertNotNull(formatDocumentContext(listOf("x"), 0))
    }

    @Test fun hugeDocumentsAreWindowedAroundTheCurrentPage() {
        val pages = List(400) { "page-$it " + "x".repeat(2000) }
        val ctx = formatDocumentContext(pages, currentPage = 200, maxTokens = 60_000)!!
        assertTrue(ctx.contains("[p.201]") && ctx.contains("[p.181]") && ctx.contains("[p.239]"))
        assertTrue(!ctx.contains("[p.1]") && !ctx.contains("[p.400]"))
        assertTrue(estimateTokens(ctx) <= 60_000)
    }

    @Test fun turningPagesInsideABlockKeepsTheWindowSoTheCacheHolds() {
        val pages = List(400) { "page-$it " + "x".repeat(2000) }
        val a = formatDocumentContext(pages, currentPage = 200, maxTokens = 60_000)
        assertEquals(a, formatDocumentContext(pages, currentPage = 219, maxTokens = 60_000))
        assertTrue(a != formatDocumentContext(pages, currentPage = 220, maxTokens = 60_000)) // the next block moves it
        val dense = List(400) { "page-$it " + "x".repeat(9000) } // the window must shrink, still around the block
        assertEquals(formatDocumentContext(dense, 203, 60_000), formatDocumentContext(dense, 214, 60_000))
        assertTrue(formatDocumentContext(dense, 219, 60_000)!!.contains("[p.220]"))
    }

    @Test fun aLessonReadsTheDocumentWhereItLastCited() {
        val pages = List(400) { "page-$it " + "x".repeat(2000) }
        val taught = listOf(ex(1, "q", "Mitosis splits the nucleus (p. 12)."), ex(2, "next?", "Then cytokinesis (p. 141)."))
        assertEquals(140, lastCitedPage(taught))
        val r = buildRequest(ContextInput(pages, null, taught, snap, Settings(), teachGoal = ""))
        assertTrue(r.documentContext!!.contains("[p.141]") && !r.documentContext!!.contains("[p.1] "))
    }

    @Test fun overBudgetHistoryIsTrimmedWellBelowItSoTheNextRequestsKeepTheirPrefix() {
        val big = "y".repeat(35_000)
        val history = (1..8).flatMap { listOf(Turn.User("u$it $big"), Turn.Assistant("a$it")) }
        val kept = trimHistory(history, budget = 70_000)
        assertTrue(kept.sumOf { estimateTokens((it as? Turn.User)?.text ?: (it as Turn.Assistant).text) } <= 49_000)
        assertEquals(history, trimHistory(history, budget = 200_000), "under budget: untouched")
    }

    @Test fun cjkCountsAboutATokenPerCharacter() {
        assertEquals(11, estimateTokens("日本語のテキストです。")) // 11 characters, punctuation included
        assertEquals(4, estimateTokens("hello world")) // 11 characters / 3.5, rounded up
    }

    @Test fun askingAgainThinksAndQuotesThePreviousAnswerAsPlainWords() {
        val again = Purpose.Again(
            "why?", "<answer>\n**Because** $2+2$.\n</answer><remember category=\"me\">x</remember><heard>why?</heard>",
        )
        val r = buildRequest(ContextInput(emptyList(), null, emptyList(), snap, Settings(), purpose = again))
        assertTrue(r.options.thinking)
        assertTrue(r.instruction.contains("\"Because 2+2.\"") && !r.instruction.contains("<remember") && !r.instruction.contains("**"))
    }

    @Test fun erasedAndCancelledExchangesLeaveTheHistory() {
        val (turns, _) = historyTurns(
            listOf(ex(1, "a", "A"), ex(2, "b", "B", ExchangeStatus.Erased), ex(3, "c", "", ExchangeStatus.Cancelled)),
        )
        assertEquals(listOf(Turn.User("a"), Turn.Assistant("A")), turns)
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
