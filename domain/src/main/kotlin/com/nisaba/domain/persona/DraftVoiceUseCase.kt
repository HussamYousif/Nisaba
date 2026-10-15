package com.nisaba.domain.persona

import com.nisaba.domain.conversation.Oracle
import com.nisaba.domain.conversation.OracleEvent
import com.nisaba.domain.conversation.OracleException
import com.nisaba.domain.conversation.OracleOptions
import com.nisaba.domain.conversation.OracleRequest
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.Outcome

/** "Someone like…": the model drafts a voice from the person's description (one cheap text-only request). */
class DraftVoiceUseCase(private val oracle: Oracle) {
    suspend operator fun invoke(description: String, model: String): Outcome<VoiceDraft> {
        val text = description.trim().take(MAX_DESCRIPTION)
        if (text.isEmpty()) return Outcome.Err(DomainError.Invalid(EMPTY))
        val request = OracleRequest(
            system = DRAFT_PROMPT, documentContext = null, history = emptyList(), instruction = text, images = emptyList(),
            options = OracleOptions(model, thinking = false, maxTokens = MAX_TOKENS, temperature = TEMPERATURE),
        )
        val reply = StringBuilder()
        try {
            oracle.ask(request).collect { if (it is OracleEvent.Delta) reply.append(it.text) }
        } catch (e: OracleException) {
            return Outcome.Err(e.error)
        }
        return parseVoiceDraft(reply.toString(), text)?.let { Outcome.Ok(it) } ?: Outcome.Err(DomainError.Invalid(EMPTY))
    }

    companion object {
        const val EMPTY = "empty answer"
        private const val MAX_DESCRIPTION = 600
        private const val MAX_TOKENS = 700
        private const val TEMPERATURE = 0.9
        val DRAFT_PROMPT = """
You design voices for Nisaba, a handwritten notebook whose spirit answers the person in handwriting, in the margin of what they write. The person describes who they would like to hear from: a character, a kind of person, a mood. Write that voice.
Reply only with these tags, each exactly once, in this order:
<name>the voice's name, 1 to 4 words (the character's own name if they named one)</name>
<tagline>what it is like, at most 10 words</tagline>
<voice>the persona part of a system prompt, 60 to 140 words, one paragraph, in English, beginning "You are Nisaba, the spirit of this notebook, " and saying how this voice speaks: temperament, turns of phrase, how it helps when they study, write or just chat. It is always genuinely helpful and correct; playful or prickly at most, never cruel, explicit or hateful.</voice>
<sample>the first thing this voice would say to them, at most 10 words</sample>
<hint>an invitation to write on a blank page, in this voice, at most 8 words, ending with "then pause."</hint>
<oops>what this voice exclaims when something goes wrong, 1 to 3 words</oops>
<length>brief, normal or long: how long its answers usually are</length>
Write the name, tagline, sample, hint and oops in the language of the description.
        """.trimIndent()
    }
}
