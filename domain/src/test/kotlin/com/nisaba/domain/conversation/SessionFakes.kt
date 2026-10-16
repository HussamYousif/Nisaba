package com.nisaba.domain.conversation

import com.nisaba.domain.answer.Block
import com.nisaba.domain.shared.Clock
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Wall clock that follows the test scheduler's virtual time. */
class VirtualClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun nowMillis() = 1_000_000L + scheduler.currentTime
}

/** Scriptable model: emits [chunks] with [gapMillis] between them, or fails with [failure] before emitting. */
class FakeOracle(
    var chunks: List<String> = emptyList(),
    var gapMillis: Long = 0,
    var failure: OracleException? = null,
    var ended: EndReason? = null,
) : Oracle {
    val requests = mutableListOf<OracleRequest>()
    var started = 0
    var cancelled = 0
    var completed = 0

    override fun ask(request: OracleRequest): Flow<OracleEvent> = flow {
        requests += request; started++
        failure?.let { throw it }
        for (c in chunks) { delay(gapMillis); emit(OracleEvent.Delta(c)) }
        ended?.let { emit(OracleEvent.Ended(it)) }
        emit(OracleEvent.Usage(1500, 40, 1024))
    }.onCompletion { cause -> if (cause == null) completed++ else cancelled++ }
}

class FakeSnapshotter : PageSnapshotter {
    val focuses = mutableListOf<PageRect?>()
    val pages = mutableListOf<PageId>()
    val fresh = mutableListOf<Set<com.nisaba.domain.shared.StrokeId>>()
    val labels = mutableListOf<List<SnapshotLabel>>()
    override suspend fun snapshot(
        pageId: PageId,
        focus: PageRect?,
        fresh: Set<com.nisaba.domain.shared.StrokeId>,
        labels: List<SnapshotLabel>,
    ): PageSnapshot {
        focuses += focus; pages += pageId; this.fresh += fresh; this.labels += labels
        return PageSnapshot(byteArrayOf(1), byteArrayOf(2))
    }
}

class FakeExchangeStore : ExchangeStore {
    private val all = MutableStateFlow<List<Exchange>>(emptyList())
    override suspend fun append(exchange: Exchange) { all.value = all.value.filter { it.id != exchange.id } + exchange } // REPLACE
    override suspend fun listByNotebook(id: NotebookId) = all.value
    override fun observeByNotebook(id: NotebookId) = all.map { it }
    override suspend fun get(id: ExchangeId) = all.value.firstOrNull { it.id == id }
    override suspend fun search(query: String, limit: Int) = all.value
        .filter { it.heard.contains(query, ignoreCase = true) || it.answerMarkup.contains(query, ignoreCase = true) }
        .sortedByDescending { it.createdAtMillis }.take(limit)
    override suspend fun usage() = all.value.map {
        com.nisaba.domain.usage.ExchangeUsage(it.model, it.createdAtMillis, it.inputTokens, it.cachedTokens, it.outputTokens)
    }
    val saved: List<Exchange> get() = all.value
}

class RecordingSurface : ConversationSurface {
    val log = mutableListOf<String>()
    val blocks = mutableListOf<Block>()
    var failure: DomainError? = null
    val memoryNotes = mutableListOf<String>()
    override fun showInkwell(utt: Utterance) { log += "ink+" }
    override fun hideInkwell(dry: Boolean) { log += if (dry) "ink-dry" else "ink-handoff" }
    val exchangeIds = mutableListOf<ExchangeId>()
    override fun planBlock(reqId: Int, block: Block, exchangeId: ExchangeId, personaId: String) {
        log += "plan$reqId"; blocks += block; exchangeIds += exchangeId
    }
    override fun stopScribe(reqId: Int) { log += "stop$reqId" }
    override fun resumeScribe(reqId: Int) { log += "resume$reqId" }
    override fun showFailure(error: DomainError) { log += "fail"; failure = error }
    override fun clearFailure() { log += "clear"; failure = null }
    override fun showMemoryNote(note: String) { memoryNotes += note }
    override fun showCutShort() { log += "cut" }
    val reminders = mutableListOf<Reminder>()
    override fun offerReminder(reminder: Reminder) { reminders += reminder }
    override fun bookNext() { log += "next" }
}

class FakeContextPorts(
    var pages: List<String> = emptyList(),
    override var currentDocumentPage: Int? = null,
    override val teachGoal: String? = null,
    override var persona: com.nisaba.domain.persona.Persona = com.nisaba.domain.persona.PersonaRegistry.default,
) : ConversationContextPorts {
    override suspend fun documentPages() = pages
}

class FakeLessonStore : com.nisaba.domain.lesson.LessonStore {
    val rows = MutableStateFlow<List<com.nisaba.domain.lesson.LessonTopic>>(emptyList())
    override suspend fun topics(notebookId: NotebookId) = rows.value
    override fun observe(notebookId: NotebookId): Flow<List<com.nisaba.domain.lesson.LessonTopic>> = rows
    override suspend fun upsert(notebookId: NotebookId, topics: List<com.nisaba.domain.lesson.LessonTopic>) {
        val keep = rows.value.filter { old -> topics.none { it.topic.equals(old.topic, ignoreCase = true) } }
        rows.value = keep + topics
    }
}
