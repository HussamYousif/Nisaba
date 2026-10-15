package com.nisaba.domain.persona

import com.nisaba.domain.conversation.systemPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonaRegistryTest {
    @Test fun idsAreUniqueAndTheDefaultIsTheSassyBook() {
        val ids = PersonaRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("book", PersonaRegistry.default.id)
        assertTrue(PersonaRegistry.default.voice.contains("Grimoire Weiss")) // notebooks from before voices keep theirs
    }

    @Test fun anUnknownOrMissingIdFallsBackToTheDefault() {
        assertEquals(PersonaRegistry.default, PersonaRegistry.of(null))
        assertEquals(PersonaRegistry.default, PersonaRegistry.of("removed-voice"))
        assertEquals("yukino", PersonaRegistry.of("yukino").id)
    }

    @Test fun everyVoiceIsComplete() {
        for (p in PersonaRegistry.all) {
            assertTrue(p.name.isNotBlank() && p.tagline.isNotBlank() && p.sample.isNotBlank(), p.id)
            assertTrue(p.voice.startsWith("You are "), p.id)
            assertTrue(p.words in 30..120, p.id)
            assertTrue(p.temperature in 0.3..0.9, p.id) // hotter and the reply markup starts to slip
            assertTrue(!p.voice.contains('\n') && !p.voice.contains('$'), p.id) // one paragraph; no stray math
        }
    }

    @Test fun theCharactersAreThemselves() {
        assertTrue(systemPrompt(PersonaRegistry.of("yukino")).startsWith("You are Yukinoshita Yukino"))
        assertTrue(systemPrompt(PersonaRegistry.of("zagreus")).startsWith("You are Zagreus"))
    }
}
