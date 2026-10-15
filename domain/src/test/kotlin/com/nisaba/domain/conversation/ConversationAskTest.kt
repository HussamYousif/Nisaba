package com.nisaba.domain.conversation

import com.nisaba.domain.answer.Block
import com.nisaba.domain.ink.tallStroke
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** New ways to ask: check my work, redraw a sketch, ask about a lassoed area (M15). */
class ConversationAskTest {
    private val page = PageId("p")
    private val oracle = FakeOracle()
    private val snapshotter = FakeSnapshotter()
    private val store = FakeExchangeStore()
    private val surface = RecordingSurface()

    private fun TestScope.session(listening: Boolean = true) = ConversationSession(
        ConversationDeps(
            NotebookId("n"), oracle, snapshotter, store, surface, FakeContextPorts(), VirtualClock(testScheduler), FakeIdGen("ex"),
        ),
        backgroundScope, Settings(), listening,
    )

    private val work = listOf(
        tallStroke("a", 100f, 100f, 40f), tallStroke("b", 160f, 104f, 36f), // 2x + 3 = 7
        tallStroke("c", 100f, 220f, 40f), // 2x = 10
    )

    @Test fun checkingNumbersTheLinesAndWritesAMarkBesideEach() = runTest {
        oracle.chunks = listOf(
            "<answer>\n<mark line=\"1\" ok=\"true\"/>\n<mark line=\"2\" ok=\"false\">2x = 4</mark>\nOne slip.\n</answer>",
            "<heard>2x+3=7 / 2x=10</heard>",
        )
        val s = session(listening = false) // asked for explicitly: goes even while muted
        assertTrue(s.check(page, work)); runCurrent()
        assertEquals(listOf("1", "2"), snapshotter.labels.single().map { it.text })
        val req = oracle.requests.single()
        assertTrue(req.instruction.startsWith(INSTRUCTION_CHECK))
        assertTrue(req.options.thinking) // worth thinking it through
        assertEquals(TIDY_TEMPERATURE, req.options.temperature)
        assertEquals(listOf(Block.Mark(1, true, ""), Block.Mark(2, false, "2x = 4")), surface.blocks.filterIsInstance<Block.Mark>())
        assertFalse(s.check(page, emptyList())) // nothing of theirs: nothing asked
    }

    @Test fun aLassoTakesOverWritingThatWasOnlyWaitingForItsPause() = runTest {
        oracle.chunks = listOf("<answer>\nIt is a fjord.\n</answer><heard>fjord?</heard>")
        val s = session()
        s.onPenDown(); runCurrent()
        s.onPenUp(StrokeId("w"), PageRect(100f, 100f, 300f, 140f), page); runCurrent()
        assertIs<ConvState.Pausing>(s.status.value.state)
        s.askAbout(page, setOf(StrokeId("w")), PageRect(90f, 90f, 320f, 150f)); runCurrent()
        assertEquals(1, oracle.requests.size)
        assertTrue(oracle.requests.single().instruction.startsWith(INSTRUCTION_ABOUT))
    }

    @Test fun aSketchIsRedrawnWithoutTheVoice() = runTest {
        val svg = "<svg viewBox=\"0 0 1000 1000\"><rect x=\"1\" y=\"1\" width=\"9\" height=\"9\"/></svg>"
        oracle.chunks = listOf("<answer>\n```diagram\n$svg\n```\n</answer><heard>box</heard>")
        val s = session()
        s.redraw(page, work); runCurrent()
        val req = oracle.requests.single()
        assertTrue(req.instruction.startsWith(INSTRUCTION_REDRAW))
        assertEquals(TIDY_TEMPERATURE, req.options.temperature)
        assertIs<Block.Diagram>(surface.blocks.single())
    }

    @Test fun anUnderlineTakesInTheLineAboveIt() {
        val underline = PageRect(100f, 300f, 500f, 306f)
        assertTrue(focusArea(underline).top < 300f - 100f) // reaches up to the underlined words
        val word = PageRect(100f, 300f, 300f, 360f)
        assertEquals(PageRect(90f, 290f, 310f, 370f), focusArea(word))
    }

    @Test fun onAPrintedPageAnswersAreGlossesAndTheContractKnowsCircles() {
        val snap = PageSnapshot(byteArrayOf(1), byteArrayOf(2))
        val onDoc = buildRequest(ContextInput(listOf("p1", "p2"), 1, emptyList(), snap, Settings()))
        assertTrue(onDoc.instruction.contains(INSTRUCTION_GLOSS))
        assertTrue(onDoc.instruction.contains("at most $GLOSS_WORDS words"))
        val blank = buildRequest(ContextInput(emptyList(), null, emptyList(), snap, Settings()))
        assertFalse(blank.instruction.contains(INSTRUCTION_GLOSS))
        assertTrue(blank.system.contains("A circle, underline, bracket or arrow"))
    }
}
