package com.nisaba.domain.search

import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId

/** One question/answer that matched, with where to find it. [pageNumber] is 1-based. */
data class SearchHit(
    val notebookId: NotebookId,
    val notebookName: String,
    val pageId: PageId,
    val pageNumber: Int,
    val question: String,
    val answer: String,
    val atMillis: Long,
    /** Where on the page the question was (outlined when the page opens), when known. */
    val focus: com.nisaba.domain.shared.PageRect? = null,
    val kind: HitKind = HitKind.Exchange,
)

/** What matched: a question or answer, a notebook's name, or the text of a PDF page. */
enum class HitKind { Exchange, Title, Document }
