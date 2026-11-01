package com.nisaba.tools

import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.BookPlace
import com.nisaba.domain.book.BookStep
import com.nisaba.domain.book.BookStore
import com.nisaba.domain.book.ReadBookUseCase
import com.nisaba.domain.book.bookHeard
import com.nisaba.domain.conversation.ContextInput
import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStatus
import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.conversation.PageSnapshot
import com.nisaba.domain.conversation.Purpose
import com.nisaba.domain.conversation.buildRequest
import com.nisaba.domain.lesson.parseTopics
import com.nisaba.domain.persona.Persona
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking

/**
 * A book walk without the tablet: the real reading of [pages] into a map (the app's [ReadBookUseCase] and prompts),
 * then the map page and the first [concepts] pages of the walk, and, given [answer], their written answer to the last
 * page's question, judged as the app would judge it. Prints as it goes; for trying a book before it goes on the tablet.
 */
class BookRunUseCase(
    private val client: EvalOracleClient,
    private val hand: EvalPageRenderer,
    private val settings: Settings = Settings(),
    private val out: (String) -> Unit = ::println,
) {
    private var tokensIn = 0
    private var tokensOut = 0

    private val oracle = object : Oracle {
        override fun ask(request: OracleRequest): Flow<OracleEvent> = flow {
            val r = client.ask(request)
            synchronized(this@BookRunUseCase) { tokensIn += r.inputTokens; tokensOut += r.outputTokens }
            emit(OracleEvent.Delta(r.text))
        }.flowOn(Dispatchers.IO) // the client blocks: let parts be read side by side
    }

    operator fun invoke(title: String, pages: List<String>, persona: Persona, concepts: Int, answer: String?) = runBlocking {
        val started = System.currentTimeMillis()
        out("${pages.size} pages, ${pages.sumOf { it.length }} characters")
        val read = ReadBookUseCase(oracle, MemoryBookStore())(NotebookId("tool"), title, pages, settings.model) { done, parts ->
            out("  read $done of $parts parts")
        }
        val map = when (read) {
            is Outcome.Err -> { out("reading failed: ${read.error}"); return@runBlocking }
            is Outcome.Ok -> read.value
        }
        out("map in ${(System.currentTimeMillis() - started) / MILLIS}s · tokens in $tokensIn out $tokensOut\n")
        printMap(map)
        if (concepts > 0) walk(map, pages, persona, concepts.coerceAtMost(map.concepts.size), answer)
        out("\ntotal tokens in $tokensIn out $tokensOut")
    }

    private fun printMap(map: BookMap) {
        out("# ${map.title}\n${map.thesis}\n")
        map.concepts.forEachIndexed { i, c ->
            if (c.part.isNotEmpty() && c.part != map.concepts.getOrNull(i - 1)?.part) out("## ${c.part}")
            out("${i + 1}. ${c.title} (${c.pages}) :: ${c.summary}")
        }
    }

    private fun walk(map: BookMap, pages: List<String>, persona: Persona, concepts: Int, answer: String?) {
        val history = ArrayList<Exchange>()
        for (step in listOf(BookStep.Overview) + (0 until concepts).map { BookStep.Concept(it) }) {
            val input = ContextInput(
                pages, null, history, NO_PICTURE, settings, purpose = Purpose.Book(step), persona = persona, book = BookPlace(map, null),
            )
            val n = (step as? BookStep.Concept)?.index?.plus(1)?.toString().orEmpty()
            history += say(input, bookHeard(map, step), "--- ${step::class.simpleName} $n")
        }
        if (answer == null) return
        val place = BookPlace(map, concepts - 1)
        val input = ContextInput(
            pages, null, history, hand.snapshot(answer, answer.hashCode().toLong()), settings, persona = persona, book = place,
        )
        val judged = say(input, answer, "--- their answer: \"$answer\"")
        out("topics: " + parseTopics(judged.answerMarkup, 0).joinToString { "${it.topic} = ${it.status}" }.ifEmpty { "none" })
    }

    private fun say(input: ContextInput, heard: String, head: String): Exchange {
        val request = buildRequest(input)
        val reply = client.ask(request)
        synchronized(this) { tokensIn += reply.inputTokens; tokensOut += reply.outputTokens }
        val doc = request.documentContext?.length ?: 0
        out("\n$head · document $doc chars · ${reply.millis}ms · in ${reply.inputTokens} out ${reply.outputTokens}")
        out(reply.text.trim())
        return Exchange(
            ExchangeId("t${System.nanoTime()}"), NotebookId("tool"), PageId("p"), heard, reply.text, ExchangeStatus.Complete,
            System.currentTimeMillis(), settings.model, 0, 0, reply.inputTokens, reply.outputTokens,
        )
    }

    private class MemoryBookStore : BookStore {
        private val map = MutableStateFlow<BookMap?>(null)
        override suspend fun get(notebookId: NotebookId) = map.value
        override fun observe(notebookId: NotebookId) = map
        override suspend fun save(notebookId: NotebookId, map: BookMap) { this.map.value = map }
    }

    private companion object {
        val NO_PICTURE = PageSnapshot(ByteArray(0), null)
        const val MILLIS = 1000
    }
}
