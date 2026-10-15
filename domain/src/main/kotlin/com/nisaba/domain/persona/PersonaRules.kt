package com.nisaba.domain.persona

import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.settings.Settings

/**
 * How this voice writes, given the person's settings. With "voices write in their own hand" off every voice writes
 * plainly. A pace picked in Settings wins: the voice's own pace only replaces the default one.
 */
fun Persona.handFor(settings: Settings): VoiceHand {
    if (!settings.voiceHands) return VoiceHand(speed = settings.handSpeed)
    val pace = if (settings.handSpeed == HandSpeed.Natural) hand.speed ?: settings.handSpeed else settings.handSpeed
    return hand.copy(speed = pace)
}

/** Answers from a voice of their own run warm, but well below where the reply markup starts to slip. */
const val OWN_TEMPERATURE = 0.75

/** The kept voice for [draft]: id [id], and the ink after the [existing] voices of their own. */
fun ownVoice(draft: VoiceDraft, id: String, existing: Int): Persona = Persona(
    id = id, name = draft.name, tagline = draft.tagline, sample = draft.sample, words = draft.words,
    temperature = OWN_TEMPERATURE, voice = draft.voice,
    hand = VoiceHand(ink = PersonaRegistry.OWN_INKS[existing.mod(PersonaRegistry.OWN_INKS.size)]),
    hint = draft.hint, oops = draft.oops,
)
