package com.nisaba.infra.db

import com.nisaba.domain.book.BookIdea
import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.BookReading
import com.nisaba.domain.book.Concept
import com.nisaba.domain.shared.PageId

fun BookMap.toDto() = BookMapDto(
    title, thesis, concepts.map { ConceptDto(it.title, it.summary, it.firstPage, it.lastPage, it.pageId?.value) },
    overviewPageId?.value, synthesisPageId?.value, reading?.parts,
    reading?.ideas.orEmpty().mapValues { (_, ideas) -> ideas.map { BookIdeaDto(it.title, it.summary, it.firstPage, it.lastPage) } },
)

fun BookMapDto.toModel() = BookMap(
    title, thesis, concepts.map { Concept(it.title, it.summary, it.firstPage, it.lastPage, it.pageId?.let(::PageId)) },
    overviewPageId?.let(::PageId), synthesisPageId?.let(::PageId),
    readingParts?.let { parts ->
        BookReading(parts, readingIdeas.mapValues { (_, ideas) -> ideas.map { BookIdea(it.title, it.summary, it.firstPage, it.lastPage) } })
    },
)
