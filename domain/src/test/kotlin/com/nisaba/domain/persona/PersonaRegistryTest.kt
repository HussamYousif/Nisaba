package com.nisaba.domain.persona

import com.nisaba.domain.conversation.systemPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonaRegistryTest {
    @Test fun idsAreUniqueAndTheDefaultIsGrimoireNoir() {
        val ids = PersonaRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("book", PersonaRegistry.default.id)
        assertTrue(PersonaRegistry.default.voice.contains("Grimoire Noir")) // notebooks from before voices keep the default
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
        assertTrue(systemPrompt(PersonaRegistry.of("yukino")).startsWith("You are Yukino from"))
        assertTrue(systemPrompt(PersonaRegistry.of("zagreus")).startsWith("You are Zagreus"))
        assertTrue(systemPrompt(PersonaRegistry.of("teacher")).startsWith("You are Gandalf"))
        assertTrue(PersonaRegistry.all.none { "Yukinoshita" in it.name + it.voice }) // just Yukino
    }

    @Test fun theFlirtIsNowYukino() {
        assertTrue(PersonaRegistry.all.none { it.id == "flirt" })
        assertEquals("yukino", PersonaRegistry.of("flirt").id) // notebooks made with the flirt keep talking
        assertEquals("yukino", PersonaRegistry.normalize("flirt"))
        assertEquals("Gandalf", PersonaRegistry.of("teacher").name) // same id: the wise teacher's notebooks are his
    }
}
