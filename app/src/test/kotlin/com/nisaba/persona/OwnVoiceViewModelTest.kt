package com.nisaba.persona

import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.notebook.FakeIdGen
import com.nisaba.domain.persona.DraftVoiceUseCase
import com.nisaba.domain.persona.ForgetVoiceUseCase
import com.nisaba.domain.persona.KeepVoiceUseCase
import com.nisaba.domain.persona.OwnVoiceStore
import com.nisaba.domain.persona.Persona
import com.nisaba.domain.persona.Voices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnVoiceViewModelTest {
    private class Store : OwnVoiceStore {
        val voices = MutableStateFlow(emptyList<Persona>())
        override fun observe() = voices
        override suspend fun save(voice: Persona) { voices.value = voices.value + voice }
        override suspend fun delete(id: String) { voices.value = voices.value.filterNot { it.id == id } }
    }

    private class ReplyOracle(var reply: String) : Oracle {
        override fun ask(request: OracleRequest): Flow<OracleEvent> = flowOf(OracleEvent.Delta(reply))
    }

    private val reply = "<name>Captain Ledger</name>" +
        "<voice>You are Nisaba, the spirit of this notebook, a pirate who loves sums.</voice><sample>Arr. Show me yer sums.</sample>"

    @Test fun aDescribedVoiceIsDraftedKeptAndHandedToThePicker() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = Store()
        val oracle = ReplyOracle("Sorry, I can't.")
        val vm = OwnVoiceViewModel(
            DraftVoiceUseCase(oracle), KeepVoiceUseCase(store, FakeIdGen()), ForgetVoiceUseCase(store),
            MutableStateFlow(Voices()), { "deepseek-flash" }, scope,
        )
        vm.open(); vm.describe("a grumpy pirate who loves maths"); vm.draft(); scope.advanceUntilIdle()
        assertEquals("Nothing came back", vm.state.value.error) // an unusable reply: said, and they can try again
        oracle.reply = reply
        vm.draft(); scope.advanceUntilIdle()
        assertEquals("Captain Ledger", vm.state.value.draft?.name)
        vm.keep(); scope.advanceUntilIdle()
        val kept = assertNotNull(vm.state.value.kept)
        assertTrue(!vm.state.value.open && kept == store.voices.value.single().id)
        vm.consumeKept()
        assertNull(vm.state.value.kept)
        vm.forget(kept); scope.advanceUntilIdle()
        assertTrue(store.voices.value.isEmpty())
    }
}
