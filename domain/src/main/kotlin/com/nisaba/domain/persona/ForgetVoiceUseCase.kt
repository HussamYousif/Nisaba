package com.nisaba.domain.persona

/** Forgets a voice of their own; notebooks that answered in it answer in the default voice from then on. */
class ForgetVoiceUseCase(private val store: OwnVoiceStore) {
    suspend operator fun invoke(id: String) { if (PersonaRegistry.isOwn(id)) store.delete(id) }
}
