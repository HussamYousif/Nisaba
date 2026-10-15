package com.nisaba.domain.conversation

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.memory.FactCategory
import com.nisaba.domain.memory.FakeMemoryStore
import com.nisaba.domain.memory.fact
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSessionTest {
    private val page = PageId("p")
    private val oracle = FakeOracle()
    private val snapshotter = FakeSnapshotter()
    private val store = FakeExchangeStore()
    private val surface = RecordingSurface()
    private val memory = FakeMemoryStore(listOf(fact(1, "Is a nurse", FactCategory.Me)))

    private class Rig(val session: ConversationSession, val scope: TestScope)

    private val lessons = FakeLessonStore()

    private fun TestScope.rig(listening: Boolean = true, context: FakeContextPorts = FakeContextPorts()): Rig {
        val deps = ConversationDeps(
            NotebookId("n"), oracle, snapshotter, store, surface, context, VirtualClock(testScheduler), FakeIdGen("ex"),
            memory = memory, lessons = lessons,
        )
        return Rig(ConversationSession(deps, backgroundScope, Settings(), listening), this)
    }

    private fun Rig.write(id: String, r: PageRect = PageRect(100f, 200f, 300f, 260f)) {
        session.onPenDown(); scope.runCurrent()
        session.onPenUp(StrokeId(id), r, page); scope.runCurrent()
    }

    /** The hand reports having drawn every block it was given so far. */
    private fun Rig.handDone() {
        val st = session.status.value.state as? ConvState.Scribing ?: return
        session.onScribeFinished(st.reqId, st.blocks)
    }

    private suspend fun TestScope.wait(ms: Long) { advanceTimeBy(ms); runCurrent() }

    private val hello = listOf("<answer>\nHello there.\n\n", "Second para.\n</answer>", "<heard>say hi</heard>")

    @Test fun questionFlowsFromPauseToSavedExchange() = runTest {
        oracle.chunks = hello
        val rig = rig()
        rig.write("a")
        wait(3400); assertEquals(0, oracle.started)                         // not yet: 3.5 s pause
        wait(200)
        assertEquals(1, oracle.started)
        assertEquals(listOf("ink+", "ink-handoff", "plan1", "plan1"), surface.log)
        assertEquals(2, surface.blocks.size)
        assertEquals(Block.Paragraph(listOf(Inline.Text("Hello there."))), surface.blocks[0])
        rig.handDone(); runCurrent()
        assertEquals(ConvState.Idle, rig.session.status.value.state)
        val saved = store.saved.single()
        assertEquals(ExchangeStatus.Complete, saved.status)
        assertEquals("say hi", saved.heard)
        assertTrue(saved.answerMarkup.contains("Hello there."))
        assertEquals(1500, saved.inputTokens)
    }

    @Test fun eachRequestSpeaksInTheNotebooksVoiceOfTheMomentAndTheExchangeRecordsIt() = runTest {
        oracle.chunks = hello
        val context = FakeContextPorts(persona = PersonaRegistry.of("yukino"))
        val rig = rig(context = context)
        rig.write("a"); wait(3600); rig.handDone(); runCurrent()
        assertTrue(oracle.requests.last().system.startsWith(PersonaRegistry.of("yukino").voice))
        assertEquals("yukino", store.saved.last().personaId)
        context.persona = PersonaRegistry.of("zagreus") // the voice changed from the ⋯ menu while the page was open
        rig.write("b"); wait(3600); rig.handDone(); runCurrent()
        assertTrue(oracle.requests.last().system.startsWith("You are Zagreus"))
        assertEquals("zagreus", store.saved.last().personaId)
    }

    @Test fun answerStrokesCanPointAtTheSavedExchange() = runTest {
        oracle.chunks = hello
        val rig = rig()
        rig.write("a"); wait(3600); rig.handDone(); runCurrent()
        assertEquals(store.saved.single().id, surface.exchangeIds.distinct().single())
    }

    @Test fun leavingThePageMidAnswerKeepsWhatWasWritten() = runTest {
        oracle.chunks = hello; oracle.gapMillis = 1_000
        val rig = rig()
        rig.write("a"); wait(3600); wait(1_200)                            // first block is being written
        rig.session.close(); runCurrent()
        assertEquals(1, oracle.cancelled)
        val saved = store.saved.single()
        assertEquals(ExchangeStatus.Interrupted, saved.status)
        assertTrue(saved.answerMarkup.contains("Hello there."))
    }

    @Test fun leavingAfterTheStreamEndedButBeforeTheHandFinishedSavesIt() = runTest {
        oracle.chunks = hello
        val rig = rig()
        rig.write("a"); wait(3600)
        assertTrue(store.saved.isEmpty())                                   // the hand is still writing
        rig.session.close(); runCurrent()
        assertEquals(ExchangeStatus.Interrupted, store.saved.single().status)
    }

    @Test fun leavingWhileThinkingLeavesNoTraceAndIgnoresLateEvents() = runTest {
        oracle.chunks = hello; oracle.gapMillis = 5_000
        val rig = rig()
        rig.write("a"); wait(3600)
        rig.session.close(); runCurrent()
        rig.write("b"); wait(10_000)
        assertTrue(store.saved.isEmpty())
        assertEquals(1, oracle.started)
    }

    @Test fun aFreshLessonNotebookIsOpenedByNisabaOnce() = runTest {
        oracle.chunks = listOf("<answer>\nWelcome. Cells first.\n</answer><heard></heard>")
        val rig = rig(context = FakeContextPorts(listOf("Cells are small."), teachGoal = ""))
        rig.session.beginLesson(page); runCurrent()
        assertEquals(1, oracle.started)
        assertEquals(listOf<PageRect?>(null), snapshotter.focuses)              // nothing written: no focus crop
        assertTrue(oracle.requests.single().system.contains("# Teaching"))
        assertTrue(oracle.requests.single().instruction.startsWith(INSTRUCTION_BEGIN_LESSON))
        rig.handDone(); runCurrent()
        assertEquals("(opens the lesson notebook)", store.saved.single().heard)
        rig.session.beginLesson(page); runCurrent()                         // reopening: it has exchanges now
        assertEquals(1, oracle.started)
    }

    @Test fun aLessonRecordsTopicsAndSendsThemBackAsProgress() = runTest {
        val lesson = FakeContextPorts(listOf("Fjords are…"), teachGoal = "")
        oracle.chunks = listOf("<answer>\nCorrect.\n</answer><topic status=\"learned\">Fjords</topic><heard>a fjord is a valley</heard>")
        val rig = rig(context = lesson)
        rig.write("a"); wait(3600); rig.handDone(); runCurrent()
        assertEquals(listOf("Fjords"), lessons.rows.value.map { it.topic })
        assertEquals(1, surface.blocks.size)                                         // the tag is not ink
        rig.write("b"); wait(3600)
        assertTrue(oracle.requests.last().instruction.contains("Lesson progress so far (learned: Fjords)"))
    }

    @Test fun anOrdinaryNotebookKeepsNoLessonProgress() = runTest {
        oracle.chunks = listOf("<answer>\nHi.\n</answer><topic status=\"learned\">X</topic>")
        val rig = rig()
        rig.write("a"); wait(3600); rig.handDone(); runCurrent()
        assertTrue(lessons.rows.value.isEmpty())
    }

    @Test fun comingBackToALessonAfterABreakRecapsBelowTheInk() = runTest {
        oracle.chunks = hello
        val rig = rig(context = FakeContextPorts(listOf("doc"), teachGoal = ""))
        rig.write("a"); wait(3600); rig.handDone(); runCurrent()
        rig.session.beginLesson(page, belowY = 900f); runCurrent()                  // back straight away: nothing
        assertEquals(1, oracle.started)
        advanceTimeBy(5 * 60 * 60 * 1000L); runCurrent()
        rig.session.beginLesson(page, belowY = 900f); runCurrent()
        assertEquals(2, oracle.started)
        assertTrue(oracle.requests.last().instruction.startsWith(INSTRUCTION_RESUME_LESSON))
        assertEquals(listOf<PageRect?>(null), snapshotter.focuses.drop(1))                // no new writing: no focus crop
    }

    @Test fun aLessonWithWritingButNoAnswersYetIsNotOpenedOverIt() = runTest {
        oracle.chunks = hello
        val rig = rig(context = FakeContextPorts(listOf("doc"), teachGoal = ""))
        rig.session.beginLesson(page, belowY = 400f); runCurrent()
        assertEquals(0, oracle.started)
    }

    @Test fun askingAgainResendsTheSameWritingWithThePreviousAnswer() = runTest {
        oracle.chunks = hello
        val rig = rig()
        rig.write("a"); wait(3600); rig.handDone(); runCurrent()
        val first = store.saved.single()
        assertEquals(PageRect(100f, 200f, 300f, 260f), first.focus)
        oracle.chunks = listOf("<answer>\nPut differently.\n</answer>")             // no <heard> this time
        rig.session.askAgain(first, page); runCurrent()
        val req = oracle.requests.last()
        assertTrue(req.instruction.contains("asked again") && req.instruction.contains("Hello there."))
        assertTrue(snapshotter.focuses.last()!!.left < 100f)                          // the old question is in focus
        rig.handDone(); runCurrent()
        assertEquals("say hi", store.saved.last().heard)                              // same question in history
    }

    @Test fun tidyingReadsTheSourcePageAndWritesOnTheNewOne() = runTest {
        oracle.chunks = listOf("<answer>\n# Notes\n- milk\n</answer><heard>whole page</heard>")
        val rig = rig()
        rig.session.tidy(PageId("src"), page); runCurrent()
        assertEquals(listOf<PageRect?>(null), snapshotter.focuses)
        assertEquals(listOf(PageId("src")), snapshotter.pages)
        assertEquals(INSTRUCTION_TIDY, oracle.requests.single().instruction)
        rig.handDone(); runCurrent()
        assertEquals(page, store.saved.single().pageId)
        assertEquals("(asks for a tidy copy of a page of notes)", store.saved.single().heard)
    }

    @Test fun knownFactsRideAlongAndNewOnesAreSavedWithANote() = runTest {
        oracle.chunks = listOf(
            "<answer>\nA nurse who reads? Naturally.\n</answer>",
            "<remember category=\"books\">Loved Dune</remember><heard>I loved Dune</heard>",
        )
        val rig = rig()
        rig.write("a"); wait(3600)
        assertTrue(oracle.requests.single().memoryContext!!.contains("#1 Is a nurse"))
        rig.handDone(); runCurrent()
        assertEquals(listOf("Is a nurse", "Loved Dune"), memory.facts.value.map { it.text })
        assertEquals(store.saved.single().id, memory.facts.value.last().sourceExchangeId)
        assertEquals(listOf("Remembered: Loved Dune"), surface.memoryNotes)
        assertEquals(1, surface.blocks.size) // the tag never becomes ink
    }

    @Test fun aReplyCutOffMidStreamChangesNoMemory() = runTest {
        oracle.chunks = listOf("<answer>\nMoved? Fine.\n</answer><forget id=\"1\"/>", "<remember>Is a doctor</remember><heard>x</heard>")
        oracle.gapMillis = 1_000
        val rig = rig()
        rig.write("a"); wait(3600); wait(1_200)                          // the forget arrived, the remember did not
        rig.session.onPenDown(); runCurrent()
        assertEquals(ExchangeStatus.Interrupted, store.saved.single().status)
        assertEquals(listOf("Is a nurse"), memory.facts.value.map { it.text })
    }

    @Test fun failedRequestsTeachNothing() = runTest {
        oracle.failure = OracleException(DomainError.Auth)
        val rig = rig()
        rig.write("a"); wait(3600)
        assertEquals(1, memory.facts.value.size)
        assertTrue(surface.memoryNotes.isEmpty())
    }

    @Test fun notesStaySilentAndAreRemembered() = runTest {
        oracle.chunks = listOf("<silent/><heard>buy milk</heard>")
        val rig = rig()
        rig.write("a"); wait(3600)
        assertEquals(listOf("ink+", "ink-dry"), surface.log)
        val saved = store.saved.single()
        assertEquals(ExchangeStatus.Silent, saved.status); assertEquals("buy milk", saved.heard)
        assertEquals(ConvState.Idle, rig.session.status.value.state)
    }

    @Test fun writingAgainWithinThePauseSendsOneRequestWithBothStrokes() = runTest {
        oracle.chunks = hello
        val rig = rig()
        rig.write("a"); wait(2000)
        rig.write("b", PageRect(320f, 210f, 500f, 300f))
        wait(3400); assertEquals(0, oracle.started)
        wait(200)
        assertEquals(1, oracle.started)
        val focus = snapshotter.focuses.single()!!
        val covers = focus.left <= 100f && focus.right >= 500f && focus.top <= 200f && focus.bottom >= 300f
        assertTrue(covers, "focus covers both strokes: $focus")
    }

    @Test fun penDownWhileThinkingCancelsTheRequestAndTheResendCarriesBothStrokes() = runTest {
        oracle.chunks = hello; oracle.gapMillis = 5_000
        val rig = rig()
        rig.write("a"); wait(3600)
        assertEquals(1, oracle.started)
        rig.session.onPenDown(); runCurrent()
        assertEquals(1, oracle.cancelled)
        assertEquals("ink-dry", surface.log.last())
        assertTrue(store.saved.isEmpty(), "a cancelled request leaves no exchange")
        oracle.gapMillis = 0
        rig.session.onPenUp(StrokeId("b"), PageRect(320f, 210f, 500f, 300f), page); runCurrent()
        wait(3600)
        assertEquals(2, oracle.started)
        assertEquals(2, snapshotter.focuses.size)
        val f2 = snapshotter.focuses.last()!!
        assertTrue(f2.right >= 500f && f2.left <= 100f, "second focus covers both strokes: $f2")
    }

    @Test fun failureShowsMessageAndRetrySucceeds() = runTest {
        oracle.failure = OracleException(DomainError.Network(NetworkKind.Offline))
        val rig = rig()
        rig.write("a"); wait(3600)
        assertEquals(DomainError.Network(NetworkKind.Offline), surface.failure)
        assertEquals(ExchangeStatus.Failed, store.saved.single().status)
        oracle.failure = null; oracle.chunks = hello
        rig.session.onTapRetry(); runCurrent()
        assertEquals(null, surface.failure)
        assertEquals(2, oracle.started)
        assertEquals(2, surface.blocks.size)
    }

    @Test fun interruptingTheHandKeepsThePartialAnswerAndCancelsTheStream() = runTest {
        oracle.chunks = hello; oracle.gapMillis = 1_000
        val rig = rig()
        rig.write("a"); wait(3600)
        wait(1_200)                                                         // first chunk arrives → first block planned
        assertEquals(1, surface.blocks.size)
        rig.session.onPenDown(); runCurrent()
        assertTrue(surface.log.contains("stop1"))
        assertEquals(1, oracle.cancelled)
        val saved = store.saved.single()
        assertEquals(ExchangeStatus.Interrupted, saved.status)
        assertTrue(saved.answerMarkup.contains("Hello there."))
        rig.session.onTapContinue(); runCurrent()
        assertTrue(surface.log.contains("resume1"))
    }

    @Test fun notListeningWaitsForAskNow() = runTest {
        oracle.chunks = hello
        val rig = rig(listening = false)
        rig.write("a"); wait(10_000)
        assertEquals(0, oracle.started)
        rig.session.onAskNow(); runCurrent()
        assertEquals(1, oracle.started)
    }

    @Test fun requestCarriesEarlierExchangesAsHistory() = runTest {
        oracle.chunks = hello
        val rig = rig()
        rig.write("a"); wait(3600); rig.handDone(); runCurrent()
        rig.write("b"); wait(3600)
        val second = oracle.requests.last()
        assertEquals(listOf<Turn>(Turn.User("say hi")), second.history.take(1))
        assertEquals(2, second.history.size)
    }
}
