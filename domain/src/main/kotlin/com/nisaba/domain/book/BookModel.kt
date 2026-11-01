package com.nisaba.domain.book

import com.nisaba.domain.shared.PageId

/**
 * One core concept of a book, as its map lists it. Pages are 1-based, as the book's text is cited ("p. 12");
 * [pageId] is the notebook page it was walked through on (null: not yet). [part] = the part or chapter of the book it
 * belongs to ("" in a short book's map, which needs no grouping).
 */
data class Concept(
    val title: String,
    val summary: String,
    val firstPage: Int,
    val lastPage: Int,
    val pageId: PageId? = null,
    val part: String = "",
) {
    val walked: Boolean get() = pageId != null
    val pages: String get() = if (firstPage == lastPage) "p. $firstPage" else "pp. $firstPage–$lastPage"
}

/**
 * An idea one part of a long book develops: the first pass, before the map is made from all of them. A [chapter] is no
 * idea but where a chapter or part of the book begins (its title), so the map can group concepts the way the book does.
 */
data class BookIdea(val title: String, val summary: String, val firstPage: Int, val lastPage: Int, val chapter: Boolean = false)

/** Reading a long book takes a request per part: how many parts, and the ideas of those read so far (by part). */
data class BookReading(val parts: Int, val ideas: Map<Int, List<BookIdea>> = emptyMap()) {
    val done: Int get() = ideas.size
}

/**
 * A book notebook's map: the book's [thesis] and its core [concepts] in the order to learn them, and the pages the walk
 * has written so far. While [reading] is set, the map is still being made (and [concepts] is empty).
 */
data class BookMap(
    val title: String,
    val thesis: String = "",
    val concepts: List<Concept> = emptyList(),
    val overviewPageId: PageId? = null,
    val synthesisPageId: PageId? = null,
    val reading: BookReading? = null,
) {
    val ready: Boolean get() = reading == null && concepts.isNotEmpty()
    val walked: Int get() = concepts.count { it.walked }

    /** What the walk does next: the map page, the first concept not yet walked, the whole picture; null = done (or not ready). */
    val next: BookStep? get() = when {
        !ready -> null
        overviewPageId == null -> BookStep.Overview
        else -> concepts.indexOfFirst { !it.walked }.takeIf { it >= 0 }?.let { BookStep.Concept(it) }
            ?: BookStep.Synthesis.takeIf { synthesisPageId == null }
    }
}

/** Where a request stands in a book walk: its map, and the concept the open page belongs to (null: the map's page). */
data class BookPlace(val map: BookMap, val concept: Int?)

/** A page of the walk: the map of the book, one concept, or the whole picture at the end. */
sealed interface BookStep {
    data object Overview : BookStep
    data class Concept(val index: Int) : BookStep
    data object Synthesis : BookStep
}
