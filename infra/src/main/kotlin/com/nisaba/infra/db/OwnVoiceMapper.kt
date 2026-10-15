package com.nisaba.infra.db

import com.nisaba.domain.persona.Persona
import com.nisaba.domain.persona.VoiceHand

fun Persona.toDto(createdAt: Long) = OwnVoiceDto(id, name, tagline, sample, voice, words, temperature, hand.ink, hint, oops, createdAt)

fun OwnVoiceDto.toModel() = Persona(
    id = id, name = name, tagline = tagline, sample = sample, words = words, temperature = temperature, voice = voice,
    hand = VoiceHand(ink = ink), hint = hint, oops = oops,
)
