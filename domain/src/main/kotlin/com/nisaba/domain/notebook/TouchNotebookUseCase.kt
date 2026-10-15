package com.nisaba.domain.notebook

import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.NotebookId

/** Marks a notebook as just used; at most once per [everyMillis] per notebook (it runs on every stroke). */
class TouchNotebookUseCase(private val store: NotebookStore, private val clock: Clock, private val everyMillis: Long = 60_000) {
    private val last = HashMap<NotebookId, Long>()

    suspend operator fun invoke(id: NotebookId) {
        val now = clock.nowMillis()
        val due = synchronized(last) { (now - (last[id] ?: Long.MIN_VALUE / 2) >= everyMillis).also { if (it) last[id] = now } }
        if (due) store.touch(id, now)
    }
}
