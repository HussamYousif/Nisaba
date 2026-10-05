package com.nisaba.memory

import com.nisaba.domain.memory.FactCategory
import com.nisaba.domain.memory.FakeMemoryStore
import com.nisaba.domain.memory.ForgetFactUseCase
import com.nisaba.domain.memory.ObserveMemoryUseCase
import com.nisaba.domain.memory.fact
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    private val store = FakeMemoryStore(
        listOf(
            fact(1, "Loved Dune", FactCategory.Books),
            fact(2, "Is a nurse", FactCategory.Me),
            fact(3, "Reads Le Guin", FactCategory.Books),
            fact(4, "Builds Nisaba", FactCategory.Projects),
        ),
    )

    private fun TestScope.vm() = MemoryViewModel(ObserveMemoryUseCase(store), ForgetFactUseCase(store), backgroundScope, pageSize = 3)
        .also { runCurrent() }

    @Test fun groupsByCategoryNewestFirstInPages() = runTest {
        val vm = vm()
        val s = vm.state.value
        assertEquals(4, s.total); assertEquals(2, s.pageCount)
        assertEquals(listOf("Is a nurse", "Builds Nisaba", "Reads Le Guin"), s.rows.map { it.text })
        assertEquals(listOf(true, true, true), s.rows.map { it.header })
        vm.next()
        assertEquals(listOf("Loved Dune"), vm.state.value.rows.map { it.text })
        assertEquals(listOf(true), vm.state.value.rows.map { it.header }) // category repeated at the top of a new page
        vm.next(); assertEquals(1, vm.state.value.page)                   // clamped
    }

    @Test fun forgettingAsksFirstThenRemovesTheFact() = runTest {
        val vm = vm()
        val row = vm.state.value.rows.first()
        vm.askForget(row); vm.cancelForget(); runCurrent()
        assertEquals(4, store.facts.value.size)
        vm.askForget(row); vm.confirmForget(); runCurrent()
        assertNull(vm.state.value.confirming)
        assertEquals(3, vm.state.value.total)
        assertEquals(1, vm.state.value.pageCount)
    }
}
