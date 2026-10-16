package com.nisaba.domain.book

import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleException
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReadBookUseCaseTest {
    private val id = NotebookId("nb")
    private val store = FakeBookStore()

    private val mapReply = """
        <thesis>Systems trade consistency for availability.</thesis>
        <concept pages="1-40">Reliability :: Faults versus failures</concept>
        <concept pages="41-120">Replication :: Copies and their lag</concept>
        <concept pages="121-300">Partitioning :: Splitting data by key</concept>
    """.trimIndent()

    /** Answers a part's reading with one idea named after its first page, the map with [mapReply]; fails when told. */
    private inner class BookOracle(private val failOnPart: Int? = null) : Oracle {
        val asked = mutableListOf<OracleRequest>()
        override fun ask(request: OracleRequest): Flow<OracleEvent> = flow {
            synchronized(asked) { asked += request }
            val first = Regex("""Pages (\d+)""").find(request.instruction)?.groupValues?.get(1)?.toInt()
            if (first != null && first == failOnPart) throw OracleException(DomainError.Network(NetworkKind.Offline))
            emit(OracleEvent.Delta(if (first != null) "<idea pages=\"$first\">Idea $first :: about p. $first</idea>" else mapReply))
        }
    }

    private val longBook = List(300) { "Text of page ${it + 1}. ".repeat(110) } // ~750k characters: read in parts

    @Test fun aLongBookIsReadInPartsThenMapped() = runTest {
        val oracle = BookOracle()
        val progress = mutableListOf<Pair<Int, Int>>()
        val r = ReadBookUseCase(oracle, store)(id, "DDIA", longBook, "flash") { done, parts -> progress += done to parts }
        val map = assertIs<Outcome.Ok<BookMap>>(r).value
        assertEquals(listOf("Reliability", "Replication", "Partitioning"), map.concepts.map { it.title })
        assertTrue(map.ready && store.get(id)!!.ready)
        val parts = bookParts(longBook).size
        assertEquals(parts + 1, oracle.asked.size) // every part once, then the map
        assertEquals(parts to parts, progress.last())
        val mapping = oracle.asked.last()
        assertTrue("Idea 1:" in mapping.instruction && mapping.documentContext == null, "the map is made from the ideas")
    }

    @Test fun aFailedPartKeepsWhatWasReadAndARetryReadsOnlyTheRest() = runTest {
        val parts = bookParts(longBook)
        val failing = parts[3].first + 1
        val r1 = ReadBookUseCase(BookOracle(failOnPart = failing), store)(id, "DDIA", longBook, "flash")
        assertIs<Outcome.Err>(r1)
        val kept = store.get(id)!!.reading!!
        assertTrue(kept.done in 1 until parts.size, "what was read is kept: ${kept.done}")
        val oracle = BookOracle()
        assertIs<Outcome.Ok<BookMap>>(ReadBookUseCase(oracle, store)(id, "DDIA", longBook, "flash"))
        assertEquals(parts.size - kept.done + 1, oracle.asked.size) // only the parts not yet read, then the map
    }

    @Test fun aShortBookIsMappedFromItsWholeTextAndAScanIsRefused() = runTest {
        val oracle = BookOracle()
        val short = List(40) { "A page of an essay. ".repeat(60) }
        assertIs<Outcome.Ok<BookMap>>(ReadBookUseCase(oracle, store)(id, "Essay", short, "flash"))
        assertEquals(1, oracle.asked.size)
        assertTrue(oracle.asked.single().documentContext!!.startsWith("[p.1] "))
        val scan = ReadBookUseCase(BookOracle(), FakeBookStore())(NotebookId("scan"), "Scan", List(40) { "" }, "flash")
        assertEquals(DomainError.Invalid(ReadBookUseCase.NO_TEXT), (scan as Outcome.Err).error)
    }
}
