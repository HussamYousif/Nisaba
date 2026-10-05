package com.nisaba.domain.search

import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStatus
import com.nisaba.domain.conversation.FakeExchangeStore
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.FakeClock
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.notebook.FakeNotebookStore
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchUseCaseTest {
    @Test fun findsQuestionsAndAnswersAcrossNotebooksWithTheirPage() = runTest {
        val notebooks = FakeNotebookStore()
        val nb = CreateNotebookUseCase(notebooks, FakeClock(), FakeIdGen("n"))("Geography")
        val page = notebooks.get(nb.id)!!.pages.single().id
        val store = FakeExchangeStore()
        fun ex(id: String, heard: String, markup: String, at: Long, p: PageId = page) =
            Exchange(ExchangeId(id), nb.id, p, heard, markup, ExchangeStatus.Complete, at, "m", 7, 0, 0, 0)
        store.append(ex("a", "capital of Peru?", "<answer>\n**Lima.** Founded 1535.\n</answer><heard>capital of Peru?</heard>", 1))
        store.append(ex("b", "and Chile?", "<answer>\nSantiago.\n</answer>", 2))
        store.append(ex("c", "peru on a deleted page", "x", 3, PageId("gone")))
        val hits = SearchUseCase(store, notebooks)("lima")
        val hit = hits.single()
        assertEquals("Geography", hit.notebookName); assertEquals(1, hit.pageNumber); assertEquals(page, hit.pageId)
        assertEquals("Lima. Founded 1535.", hit.answer)
        assertEquals(listOf("capital of Peru?"), SearchUseCase(store, notebooks)("PERU").map { it.question }) // the deleted page is dropped
        assertTrue(SearchUseCase(store, notebooks)("  ").isEmpty())
    }

    @Test fun plainTextDropsMarkupAndDiagrams() {
        val markup = "<answer>\n# Water\n- **evaporates**\n```diagram\n<svg viewBox=\"0 0 9 9\"><circle r=\"1\"/></svg>\n```\n" +
            "\$x^2\$\n</answer>" +
            "<remember category=\"me\">x</remember><heard>q</heard>"
        assertEquals("Water - evaporates (a drawing) x^2", plainText(markup))
    }

    @Test fun snippetsCentreOnTheMatch() {
        val text = "a".repeat(300) + " fjord " + "b".repeat(300)
        val s = snippet(text, "FJORD", 60)
        assertTrue(s.contains("fjord") && s.startsWith("…") && s.endsWith("…"))
        assertEquals("short", snippet("short", "x"))
    }
}
