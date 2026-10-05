package com.nisaba.search

import com.nisaba.domain.search.SearchHit
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test fun searchesOnceTypingPausesWithTheLastText() = runTest {
        val asked = mutableListOf<String>()
        val hit = SearchHit(NotebookId("n"), "N", PageId("p"), 1, "q", "a", 0)
        val vm = SearchViewModel({ q -> asked += q; listOf(hit) }, backgroundScope, debounceMillis = 400)
        vm.onQuery("l"); advanceTimeBy(100); vm.onQuery("li"); advanceTimeBy(100); vm.onQuery("lima")
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("lima"), asked)
        assertEquals(listOf(hit), vm.state.value.hits); assertTrue(vm.state.value.searched)
        vm.onQuery(""); runCurrent()
        assertTrue(vm.state.value.hits.isEmpty())
    }
}
