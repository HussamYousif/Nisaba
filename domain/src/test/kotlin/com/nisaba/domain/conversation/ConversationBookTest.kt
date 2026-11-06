package com.nisaba.domain.conversation

import com.nisaba.domain.book.BookMap
import com.nisaba.domain.book.BookStep
import com.nisaba.domain.book.Concept
import com.nisaba.domain.book.FakeBookStore
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationBookTest {
    private val nb = NotebookId("n")
    private val page = PageId("p2")
    private val oracle = FakeOracle()
    private val store = FakeExchangeStore()
    private val surface = RecordingSurface()
    private val books = FakeBookStore()
    private val lessons = FakeLessonStore()
    private val map = BookMap(
        "DDIA", "Trade-offs all the way down.",
        listOf(Concept("Reliability", "", 1, 40), Concept("Replication", "", 150, 170), Concept("Partitioning", "", 200, 260)),
        overviewPageId = PageId("p1"),
    )

    private class BookPorts(var map: BookMap?, override var bookConcept: Int? = null) : ConversationContextPorts {
        override suspend fun documentPages() = List(300) { "Words of page ${it + 1}. ".repeat(80) } // too long to send whole
        override val currentDocumentPage: Int? = null
        override val book: BookMap? get() = map
    }

    private suspend fun TestScope.session(ports: BookPorts): ConversationSession {
        books.save(nb, map)
        val deps = ConversationDeps(
            nb, oracle, FakeSnapshotter(), store, surface, ports, VirtualClock(testScheduler), FakeIdGen("ex"), books = books,
            lessons = lessons,
        )
        return ConversationSession(deps, backgroundScope, Settings(), listening = true)
    }

    private fun ConversationSession.finishWriting() {
        val st = status.value.state as? ConvState.Scribing ?: return
        onScribeFinished(st.reqId, st.blocks)
    }

    @Test fun aConceptGetsAFreshPageFromItsOwnPagesAndTheMapRemembersIt() = runTest {
        oracle.chunks = listOf("<answer>\n2. Replication\n\nCopies of the data…\n</answer>", "<heard></heard>")
        val s = session(BookPorts(map))
        s.writeFresh(page, Purpose.Book(BookStep.Concept(1))); runCurrent()
        s.finishWriting(); runCurrent()
        val sent = oracle.requests.single()
        assertTrue("# Teaching a book" in sent.system && "2. Replication (pp. 150–170)" in sent.system)
        assertTrue(sent.instruction.startsWith("Open concept 2 of 3"), sent.instruction)
        val doc = sent.documentContext!!
        assertTrue("[p.160]" in doc && "[p.1] " !in doc, "the window sits on the concept's pages")
        assertTrue(sent.images.isEmpty())
        assertEquals(page, books.get(nb)!!.concepts[1].pageId)
        assertEquals("(asks to be taught: Replication)", store.saved.single().heard)
    }

    @Test fun theMapOfTheBookIsWrittenFromTheMapAlone() = runTest {
        oracle.chunks = listOf("<answer>\nDDIA\n</answer>", "<heard></heard>")
        val s = session(BookPorts(map.copy(overviewPageId = null)))
        s.writeFresh(PageId("p1"), Purpose.Book(BookStep.Overview)); runCurrent()
        assertNull(oracle.requests.single().documentContext)
    }

    @Test fun writingNextMovesTheWalkOnAndWritesNothing() = runTest {
        oracle.chunks = listOf("<next/>", "<heard>neste</heard>")
        val ports = BookPorts(map, bookConcept = 0)
        val s = session(ports)
        s.onPenDown(); runCurrent()
        s.onPenUp(StrokeId("a"), PageRect(100f, 200f, 300f, 260f), page); runCurrent()
        advanceTimeBy(4000); runCurrent()
        assertTrue("next" in surface.log, surface.log.toString())
        assertTrue(surface.blocks.isEmpty())
        val asked = oracle.requests.single()
        assertTrue("concept 1 of 3" in asked.instruction && "[p.20]" in asked.documentContext!!)
        assertFalse(store.saved.single().status == ExchangeStatus.Complete)
        assertTrue(lessons.rows.value.isEmpty()) // a finished concept was judged already: nothing to record
    }

    @Test fun nextMovesOnAtOnceOnceTheConceptIsJudged() = runTest {
        oracle.chunks = listOf("<next/>", "<heard>next</heard>")
        val s = session(BookPorts(map, bookConcept = 0))
        s.onPenDown(); runCurrent()
        s.onPenUp(StrokeId("a"), PageRect(100f, 200f, 300f, 260f), page); runCurrent()
        advanceTimeBy(4000); runCurrent()
        assertTrue("still being taught" in oracle.requests.single().instruction) // not judged yet: a nudge first
        lessons.upsert(nb, listOf(com.nisaba.domain.lesson.LessonTopic("reliability", com.nisaba.domain.lesson.TopicStatus.Learned, 0)))
        s.onPenDown(); runCurrent()
        s.onPenUp(StrokeId("b"), PageRect(100f, 300f, 300f, 360f), page); runCurrent()
        advanceTimeBy(4000); runCurrent()
        assertTrue("This concept is done and judged" in oracle.requests.last().instruction, oracle.requests.last().instruction)
    }

    @Test fun movingOnFromAnUnfinishedConceptMarksItShakyAndWritesNothing() = runTest {
        oracle.chunks = listOf("<next/><topic status=\"shaky\">Reliability</topic>", "<heard>next</heard>")
        val s = session(BookPorts(map, bookConcept = 0))
        s.onPenDown(); runCurrent()
        s.onPenUp(StrokeId("a"), PageRect(100f, 200f, 300f, 260f), page); runCurrent()
        advanceTimeBy(4000); runCurrent()
        assertTrue("next" in surface.log, surface.log.toString())
        assertTrue(surface.blocks.isEmpty())
        assertEquals(listOf("Reliability"), lessons.rows.value.map { it.topic })
    }
}
