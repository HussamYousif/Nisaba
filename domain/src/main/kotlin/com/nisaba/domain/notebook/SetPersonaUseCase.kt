package com.nisaba.domain.notebook

import com.nisaba.domain.persona.PersonaRegistry
import com.nisaba.domain.shared.NotebookId

class SetPersonaUseCase(private val store: NotebookStore) {
    /** From the next reply on, Nisaba answers in notebook [id] in the voice [personaId] (unknown → the default voice). */
    suspend operator fun invoke(id: NotebookId, personaId: String) = store.setPersona(id, PersonaRegistry.normalize(personaId))
}
