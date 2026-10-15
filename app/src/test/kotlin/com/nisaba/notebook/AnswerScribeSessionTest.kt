package com.nisaba.notebook

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.conversation.Utterance
import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.GlyphSetParser
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
        session.planBlock(1, hello, ExchangeId("e1"), personaId = "zagreus")
        session.planBlock(2, hello, ExchangeId("e2"), personaId = "book") // the sassy book: the answer ink in Settings
        settings = Settings(voiceHands = false)
        session.planBlock(3, hello, ExchangeId("e3"), personaId = "zagreus")
        assertEquals(listOf(PersonaRegistry.of("zagreus").hand.ink, null, null), played.map { it.ref.ink })
        // Zagreus leans further and writes faster than the plain hand
        assertTrue(played[0].strokes.last().points.ts.last() < played[2].strokes.last().points.ts.last())
    }
}
