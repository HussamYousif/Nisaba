package com.nisaba.app

import com.nisaba.domain.book.ReadBookUseCase
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reads book notebooks into their maps, one job per notebook, in the app's scope: a long book takes minutes, and leaving
 * the notebook (or the screen going off) must not stop it. The use case keeps each part as it is read.
 */
class BookReaderRegistry(
    private val scope: CoroutineScope,
    private val reader: ReadBookUseCase,
    /** The book's title (its notebook's name) and its text, a page each. */
    private val title: suspend (NotebookId) -> String?,
    private val pages: suspend (NotebookId) -> List<String>,
    private val model: suspend () -> String,
    /** Names the notebook after the book once its map knows the real title (a file name like "the-prince" is no title). */
    private val rename: suspend (NotebookId, String) -> Unit = { _, _ -> },
) {
    private val running = HashMap<NotebookId, Job>()
    private val failures = MutableStateFlow<Map<NotebookId, String>>(emptyMap())

    /** Starts reading [id], or does nothing while it already is. */
    fun read(id: NotebookId) = synchronized(running) {
        if (running[id]?.isActive == true) return@synchronized
        failures.update { it - id }
        running[id] = scope.launch {
            val name = title(id) ?: return@launch
            when (val r = reader(id, name, pages(id), model())) {
                is Outcome.Err -> failures.update { it + (id to message(r.error)) }
                // still named as imported: the book's own title now (a name they chose is theirs)
                is Outcome.Ok -> if (title(id) == name && r.value.title != name) runCatching { rename(id, r.value.title) }
            }
        }
    }

    /** Why the last reading of [id] stopped (null: it didn't, or it is going again). */
    fun failure(id: NotebookId): Flow<String?> = failures.map { it[id] }

    private fun message(e: DomainError) = if (e is DomainError.Invalid) e.message else failureMessage(e)
}
