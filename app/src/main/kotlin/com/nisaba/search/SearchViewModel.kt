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
    private val search: suspend (String) -> List<SearchHit>,
    scopeOverride: CoroutineScope? = null,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onQuery(text: String) {
        _state.update { it.copy(query = text, searched = false) }
        job?.cancel()
        if (text.isBlank()) { _state.update { it.copy(hits = emptyList()) }; return }
        job = scope.launch {
            delay(debounceMillis)
            val hits = search(text)
            _state.update { if (it.query == text) it.copy(hits = hits, searched = true) else it }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 450L
    }
}
