package com.nisaba.domain.notebook

import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.NotebookId

class RenameNotebookUseCase(private val store: NotebookStore, private val clock: Clock) {
    suspend operator fun invoke(id: NotebookId, rawName: String): String {
        val others = store.get(id)?.let { current -> store.allNames() - current.notebook.name } ?: store.allNames()
        val name = validateNotebookName(rawName, others)
        store.rename(id, name, clock.nowMillis())
        return name
    }
}
