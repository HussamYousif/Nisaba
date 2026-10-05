package com.nisaba.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.domain.memory.Fact
import com.nisaba.domain.memory.ForgetFactUseCase
import com.nisaba.domain.memory.ObserveMemoryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** "What Nisaba remembers": every fact by category, newest first within each, in discrete pages (no scrolling on e-ink). */
class MemoryViewModel(
    observe: ObserveMemoryUseCase,
    private val forget: ForgetFactUseCase,
    scopeOverride: CoroutineScope? = null,
    private val pageSize: Int = PAGE_SIZE,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()
    private var facts: List<Fact> = emptyList()

    init {
        scope.launch {
            observe().collect { all ->
                facts = all.sortedWith(compareBy<Fact> { it.category.ordinal }.thenByDescending { it.id.value })
                show(_state.value.page)
            }
        }
    }

    fun next() = show(_state.value.page + 1)
    fun previous() = show(_state.value.page - 1)

    fun askForget(row: MemoryRow) = _state.update { it.copy(confirming = row) }
    fun cancelForget() = _state.update { it.copy(confirming = null) }

    fun confirmForget() {
        val row = _state.value.confirming ?: return
        _state.update { it.copy(confirming = null) }
        scope.launch { forget(row.id) }
    }

    private fun show(page: Int) {
        val pageCount = ((facts.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val p = page.coerceIn(0, pageCount - 1)
        val slice = facts.drop(p * pageSize).take(pageSize)
        val rows = slice.mapIndexed { i, f -> MemoryRow(f.id, f.category, f.text, header = i == 0 || slice[i - 1].category != f.category) }
        _state.update { it.copy(loading = false, total = facts.size, page = p, pageCount = pageCount, rows = rows) }
    }

    companion object {
        const val PAGE_SIZE = 12
    }
}
