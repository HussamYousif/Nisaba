package com.nisaba.domain.notebook

import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The trash: what was deleted in the last [TRASH_DAYS] days, newest first. */
class ListTrashUseCase(private val store: NotebookStore) {
    operator fun invoke(): Flow<List<NotebookSummary>> =
        store.observeAll().map { list -> list.filter { it.deletedAtMillis != null }.sortedByDescending { it.deletedAtMillis } }
}

/** Delete = into the trash (restorable for [TRASH_DAYS] days). */
class TrashNotebookUseCase(private val store: NotebookStore, private val clock: Clock) {
    suspend operator fun invoke(id: NotebookId) = store.setTrashed(id, clock.nowMillis())
}

class RestoreNotebookUseCase(private val store: NotebookStore) {
    suspend operator fun invoke(id: NotebookId) = store.setTrashed(id, null)
}

/** Deletes for good what has been in the trash longer than [TRASH_DAYS] days (or everything in it, [all]). */
class EmptyTrashUseCase(private val store: NotebookStore, private val delete: DeleteNotebookUseCase, private val clock: Clock) {
    suspend operator fun invoke(all: Boolean = false): Int {
        val cutoff = clock.nowMillis() - TRASH_DAYS * DAY_MILLIS
        val gone = store.observeAll().first().filter { n -> n.deletedAtMillis?.let { all || it < cutoff } == true }
        gone.forEach { delete(it.id) }
        return gone.size
    }

    private companion object { const val DAY_MILLIS = 24L * 60 * 60 * 1000 }
}

class PinNotebookUseCase(private val store: NotebookStore) {
    suspend operator fun invoke(id: NotebookId, pinned: Boolean) = store.setPinned(id, pinned)
}

class SetPaperUseCase(private val store: NotebookStore) {
    suspend operator fun invoke(id: NotebookId, paper: Paper) = store.setPaper(id, paper)
}

/** "Tutor me" on a PDF notebook (goal "" = all of it), or stop tutoring (null). */
class SetTeachGoalUseCase(private val store: NotebookStore) {
    suspend operator fun invoke(id: NotebookId, goal: String?) = store.setTeachGoal(id, goal)
}
