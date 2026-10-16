package com.nisaba.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.domain.search.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Searches as you type, once typing pauses (e-ink: one repaint per search, not per key). */
class SearchViewModel(
    private val search: suspend (String, Int) -> List<SearchHit>,
    private val scopeOverride: CoroutineScope? = null,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onQuery(text: String) {
        _state.update { it.copy(query = text, searched = false, screen = 0) }
        job?.cancel()
        if (text.isBlank()) { _state.update { it.copy(hits = emptyList(), more = false) }; return }
        job = scope.launch {
            delay(debounceMillis)
            fetch(text, SearchUiState.PER_SCREEN * 2)
        }
    }

    /** The next or previous screenful; the next one fetches more when it runs out. */
    fun screen(delta: Int) {
        val s = _state.value
        val next = (s.screen + delta).coerceAtLeast(0)
        _state.update { it.copy(screen = next) }
        if ((next + 1) * SearchUiState.PER_SCREEN > s.hits.size && s.more) {
            job?.cancel()
            job = scope.launch { fetch(s.query, (next + 2) * SearchUiState.PER_SCREEN) }
        }
    }

    private suspend fun fetch(text: String, limit: Int) {
        // a PDF's text is scanned page by page: off the thread that draws (the scope's dispatcher in tests)
        val hits = if (scopeOverride == null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { search(text, limit) }
        else search(text, limit)
        _state.update { if (it.query == text) it.copy(hits = hits, searched = true, more = hits.size >= limit) else it }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 450L
    }
}
