package com.nisaba.domain.notebook

import com.nisaba.domain.shared.NotebookId

/** Makes notebook [id] private (nothing remembered from it or recalled into it, out of search) or not. */
class SetPrivateUseCase(private val store: NotebookStore) {
    suspend operator fun invoke(id: NotebookId, isPrivate: Boolean) = store.setPrivate(id, isPrivate)
}
