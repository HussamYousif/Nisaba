package com.nisaba.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nisaba.domain.conversation.failureMessage
import com.nisaba.domain.persona.DraftVoiceUseCase
import com.nisaba.domain.persona.ForgetVoiceUseCase
import com.nisaba.domain.persona.KeepVoiceUseCase
import com.nisaba.domain.persona.Voices
import com.nisaba.domain.shared.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Drafts, keeps and forgets the person's own voices; one per activity, shared by every voice picker. */
class OwnVoiceViewModel(
    private val draftVoice: DraftVoiceUseCase,
    private val keepVoice: KeepVoiceUseCase,
    private val forgetVoice: ForgetVoiceUseCase,
    private val voices: StateFlow<Voices>,
    private val model: suspend () -> String,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutableState = MutableStateFlow(OwnVoiceUiState())
    val state: StateFlow<OwnVoiceUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun open() = mutableState.update { OwnVoiceUiState(open = true) }
    fun describe(text: String) = mutableState.update { it.copy(description = text, error = null) }

    fun close() { job?.cancel(); mutableState.update { OwnVoiceUiState(kept = it.kept) } }

    /** Asks the model for a voice like the description (again: another take on it). */
    fun draft() {
        val text = mutableState.value.description
        if (text.isBlank() || mutableState.value.drafting) return
        mutableState.update { it.copy(drafting = true, error = null) }
        job = scope.launch {
            when (val out = draftVoice(text, model())) {
                is Outcome.Ok -> mutableState.update { it.copy(drafting = false, draft = out.value) }
                is Outcome.Err -> mutableState.update { it.copy(drafting = false, error = failureMessage(out.error)) }
            }
        }
    }

    /** Keeps the draft; the picker that opened this selects it ([OwnVoiceUiState.kept]). */
    fun keep() {
        val d = mutableState.value.draft ?: return
        job = scope.launch {
            val voice = keepVoice(d, voices.value.own.size)
            mutableState.update { OwnVoiceUiState(kept = voice.id) }
        }
    }

    /** The picker has selected the kept voice. */
    fun consumeKept() = mutableState.update { it.copy(kept = null) }

    fun forget(id: String) { scope.launch { forgetVoice(id) } }
}
