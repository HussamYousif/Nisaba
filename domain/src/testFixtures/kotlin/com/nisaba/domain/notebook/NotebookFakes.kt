package com.nisaba.domain.notebook

import com.nisaba.domain.document.DocumentStore
import com.nisaba.domain.ink.InkStore
import com.nisaba.domain.ink.PageInk
import com.nisaba.domain.ink.Stroke
import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis() = now
}

class FakeIdGen(private val prefix: String = "id") : IdGen {
    private var n = 0
    override fun next() = "$prefix${++n}"
}

class FakeNotebookStore : NotebookStore {
    val books = MutableStateFlow<Map<NotebookId, NotebookWithPages>>(emptyMap())
    val log = mutableListOf<String>()

    override fun observeAll(): Flow<List<NotebookSummary>> = books.map { m ->
        m.values.map {
            val n = it.notebook
            NotebookSummary(
                n.id, n.name, n.updatedAtMillis, it.pages.size, n.coverSeed, n.documentId != null, it.pages.any { p -> p.size.isEndless },
                n.teachGoal != null, n.personaId,
            )
        }
    }
    override fun observe(id: NotebookId) = books.map { it[id] }
    override suspend fun get(id: NotebookId) =
        books.value[id]?.let { it.copy(pages = it.pages.sortedBy { p -> p.position }) }
    override suspend fun allNames() = books.value.values.map { it.notebook.name }
    override suspend fun create(notebook: Notebook, pages: List<Page>) {
        log += "create"
        books.value += (notebook.id to NotebookWithPages(notebook, pages))
    }
    override suspend fun rename(id: NotebookId, name: String, updatedAtMillis: Long) {
        val cur = books.value[id] ?: return
        books.value += (id to cur.copy(notebook = cur.notebook.copy(name = name, updatedAtMillis = updatedAtMillis)))
    }
    override suspend fun delete(id: NotebookId) { log += "deleteRow"; books.value -= id }
    override suspend fun insertPage(page: Page) {
        val cur = books.value[page.notebookId] ?: return
        books.value += (page.notebookId to cur.copy(pages = cur.pages + page))
    }
    override suspend fun repositionPages(notebookId: NotebookId, positions: List<Pair<PageId, Double>>) {
        val cur = books.value[notebookId] ?: return
        val m = positions.toMap()
        books.value += (notebookId to cur.copy(pages = cur.pages.map { it.copy(position = m[it.id] ?: it.position) }))
    }
    override suspend fun setListening(id: NotebookId, listening: Boolean) {
        val cur = books.value[id] ?: return
        books.value += (id to cur.copy(notebook = cur.notebook.copy(listening = listening)))
    }
    override suspend fun setStyle(id: NotebookId, style: AnswerStyle) {
        val cur = books.value[id] ?: return
        books.value += (
            id to cur.copy(
                notebook = cur.notebook.copy(
                    listening = style.mode != AnswerMode.WhenAsked, questionsOnly = style.mode == AnswerMode.QuestionsOnly,
                    answerLength = style.length,
                ),
            )
        )
    }
    override suspend fun touch(id: NotebookId, atMillis: Long) {
        val cur = books.value[id] ?: return
        books.value += (id to cur.copy(notebook = cur.notebook.copy(updatedAtMillis = atMillis)))
    }
    override suspend fun setPersona(id: NotebookId, personaId: String) {
        val cur = books.value[id] ?: return
        books.value += (id to cur.copy(notebook = cur.notebook.copy(personaId = personaId)))
    }
}

class FakeInkStore(private val log: MutableList<String> = mutableListOf()) : InkStore {
    val saved = mutableMapOf<PageId, List<Stroke>>()
    val deleted = mutableListOf<PageId>()
    override suspend fun load(pageId: PageId) = PageInk(pageId, saved[pageId].orEmpty())
    override suspend fun save(pageId: PageId, strokes: List<Stroke>) { saved[pageId] = strokes }
    override suspend fun deletePages(ids: List<PageId>) { log += "deleteFiles"; deleted += ids }
}

class FakeDocumentStore : DocumentStore {
    val deleted = mutableListOf<DocumentId>()
    override suspend fun delete(id: DocumentId) { deleted += id }
}
