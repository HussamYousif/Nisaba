package com.nisaba.domain.persona

import com.nisaba.domain.shared.IdGen

/** Keeps a drafted voice as one of their own; it can answer in any notebook from now on. */
class KeepVoiceUseCase(private val store: OwnVoiceStore, private val ids: IdGen) {
    suspend operator fun invoke(draft: VoiceDraft, existing: Int): Persona =
        ownVoice(draft, PersonaRegistry.OWN_PREFIX + ids.next(), existing).also { store.save(it) }
}
