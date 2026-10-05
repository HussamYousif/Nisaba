package com.nisaba.infra.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStatus
import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExchangeStoreTest {
    private lateinit var db: NisabaDatabase
    private val nb = NotebookId("n1")

    private fun ex(id: String, at: Long, status: ExchangeStatus = ExchangeStatus.Complete) =
        Exchange(ExchangeId(id), nb, PageId("p"), "heard $id", "answer $id", status, at, "deepseek-flash", 1, 800, 1500, 40)

    @Before fun setUp() {
        db = NisabaDatabase.inMemory(ApplicationProvider.getApplicationContext())
        runBlocking { RoomNotebookStore(db.notebooks()).create(Notebook(nb, "N", 1, 1, null, true, 0), emptyList()) }
    }

    @After fun tearDown() { db.close() }

    @Test fun appendsAndListsOldestFirstWithAllFields() = runBlocking {
        val store = RoomExchangeStore(db.exchanges())
        store.append(ex("b", 20, ExchangeStatus.Interrupted)); store.append(ex("a", 10))
        val all = store.listByNotebook(nb)
        assertEquals(listOf("a", "b"), all.map { it.id.value })
        assertEquals(ex("b", 20, ExchangeStatus.Interrupted), all[1])
        assertEquals(2, store.observeByNotebook(nb).first().size)
    }

    @Test fun keepsCachedTokensAndTheAnsweredRegion() = runBlocking {
        val store = RoomExchangeStore(db.exchanges())
        val e = ex("c", 30).copy(cachedTokens = 1024, focus = com.nisaba.domain.shared.PageRect(1f, 2f, 3f, 4f))
        store.append(e)
        assertEquals(e, store.get(ExchangeId("c")))
        assertEquals(listOf(com.nisaba.domain.usage.ExchangeUsage("deepseek-flash", 30, 1500, 1024, 40)), store.usage())
    }

    @Test fun searchFindsQuestionsAndAnswersNewestFirstAndSkipsFailures() = runBlocking {
        val store = RoomExchangeStore(db.exchanges())
        store.append(ex("a", 10).copy(heard = "capital of Peru?", answerMarkup = "Lima."))
        store.append(ex("b", 20).copy(heard = "and of Chile?", answerMarkup = "Santiago, south of Peru."))
        store.append(ex("c", 30, ExchangeStatus.Failed).copy(heard = "peru again"))
        store.append(ex("d", 40).copy(heard = "100% sure?", answerMarkup = "yes"))
        assertEquals(listOf("b", "a"), store.search("peru", 10).map { it.id.value })
        assertEquals(listOf("d"), store.search("100%", 10).map { it.id.value }) // % is literal
        assertEquals(emptyList<Exchange>(), store.search("  ", 10))
    }
}
