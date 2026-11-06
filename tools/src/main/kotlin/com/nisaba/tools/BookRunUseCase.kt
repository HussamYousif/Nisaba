package com.nisaba.tools

import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.BookPlace
import com.nisaba.domain.book.BookStep
import com.nisaba.domain.book.BookStore
import com.nisaba.domain.book.ReadBookUseCase
import com.nisaba.domain.book.bookHeard
import com.nisaba.domain.book.hasNextTag
import com.nisaba.domain.conversation.ContextInput
import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStatus
import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleOptions
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.conversation.PageSnapshot
import com.nisaba.domain.conversation.Purpose
import com.nisaba.domain.conversation.buildRequest
import com.nisaba.domain.lesson.LessonTopic
import com.nisaba.domain.lesson.formatLessonProgress
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
 * then the map page and the first [concepts] pages of the walk, and, given [answers], what they write on the last
 * concept's pages one after another (a guess, answers, "next"), each judged as the app would; then, for up to [student]
 * more turns, a simulated learner answers the tutor's questions until the walk moves on. Prints as it goes; for trying a
 * book before it goes on the tablet.
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

    operator fun invoke(
        title: String,
        pages: List<String>,
        persona: Persona,
        concepts: Int,
        answers: List<String>,
        student: Int = 0,
    ) = runBlocking {
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
        if (concepts > 0) walk(map, pages, persona, concepts.coerceAtMost(map.concepts.size), answers, student)
        out("\ntotal tokens in $tokensIn out $tokensOut")
    }

    private fun printMap(map: BookMap) {
        out("# ${map.title}\n${map.thesis}\n")
        map.concepts.forEachIndexed { i, c ->
            if (c.part.isNotEmpty() && c.part != map.concepts.getOrNull(i - 1)?.part) out("## ${c.part}")
            out("${i + 1}. ${c.title} (${c.pages}) :: ${c.summary}")
        }
    }

    private fun walk(map: BookMap, pages: List<String>, persona: Persona, concepts: Int, answers: List<String>, student: Int) {
        val history = ArrayList<Exchange>()
        for (step in listOf(BookStep.Overview) + (0 until concepts).map { BookStep.Concept(it) }) {
            val input = ContextInput(
                pages, null, history, NO_PICTURE, settings, purpose = Purpose.Book(step), persona = persona, book = BookPlace(map, null),
            )
            val n = (step as? BookStep.Concept)?.index?.plus(1)?.toString().orEmpty()
            history += say(input, bookHeard(map, step), "--- ${step::class.simpleName} $n")
        }
        val concept = map.concepts[concepts - 1]
        val judged = ArrayList<LessonTopic>()
        val scripted = ArrayDeque(answers)
        var simulated = 0
        var movedOn = false
        while (!movedOn && (scripted.isNotEmpty() || simulated < student)) {
            val answer = scripted.removeFirstOrNull() ?: studentAnswer(history.last().answerMarkup).also { simulated++ }
            val done = judged.any { it.topic.equals(concept.title, ignoreCase = true) }
            val input = ContextInput(
                pages, null, history, hand.snapshot(answer, answer.hashCode().toLong()), settings,
                lessonProgress = formatLessonProgress(judged), persona = persona, book = BookPlace(map, concepts - 1, done),
            )
            val reply = say(input, answer, "--- they write: \"$answer\"")
            history += reply
            val recorded = parseTopics(reply.answerMarkup, 0)
            judged.removeAll { t -> recorded.any { it.topic.equals(t.topic, ignoreCase = true) } }
            judged += recorded
            val topics = recorded.joinToString { "${it.topic} = ${it.status}" }
            movedOn = hasNextTag(reply.answerMarkup)
            out("topics: ${topics.ifEmpty { "none" }}" + if (movedOn) " · <next/>: the walk moves on" else "")
        }
    }

    /** A keen but imperfect learner's handwritten reply to the tutor's last words. */
    private fun studentAnswer(tutor: String): String {
        val r = client.ask(
            OracleRequest(
                system = STUDENT, documentContext = null, history = emptyList(),
                instruction = com.nisaba.domain.search.plainText(tutor), images = emptyList(),
                options = OracleOptions(settings.model, thinking = false, maxTokens = STUDENT_TOKENS, temperature = 1.0),
            ),
        )
        synchronized(this) { tokensIn += r.inputTokens; tokensOut += r.outputTokens }
        return r.text.trim().lines().joinToString(" ").take(STUDENT_CHARS)
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
        const val STUDENT_TOKENS = 120
        const val STUDENT_CHARS = 200
        const val STUDENT = "You are a student learning a book with a tutor, in a handwritten notebook. The message is what " +
            "the tutor just wrote. Reply only with what you would write by hand under it: one or two short lines, no quotes. " +
            "Answer like a keen but imperfect learner: mostly right, now and then wrong or unsure. When the tutor invites " +
            "you to write \"next\" (the concept is done), write exactly: next"
        const val MILLIS = 1000
    }
}
