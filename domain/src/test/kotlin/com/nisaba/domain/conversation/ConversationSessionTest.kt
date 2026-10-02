package com.nisaba.domain.conversation

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.notebook.FakeIdGen
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

    private class Rig(val session: ConversationSession, val scope: TestScope)

    private fun TestScope.rig(listening: Boolean = true): Rig {
        val deps = ConversationDeps(
            NotebookId("n"), oracle, snapshotter, store, surface, FakeContextPorts(), VirtualClock(testScheduler), FakeIdGen("ex"),
        )
        return Rig(ConversationSession(deps, backgroundScope, Settings(), listening), this)
    }

    private fun Rig.write(id: String, r: PageRect = PageRect(100f, 200f, 300f, 260f)) {
        session.onPenDown(); scope.runCurrent()
        session.onPenUp(StrokeId(id), r, page); scope.runCurrent()
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
        rig.session.onScribeFinished(); runCurrent()
        assertEquals(ConvState.Idle, rig.session.status.value.state)
        val saved = store.saved.single()
        assertEquals(ExchangeStatus.Complete, saved.status)
        assertEquals("say hi", saved.heard)
        assertTrue(saved.answerMarkup.contains("Hello there."))
        assertEquals(1500, saved.inputTokens)
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
        rig.write("a"); wait(3600); rig.session.onScribeFinished(); runCurrent()
        rig.write("b"); wait(3600)
        val second = oracle.requests.last()
        assertEquals(listOf<Turn>(Turn.User("say hi")), second.history.take(1))
        assertEquals(2, second.history.size)
    }
}
