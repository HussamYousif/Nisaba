package com.nisaba.infra.db

import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.notebook.NotebookSummary
import com.nisaba.domain.notebook.Page
import com.nisaba.domain.notebook.PageBackground
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageSize

private const val BG_BLANK = 0
private const val BG_DOCUMENT = 1

fun Notebook.toDto() =
    NotebookDto(id.value, name, createdAtMillis, updatedAtMillis, documentId?.value, listening, coverSeed, teachGoal, personaId)

fun NotebookDto.toModel() =
    Notebook(NotebookId(id), name, createdAt, updatedAt, documentId?.let(::DocumentId), listening, coverSeed, teachGoal, persona)

fun Page.toDto() = PageDto(
    id = id.value, notebookId = notebookId.value, position = position, width = size.w, height = size.h,
    bgKind = if (background is PageBackground.DocumentPage) BG_DOCUMENT else BG_BLANK,
    bgDocumentId = (background as? PageBackground.DocumentPage)?.documentId?.value,
    bgIndex = (background as? PageBackground.DocumentPage)?.index,
)

fun PageDto.toModel() = Page(
    id = PageId(id), notebookId = NotebookId(notebookId), position = position, size = PageSize(width, height),
    background = if (bgKind == BG_DOCUMENT && bgDocumentId != null && bgIndex != null) {
        PageBackground.DocumentPage(DocumentId(bgDocumentId), bgIndex)
    } else {
        PageBackground.Blank
    },
)

fun SummaryRowDto.toModel() =
    NotebookSummary(NotebookId(id), name, updatedAt, pageCount, coverSeed, hasDocument, endless, lesson, persona)
