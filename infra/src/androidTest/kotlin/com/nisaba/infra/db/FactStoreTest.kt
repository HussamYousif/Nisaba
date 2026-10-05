package com.nisaba.infra.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.domain.memory.FactCategory
import com.nisaba.domain.memory.NewFact
import com.nisaba.domain.shared.ExchangeId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FactStoreTest {
    private val db = NisabaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = RoomFactStore(db.facts())

    @After fun close() = db.close()

    @Test fun addsWithIncreasingIdsAndForgetsInOneGo() = runBlocking {
        store.apply(
            listOf(NewFact(FactCategory.Books, "Loved Dune", ExchangeId("e1"), 5), NewFact(FactCategory.Me, "Is a nurse", null, 6)),
            emptyList(),
        )
        val (dune, nurse) = store.all()
        assertEquals(FactCategory.Books, dune.category)
        assertEquals(ExchangeId("e1"), dune.sourceExchangeId)
        assertEquals(dune.id.value + 1, nurse.id.value)
        store.apply(listOf(NewFact(FactCategory.Books, "Loved Dune, not the sequel", null, 7)), listOf(dune.id))
        assertEquals(listOf("Is a nurse", "Loved Dune, not the sequel"), store.observeAll().first().map { it.text })
    }
}
