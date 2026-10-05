package com.nisaba.memory

import com.nisaba.domain.memory.FactCategory
import com.nisaba.domain.memory.FactId

/** One fact on the current page; [header] = first of its category on this page, so the shelf name is drawn above it. */
data class MemoryRow(val id: FactId, val category: FactCategory, val text: String, val header: Boolean)

data class MemoryUiState(
    val loading: Boolean = true,
    val total: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 1,
    val rows: List<MemoryRow> = emptyList(),
    /** The fact waiting for "Forget?" confirmation. */
    val confirming: MemoryRow? = null,
)
