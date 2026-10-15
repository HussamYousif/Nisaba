package com.nisaba.search

import com.nisaba.domain.search.SearchHit

data class SearchUiState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    /** True once a search for the current [query] has come back (so "nothing found" can be told from "still looking"). */
    val searched: Boolean = false,
    /** Which screenful of hits is shown (no scrolling on e-ink); [more] = there may be more after it. */
    val screen: Int = 0,
    val more: Boolean = false,
) {
    val visible: List<SearchHit> get() = hits.drop(screen * PER_SCREEN).take(PER_SCREEN)

    companion object { const val PER_SCREEN = 6 }
}
