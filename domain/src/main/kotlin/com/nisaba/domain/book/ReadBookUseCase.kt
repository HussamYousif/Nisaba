package com.nisaba.domain.book

import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleException
import com.nisaba.domain.conversation.OracleOptions
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Reads a book into the map of its core concepts. A short book goes in one request; a long one is read a part at a time
 * (a few at once), each part's ideas kept as they come so a restart picks up where it stopped, and the map is made from
 * all of them. Text-only, cheap requests; the map is saved for notebook [notebookId] when made.
 */
class ReadBookUseCase(private val oracle: Oracle, private val store: BookStore) {
    suspend operator fun invoke(
        notebookId: NotebookId,
        title: String,
        pages: List<String>,
        model: String,
        onProgress: (read: Int, parts: Int) -> Unit = { _, _ -> },
    ): Outcome<BookMap> {
        store.get(notebookId)?.takeIf { it.ready }?.let { return Outcome.Ok(it) }
        if (pages.sumOf { it.length } < MIN_BOOK_CHARS) return Outcome.Err(DomainError.Invalid(NO_TEXT))
        val parts = bookParts(pages)
        var reading = store.get(notebookId)?.reading?.takeIf { it.parts == parts.size } ?: BookReading(parts.size)
        store.save(notebookId, BookMap(title, reading = reading))
        onProgress(reading.done, parts.size)
        if (parts.size > 1) {
            val lock = Mutex()
            var failed: DomainError? = null
            coroutineScope {
                val gate = Semaphore(PARALLEL)
                parts.indices.filter { it !in reading.ideas }.map { i ->
                    async {
                        gate.withPermit {
                            if (failed != null) return@withPermit
                            val part = formatPages(pages, parts[i])
                            when (val r = ask(request(READ_SYSTEM, part, readInstruction(title, parts[i]), model, READ_TOKENS))) {
                                is Outcome.Err -> lock.withLock { failed = failed ?: r.error }
                                is Outcome.Ok -> lock.withLock {
                                    reading = reading.copy(ideas = reading.ideas + (i to parseIdeas(r.value, pages.size)))
                                    store.save(notebookId, BookMap(title, reading = reading))
                                    onProgress(reading.done, parts.size)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            failed?.let { return Outcome.Err(it) }
        }
        val concepts = conceptRange(pages)
        val tokens = MAP_TOKENS + TOKENS_PER_CONCEPT * concepts.last // a long book's map is long
        val mapping = if (parts.size == 1) {
            request(MAP_SYSTEM, formatPages(pages, pages.indices), mapInstructionWhole(title, pages.size, concepts), model, tokens)
        } else {
            val ideas = reading.ideas.toSortedMap().values.flatten()
            request(MAP_SYSTEM, null, mapInstructionFromIdeas(title, pages.size, ideas, concepts), model, tokens)
        }
        val reply = when (val r = ask(mapping)) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        val map = parseBookMap(reply, title, pages.size) ?: return Outcome.Err(DomainError.Invalid(NO_MAP))
        store.save(notebookId, map)
        return Outcome.Ok(map)
    }

    private fun request(system: String, document: String?, instruction: String, model: String, maxTokens: Int) = OracleRequest(
        system = system, documentContext = document, history = emptyList(), instruction = instruction, images = emptyList(),
        options = OracleOptions(model, thinking = false, maxTokens = maxTokens, temperature = TEMPERATURE),
    )

    private suspend fun ask(request: OracleRequest): Outcome<String> {
        val reply = StringBuilder()
        return try {
            oracle.ask(request).collect { if (it is OracleEvent.Delta) reply.append(it.text) }
            Outcome.Ok(reply.toString())
        } catch (e: OracleException) {
            Outcome.Err(e.error)
        }
    }

    companion object {
        const val NO_TEXT = "This PDF has almost no text to read (a scan?), so there is no map to make of it."
        const val NO_MAP = "Couldn't make a map of this book."
        private const val PARALLEL = 4
        private const val READ_TOKENS = 1200
        private const val MAP_TOKENS = 800
        private const val TOKENS_PER_CONCEPT = 80
        private const val TEMPERATURE = 0.3
    }
}
