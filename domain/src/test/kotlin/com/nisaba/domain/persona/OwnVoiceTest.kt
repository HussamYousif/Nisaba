package com.nisaba.domain.persona

import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleException
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnVoiceTest {
    private val reply = """
        <name>Captain Ledger</name>
        <tagline>A grumpy pirate who loves sums.</tagline>
        <voice>You are Nisaba, the spirit of this notebook, speaking as a grumpy old pirate captain who secretly adores arithmetic and costs everything in doubloons.</voice>
        <sample>Arr. Show me yer sums.</sample>
        <hint>Scribble, then pause.</hint>
        <oops>Blast.</oops>
        <length>brief</length>
    """.trimIndent()

    private class ReplyOracle(private val reply: String) : Oracle {
        val asked = mutableListOf<OracleRequest>()
        override fun ask(request: OracleRequest): Flow<OracleEvent> { asked += request; return flowOf(OracleEvent.Delta(reply)) }
    }

    private class FakeOwnVoices : OwnVoiceStore {
        val voices = MutableStateFlow(emptyList<Persona>())
        override fun observe() = voices
        override suspend fun save(voice: Persona) { voices.value = voices.value + voice }
        override suspend fun delete(id: String) { voices.value = voices.value.filterNot { it.id == id } }
    }

    @Test fun aDraftReadsEveryTag() {
        val d = parseVoiceDraft(reply, "  a grumpy pirate who loves maths ")!!
        assertEquals("Captain Ledger", d.name)
        assertEquals("A grumpy pirate who loves sums.", d.tagline)
        assertTrue(d.voice.startsWith("You are Nisaba"))
        assertEquals("Arr. Show me yer sums.", d.sample)
        assertEquals("Scribble, then pause.", d.hint)
        assertEquals("Blast.", d.oops)
        assertEquals(35, d.words)
        assertEquals("a grumpy pirate who loves maths", d.description)
    }

    @Test fun aDraftWithoutANameOrAVoiceIsNoDraft() {
        assertNull(parseVoiceDraft("<voice>You are Nisaba, the spirit of this notebook, a pirate.</voice>", "x"))
        assertNull(parseVoiceDraft("<name>Pirate</name><voice>Too short.</voice>", "x"))
        assertNull(parseVoiceDraft("Sure! Here is a voice for you.", "x"))
    }

    @Test fun missingLinesGetSensibleDefaults() {
        val reply = "<name>Pirate</name><voice>You are Nisaba, the spirit of this notebook, as a pirate of the seven seas.</voice>"
        val d = parseVoiceDraft(reply, "x")!!
        assertEquals("Pirate", d.sample)
        assertEquals("Write, then pause.", d.hint)
        assertEquals("", d.oops)
        assertEquals(50, d.words)
    }

    @Test fun draftingAsksTheModelOnceAndKeepsTheDescription() = runTest {
        val oracle = ReplyOracle(reply)
        val out = DraftVoiceUseCase(oracle)("a grumpy pirate who loves maths", "deepseek-flash")
        assertIs<Outcome.Ok<VoiceDraft>>(out)
        assertEquals("a grumpy pirate who loves maths", oracle.asked.single().instruction)
        assertTrue(oracle.asked.single().system.contains("<name>"))
        assertIs<Outcome.Err>(DraftVoiceUseCase(oracle)("   ", "deepseek-flash")) // nothing described: nothing asked
        assertEquals(1, oracle.asked.size)
    }

    @Test fun aFailedDraftSaysWhy() = runTest {
        val offline = object : Oracle {
            override fun ask(request: OracleRequest): Flow<OracleEvent> =
                flow { throw OracleException(DomainError.Network(NetworkKind.Offline)) }
        }
        val out = DraftVoiceUseCase(offline)("a pirate", "m")
        assertEquals(DomainError.Network(NetworkKind.Offline), (out as Outcome.Err).error)
        assertIs<Outcome.Err>(DraftVoiceUseCase(ReplyOracle("I can't help with that."))("a pirate", "m"))
    }

    @Test fun keptVoicesGetOwnIdsAndTheirOwnInksAndCanBeForgotten() = runTest {
        val store = FakeOwnVoices()
        val draft = parseVoiceDraft(reply, "pirate")!!
        val first = KeepVoiceUseCase(store, FakeIdGen())(draft, existing = 0)
        val second = KeepVoiceUseCase(store, FakeIdGen("b"))(draft, existing = 1)
        assertTrue(PersonaRegistry.isOwn(first.id) && first.id != second.id)
        assertTrue(first.hand.ink != second.hand.ink)
        assertEquals(OWN_TEMPERATURE, first.temperature)
        val voices = Voices(store.voices.value)
        assertEquals("Captain Ledger", voices.of(first.id).name)
        assertEquals(PersonaRegistry.all.size + 2, voices.all.size)
        ForgetVoiceUseCase(store)(first.id)
        ForgetVoiceUseCase(store)("teacher") // built-in voices can't be forgotten
        assertEquals(PersonaRegistry.default, Voices(store.voices.value).of(first.id)) // its notebooks: the default voice
        assertEquals(listOf(second.id), store.voices.value.map { it.id })
    }

    @Test fun storedIdsKeepOwnVoicesAndNormalizeTheRest() {
        assertEquals("own-1", PersonaRegistry.normalize("own-1"))
        assertEquals("teacher", PersonaRegistry.normalize("teacher"))
        assertEquals(PersonaRegistry.DEFAULT_ID, PersonaRegistry.normalize("gone"))
    }

    @Test fun eachVoiceWritesInItsOwnHandUnlessTurnedOff() {
        val explorer = PersonaRegistry.of("explorer")
        val own = explorer.handFor(Settings())
        assertEquals(explorer.hand.ink, own.ink)
        assertEquals(HandSpeed.Swift, own.speed) // the default pace gives way to the voice's
        assertEquals(HandSpeed.Slow, explorer.handFor(Settings(handSpeed = HandSpeed.Slow)).speed) // a chosen pace wins
        val plain = explorer.handFor(Settings(voiceHands = false))
        assertNull(plain.ink); assertNull(plain.slantDeg); assertEquals(1f, plain.tidiness)
        assertEquals(HandSpeed.Natural, plain.speed)
    }

    @Test fun everyBuiltInVoiceHasAHintAndADistinctInk() {
        for (p in PersonaRegistry.all) assertTrue(p.hint.endsWith("pause.") || p.hint.contains("pause"), p.id)
        val inks = PersonaRegistry.all.mapNotNull { it.hand.ink }
        assertEquals(inks.size, inks.toSet().size)
    }

    @Test fun failuresOpenInTheVoice() {
        val offline = DomainError.Network(NetworkKind.Offline)
        assertEquals("The ink wouldn't flow: no connection", failureMessage(offline))
        assertEquals("Tch. The ink wouldn't flow: no connection", failureMessage(offline, PersonaRegistry.default.oops))
        assertEquals("The ink wouldn't flow: no connection", failureMessage(offline, " "))
    }
}
