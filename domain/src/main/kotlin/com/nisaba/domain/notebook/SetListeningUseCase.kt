package com.nisaba.domain.notebook

import com.nisaba.domain.shared.NotebookId

class SetListeningUseCase(private val store: NotebookStore) {
    suspend operator fun invoke(id: NotebookId, listening: Boolean) = store.setListening(id, listening)
}
