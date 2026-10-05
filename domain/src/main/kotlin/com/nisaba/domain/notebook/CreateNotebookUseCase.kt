package com.nisaba.domain.notebook

import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageSize

class CreateNotebookUseCase(private val store: NotebookStore, private val clock: Clock, private val idGen: IdGen) {
    /**
     * Creates a notebook with one blank page (or one endless canvas); a document notebook gets one page per source page.
     * A lesson notebook ([teachGoal] non-null) keeps its document as hidden context and starts on one blank page.
     */
    suspend operator fun invoke(
        rawName: String?,
        documentId: DocumentId? = null,
        documentPages: List<PageSize> = emptyList(),
        listening: Boolean = true,
        endless: Boolean = false,
        teachGoal: String? = null,
    ): Notebook {
        val name = validateNotebookName(rawName, store.allNames())
        val now = clock.nowMillis()
        val id = NotebookId(idGen.next())
        val notebook = Notebook(id, name, now, now, documentId, listening, coverSeed(name, id), teachGoal?.trim()?.take(MAX_GOAL))
        val pages = if (documentId != null && documentPages.isNotEmpty() && teachGoal == null) {
            documentPages.mapIndexed { i, size ->
                Page(PageId(idGen.next()), id, (i + 1) * PAGE_POSITION_STEP, size, PageBackground.DocumentPage(documentId, i))
            }
        } else {
            val size = if (endless) PageSize.ENDLESS else PageSize.PORTRAIT
            listOf(Page(PageId(idGen.next()), id, PAGE_POSITION_STEP, size, PageBackground.Blank))
        }
        store.create(notebook, pages)
        return notebook
    }
}

private const val MAX_GOAL = 300
