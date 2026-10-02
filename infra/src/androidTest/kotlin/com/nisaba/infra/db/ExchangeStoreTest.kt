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
}
