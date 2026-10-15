package com.nisaba.domain.conversation

import com.nisaba.domain.answer.ParseEvent
import com.nisaba.domain.answer.AnswerParser
import com.nisaba.domain.memory.FactCategory
import com.nisaba.domain.memory.FakeMemoryStore
import com.nisaba.domain.memory.fact
import com.nisaba.domain.notebook.CreateNotebookUseCase
import com.nisaba.domain.notebook.FakeClock
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.notebook.FakeNotebookStore
import com.nisaba.domain.notebook.SetPrivateUseCase
import com.nisaba.domain.search.SearchUseCase
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Learning and habits (M16): quiz, today's page, recap, review; private notebooks; reminders. */
class ConversationHabitsTest {
    private val page = PageId("p")
    private val oracle = FakeOracle()
    private val snapshotter = FakeSnapshotter()
    private val store = FakeExchangeStore()
    private val surface = RecordingSurface()
    private val memory = FakeMemoryStore(listOf(fact(1, "Is a nurse", FactCategory.Me)))

    private class Context(override val isPrivate: Boolean) : ConversationContextPorts {
        override suspend fun documentPages() = emptyList<String>()
        override val currentDocumentPage: Int? = null
        override val teachGoal: String? = null
    }

    private fun TestScope.session(private: Boolean = false) = ConversationSession(
        ConversationDeps(
            NotebookId("n"), oracle, snapshotter, store, surface, Context(private), VirtualClock(testScheduler), FakeIdGen("ex"),
            memory = memory,
        ),
        backgroundScope, Settings(), true,
    )

    @Test fun aQuizIsWrittenFromTheHistoryAloneWithoutAPictureOfThePage() = runTest {
        oracle.chunks = listOf("<answer>\n1. What carved the fjords?\n</answer><heard>-</heard>")
        val s = session()
        s.writeFresh(page, Purpose.Quiz); runCurrent()
        assertTrue(snapshotter.pages.isEmpty()) // no picture taken
        val req = oracle.requests.single()
        assertTrue(req.images.isEmpty())
        assertTrue(req.instruction.startsWith(INSTRUCTION_QUIZ))
        assertTrue(req.instruction.contains("It is now ")) // dates and reminders
        s.writeFresh(page, Purpose.Writing) // only the text-only kinds are written fresh
        runCurrent(); assertEquals(1, oracle.requests.size)
    }

    @Test fun aPrivateNotebookNeitherSeesNorTeachesMemory() = runTest {
        oracle.chunks = listOf("<answer>\nNoted.\n</answer><remember category=\"me\">Keeps a diary</remember><heard>dear diary</heard>")
        val s = session(private = true)
        s.onPenDown(); runCurrent()
        s.onPenUp(StrokeId("w"), PageRect(100f, 100f, 300f, 140f), page); runCurrent()
        testScheduler.advanceTimeBy(10_000); runCurrent()
        val req = oracle.requests.single()
        assertEquals(null, req.memoryContext)
        assertTrue(req.instruction.contains(INSTRUCTION_PRIVATE))
        assertEquals(listOf("Is a nurse"), memory.facts.value.map { it.text }) // nothing learned
    }

    @Test fun aReplyAskingForAReminderOffersItAndNeverWritesTheTag() = runTest {
        oracle.chunks = listOf(
            "<answer>\nI'll remind you.\n</answer>",
            "<task when=\"2026-10-16T09:00\">call Ola</task><heard>remind me tomorrow 9: call Ola</heard>",
        )
        val s = session()
        s.onPenDown(); runCurrent()
        s.onPenUp(StrokeId("w"), PageRect(100f, 100f, 300f, 140f), page); runCurrent()
        testScheduler.advanceTimeBy(10_000); runCurrent()
        val st = s.status.value.state as? ConvState.Scribing
        if (st != null) s.onScribeFinished(st.reqId, st.blocks)
        runCurrent()
        assertEquals(listOf(Reminder("call Ola", "2026-10-16T09:00")), surface.reminders)
    }

    @Test fun remindersAreParsedAndKeptOutOfTheInk() {
        val reply = "<answer>\nSure.\n<task when=\"2026-10-16T09:00:00\">call Ola</task>\n</answer><heard>x</heard>"
        assertEquals(listOf(Reminder("call Ola", "2026-10-16T09:00")), parseReminders(reply))
        val p = AnswerParser()
        val ink = (p.feed(reply) + p.finish()).filterIsInstance<ParseEvent.BlockReady>()
        assertEquals(1, ink.size) // "Sure." only
        assertTrue(parseReminders("<task when=\"tomorrow\">x</task>").isEmpty()) // a time it can't use
        assertTrue(nowInWords(0, ZoneId.of("UTC")).startsWith("Thursday 1 January 1970"))
    }

    @Test fun privateNotebooksStayOutOfSearch() = runTest {
        val notebooks = FakeNotebookStore()
        val nb = CreateNotebookUseCase(notebooks, FakeClock(), FakeIdGen("n"))("Diary")
        val p = notebooks.get(nb.id)!!.pages.single().id
        val markup = "<answer>\nok\n</answer>"
        store.append(Exchange(ExchangeId("a"), nb.id, p, "secret plans", markup, ExchangeStatus.Complete, 1, "m", 7, 0, 0, 0))
        assertEquals(1, SearchUseCase(store, notebooks)("secret").size)
        SetPrivateUseCase(notebooks)(nb.id, true)
        assertFalse(SearchUseCase(store, notebooks)("secret").isNotEmpty())
    }
}
