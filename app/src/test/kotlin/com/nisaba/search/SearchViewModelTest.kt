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
        val vm = SearchViewModel({ q, _ -> asked += q; listOf(hit) }, backgroundScope, debounceMillis = 400)
        vm.onQuery("l"); advanceTimeBy(100); vm.onQuery("li"); advanceTimeBy(100); vm.onQuery("lima")
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("lima"), asked)
        assertEquals(listOf(hit), vm.state.value.hits); assertTrue(vm.state.value.searched)
        vm.onQuery(""); runCurrent()
        assertTrue(vm.state.value.hits.isEmpty())
    }

    @Test fun laterScreensFetchMoreWhenTheyRunOut() = runTest {
        val limits = mutableListOf<Int>()
        val all = (1..20).map { SearchHit(NotebookId("n"), "N", PageId("p$it"), it, "q$it", "a", 0) }
        val vm = SearchViewModel({ _, limit -> limits += limit; all.take(limit) }, backgroundScope, debounceMillis = 0)
        vm.onQuery("q"); runCurrent()
        assertEquals(SearchUiState.PER_SCREEN, vm.state.value.visible.size)
        assertTrue(vm.state.value.more)
        vm.screen(1); runCurrent() // the second screen still has hits: no new fetch yet
        assertEquals("q7", vm.state.value.visible.first().question)
        vm.screen(1); runCurrent() // the third runs out: more are fetched
        assertEquals(listOf(12, 24), limits)
        assertEquals("q13", vm.state.value.visible.first().question)
    }
}
