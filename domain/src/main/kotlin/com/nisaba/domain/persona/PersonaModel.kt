package com.nisaba.domain.persona

/**
 * A voice Nisaba answers in, picked per notebook (DESIGN D17). [voice] is the persona part of the system prompt; the
 * reply contract around it is the same for every voice. [words] = the default answer length; [temperature] = how
 * adventurous the wording may be. [sample] is a line in this voice that the hand writes in the picker.
 */
data class Persona(
    val id: String,
    val name: String,
    val tagline: String,
    val sample: String,
    val words: Int,
    val temperature: Double,
    val voice: String,
    /** How this voice writes on the page (when voices write in their own hand: Settings → The hand). */
    val hand: VoiceHand = VoiceHand(),
    /** The first-page hint, in this voice. */
    val hint: String = "Write, then pause.",
    /** Said before a failure line ("Bah." + "the ink wouldn't flow"); empty = nothing. */
    val oops: String = "",
    /** A fan homage to a character someone else owns: offered only where that is fine (not in store builds). */
    val character: Boolean = false,
)

/**
 * A voice's handwriting: its ink (ARGB; null = the answer ink in Settings), its slant in degrees (null = the hand's
 * own), how tidy it is (1 = the usual wobble, below 1 neater, above looser), and its pace (null = Settings).
 */
data class VoiceHand(
    val ink: Int? = null,
    val slantDeg: Float? = null,
    val tidiness: Float = 1f,
    val speed: com.nisaba.domain.settings.HandSpeed? = null,
)

/**
 * Every voice there is: the built-in ones and the person's own ([own], drafted from a description, DESIGN §5.3a).
 * [builtIn] is what the picker offers; [of] still finds any built-in voice, so a notebook never loses its voice.
 */
data class Voices(val own: List<Persona> = emptyList(), val builtIn: List<Persona> = PersonaRegistry.all) {
    val all: List<Persona> get() = builtIn + own

    /** The voice [id] names; an unknown one (an own voice since forgotten) is the default voice. */
    fun of(id: String?): Persona = own.firstOrNull { it.id == id } ?: PersonaRegistry.of(id)

    /** Room for one more voice of their own (the picker stays one screen, no scrolling). */
    val canAddOwn: Boolean get() = own.size < PersonaRegistry.MAX_OWN
}

/** A voice the model drafted from the person's [description], not kept yet. [words] = its usual answer length. */
data class VoiceDraft(
    val description: String,
    val name: String,
    val tagline: String,
    val voice: String,
    val sample: String,
    val hint: String,
    val oops: String,
    val words: Int,
)
