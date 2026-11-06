package com.nisaba.notebook

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.conversation.Purpose
import com.nisaba.domain.conversation.Utterance
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.GlyphSetParser
import com.nisaba.scribe.layout.Occupancy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerScribeSessionTest {
    private val hand = GlyphSetParser.parse(javaClass.classLoader!!.getResourceAsStream("hands/casual.json")!!.reader().readText())
    private val played = mutableListOf<ScribeCommand.Play>()
    private var settings = Settings()
    private val session = AnswerScribeSession(
        1, PageId("question-page"), { hand }, { settings }, { PageSize.PORTRAIT }, { emptyList() }, { null },
        { if (it is ScribeCommand.Play) played += it }, {},
        planScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), // plan at once, in order
    )
    private val hello = Block.Paragraph(listOf(Inline.Text("Hello there.")))

    @Test fun anAnswerIsWrittenOnThePageOfItsQuestion() {
        session.showInkwell(Utterance(PageId("question-page"), emptySet(), PageRect(200f, 300f, 600f, 360f)))
        session.planBlock(1, hello, ExchangeId("e1"))
        // after a spill the session carries on on the next page; a follow-up asked there is answered there
        session.showInkwell(Utterance(PageId("spill-page"), emptySet(), PageRect(200f, 900f, 600f, 960f)))
        session.planBlock(2, hello, ExchangeId("e2"))
        assertEquals(listOf(PageId("question-page"), PageId("spill-page")), played.map { it.pageId })
        assertTrue(played.all { p -> p.strokes.isNotEmpty() && p.strokes.all { it.page == 0 } })
    }

    @Test fun eachVoiceWritesInItsOwnInkAndHand() {
        session.showInkwell(Utterance(PageId("question-page"), emptySet(), PageRect(200f, 300f, 600f, 360f)))
        session.planBlock(1, hello, ExchangeId("e1"), personaId = "explorer")
        session.planBlock(2, hello, ExchangeId("e2"), personaId = "book") // Grimoire Noir: the answer ink in Settings
        settings = Settings(voiceHands = false)
        session.planBlock(3, hello, ExchangeId("e3"), personaId = "explorer")
        assertEquals(listOf(PersonaRegistry.of("explorer").hand.ink, null, null), played.map { it.ref.ink })
        // The idea explorer leans further and writes faster than the plain hand
        assertTrue(played[0].strokes.last().points.ts.last() < played[2].strokes.last().points.ts.last())
    }

    @Test fun checkedWorkGetsATickOrACrossBesideEachLineThenTheClosingLineBelow() {
        val line1 = PageRect(200f, 300f, 700f, 350f)
        val line2 = PageRect(200f, 420f, 650f, 470f)
        val all = PageRect(200f, 300f, 700f, 470f)
        session.showInkwell(Utterance(PageId("question-page"), emptySet(), all, Purpose.Check(listOf(line1, line2))))
        session.planBlock(1, Block.Mark(1, true, ""), ExchangeId("e1"))
        session.planBlock(1, Block.Mark(2, false, "2x = 4"), ExchangeId("e1"))
        session.planBlock(1, hello, ExchangeId("e1"))
        val (tick, cross, closing) = played
        fun xs(p: ScribeCommand.Play) = p.strokes.flatMap { it.points.xs.toList() }
        fun ys(p: ScribeCommand.Play) = p.strokes.flatMap { it.points.ys.toList() }
        assertTrue(xs(tick).min() > line1.right && ys(tick).max() < line1.bottom + 30f && ys(tick).min() > line1.top - 30f)
        assertTrue(xs(cross).min() > line2.right && ys(cross).min() > line1.bottom)
        assertTrue(ys(closing).min() > line2.bottom) // the closing sentence under the work
        assertEquals(listOf(0xFF1F7A33.toInt(), 0xFFA3271B.toInt(), null), played.map { it.ref.ink }) // green tick, red cross
        val end = { p: ScribeCommand.Play -> p.strokes.maxOf { it.points.ts.last() } }
        assertTrue(cross.strokes.minOf { it.points.ts.first() } >= end(tick)) // one clock: the cross after the tick
    }

    @Test fun onAPrintedPageTheAnswerGoesInTheMarginBesideTheQuestion() {
        val page = PageSize.PORTRAIT
        val printed = Occupancy.of(page, (0 until 30).map { PageRect(200f, 200f + it * 60f, 1500f, 230f + it * 60f) }, pad = 0f)
        val plays = mutableListOf<ScribeCommand.Play>()
        val pdf = AnswerScribeSession(
            2, PageId("pdf-page"), { hand }, { Settings() }, { page }, { emptyList() }, { printed },
            { if (it is ScribeCommand.Play) plays += it }, {},
            planScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        pdf.showInkwell(Utterance(PageId("pdf-page"), emptySet(), PageRect(1560f, 600f, 1800f, 650f)))
        pdf.planBlock(1, hello, ExchangeId("e1"))
        val xs = plays.single().strokes.flatMap { it.points.xs.toList() }
        assertTrue(xs.min() >= 1500f, "written in the right margin, not over the text: ${xs.min()}")
    }
}
