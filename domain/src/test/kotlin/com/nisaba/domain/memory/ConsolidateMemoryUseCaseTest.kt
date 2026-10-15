package com.nisaba.domain.memory

import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.notebook.FakeClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsolidateMemoryUseCaseTest {
    private class ReplyOracle(private val reply: String) : Oracle {
        val asked = mutableListOf<OracleRequest>()
        override fun ask(request: OracleRequest): Flow<OracleEvent> { asked += request; return flowOf(OracleEvent.Delta(reply)) }
    }

    private fun facts(n: Int) = (1..n.toLong()).map { fact(it, "fact number $it") }

    @Test fun pastTheThresholdDuplicatesAreMergedInOneGo() = runTest {
        val store = FakeMemoryStore(facts(150))
        val oracle = ReplyOracle("<forget id=\"1\"/><forget id=\"2\"/><remember category=\"me\">Facts one and two, merged</remember>")
        val plan = ConsolidateMemoryUseCase(store, oracle, FakeClock())("deepseek-flash")!!
        assertEquals(2, plan.forget.size)
        assertEquals(149, store.facts.value.size)
        assertTrue(store.facts.value.any { it.text == "Facts one and two, merged" })
        assertTrue(oracle.asked.single().instruction.lines().first().startsWith("#1 [")) // every fact, by id
        assertTrue(oracle.asked.single().images.isEmpty())
    }

    @Test fun belowTheThresholdOrBetweenStepsNothingIsAsked() = runTest {
        val oracle = ReplyOracle("")
        assertNull(ConsolidateMemoryUseCase(FakeMemoryStore(facts(100)), oracle, FakeClock())("m"))
        assertNull(ConsolidateMemoryUseCase(FakeMemoryStore(facts(151)), oracle, FakeClock())("m"))
        assertTrue(oracle.asked.isEmpty())
    }

    @Test fun aReplyThatWouldWipeMostOfMemoryIsIgnored() = runTest {
        val store = FakeMemoryStore(facts(150))
        val wipe = (1..100).joinToString("") { "<forget id=\"$it\"/>" }
        assertNull(ConsolidateMemoryUseCase(store, ReplyOracle(wipe), FakeClock())("m"))
        assertEquals(150, store.facts.value.size)
    }
}
