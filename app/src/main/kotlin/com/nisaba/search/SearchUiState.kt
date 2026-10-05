package com.nisaba.search

import com.nisaba.domain.search.SearchHit

data class SearchUiState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    /** True once a search for the current [query] has come back (so "nothing found" can be told from "still looking"). */
    val searched: Boolean = false,
)
