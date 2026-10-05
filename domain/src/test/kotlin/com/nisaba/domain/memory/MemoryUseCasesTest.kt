package com.nisaba.domain.memory

import com.nisaba.domain.notebook.FakeClock
import com.nisaba.domain.shared.ExchangeId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryUseCasesTest {
    private val store = FakeMemoryStore(listOf(fact(1, "Loved Dune", FactCategory.Books)))
    private val apply = ApplyMemoryUseCase(store, FakeClock(5_000))

    @Test fun replyTagsBecomeFactsLinkedToTheExchange() = runTest {
        val plan = apply("""</answer><remember category="projects">Builds Nisaba</remember><heard>x</heard>""", ExchangeId("e1"))
        assertEquals(1, plan!!.add.size)
        val added = store.all().last()
        assertEquals(Fact(FactId(2), FactCategory.Projects, "Builds Nisaba", ExchangeId("e1"), 5_000), added)
    }

    @Test fun nothingToDoReturnsNull() = runTest {
        assertNull(apply("<answer>\nHi\n</answer>", ExchangeId("e1")))
        assertNull(apply("""<remember category="books">loved dune</remember>""", ExchangeId("e1")))
        assertEquals(1, store.all().size)
    }

    @Test fun forgetAndObserve() = runTest {
        ForgetFactUseCase(store)(FactId(1))
        assertEquals(emptyList(), ObserveMemoryUseCase(store)().first())
    }
}
