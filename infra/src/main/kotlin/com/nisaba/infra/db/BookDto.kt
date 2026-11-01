package com.nisaba.infra.db

import kotlinx.serialization.Serializable

/** A book notebook's map as stored (JSON in `book_maps.json`): small, read whole, rewritten as the walk goes on. */
@Serializable
data class BookMapDto(
    val title: String,
    val thesis: String = "",
    val concepts: List<ConceptDto> = emptyList(),
    val overviewPageId: String? = null,
    val synthesisPageId: String? = null,
    /** Still being read: how many parts, and each read part's ideas (by part number). */
    val readingParts: Int? = null,
    val readingIdeas: Map<Int, List<BookIdeaDto>> = emptyMap(),
)

@Serializable
data class ConceptDto(
    val title: String,
    val summary: String = "",
    val firstPage: Int,
    val lastPage: Int,
    val pageId: String? = null,
    val part: String = "",
)

@Serializable
data class BookIdeaDto(val title: String, val summary: String = "", val firstPage: Int, val lastPage: Int, val chapter: Boolean = false)
