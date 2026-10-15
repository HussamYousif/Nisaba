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
)
