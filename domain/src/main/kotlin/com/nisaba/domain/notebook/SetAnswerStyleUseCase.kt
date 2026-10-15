package com.nisaba.domain.notebook

import com.nisaba.domain.shared.NotebookId

class SetAnswerStyleUseCase(private val store: NotebookStore) {
    /** From the next pause on, notebook [id] answers as [style] says. */
    suspend operator fun invoke(id: NotebookId, style: AnswerStyle) = store.setStyle(id, style)
}
