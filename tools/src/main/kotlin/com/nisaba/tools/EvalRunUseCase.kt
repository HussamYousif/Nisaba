package com.nisaba.tools

import com.nisaba.domain.conversation.ContextInput
import com.nisaba.domain.conversation.Exchange
import com.nisaba.domain.conversation.ExchangeStatus
import com.nisaba.domain.conversation.buildRequest
import com.nisaba.domain.persona.Persona
import com.nisaba.domain.settings.Settings
import com.nisaba.domain.shared.ExchangeId
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId

/**
 * Every [personas] × [cases]: the question written by the app's hand, the request the app would build, the reply
 * parsed and scored the way the app reads it. [temperatureScale] multiplies each voice's temperature (to compare).
 */
class EvalRunUseCase(
    private val pages: EvalPageRenderer,
    private val oracle: EvalOracleClient,
    private val settings: Settings = Settings(),
) {
    operator fun invoke(
        personas: List<Persona>,
        cases: List<EvalCase>,
        temperatureScale: Double = 1.0,
        onResult: (EvalResult) -> Unit = {},
    ): List<EvalResult> = personas.flatMap { persona ->
        cases.map { case ->
            val old = if (case.oldVoiceHistory) (if (persona.id == "book") "zagreus" else "book") else null
            val history = old?.let { oldVoiceExchanges(it) }.orEmpty()
            val page = pages.snapshot(case.writing, case.id.hashCode().toLong())
            val input = ContextInput(emptyList(), null, history, page, settings, persona = persona)
            val result = runCatching { oracle.ask(buildRequest(input), temperatureScale) }.fold(
                onSuccess = { reply ->
                    val (problems, words) = scoreReply(reply.text, case, persona, old)
                    EvalResult(
                        persona.id, case.id, problems.isEmpty(), problems, reply.text, words, reply.millis, reply.inputTokens,
                        reply.outputTokens,
                    )
                },
                onFailure = { EvalResult(persona.id, case.id, false, listOf("error: ${it.message}"), "", 0, 0, 0, 0) },
            )
            onResult(result)
            result
        }
    }

    /** Twenty earlier answers in another voice, as a notebook that changed its voice would have. */
    private fun oldVoiceExchanges(voice: String): List<Exchange> {
        val line = when (voice) {
            "zagreus" -> "Blood and darkness, an easy one! %s. Sorry, Father, but it's true."
            else -> "So. %s, as the Sealed Verses foretold. Even Weiss knew that much. Ask me something harder."
        }
        return (1..HISTORY).map { i ->
            Exchange(
                ExchangeId("old$i"), NotebookId("eval"), PageId("p"), "question $i",
                "<answer>\n" + line.format("The answer is $i") + "\n</answer>",
                ExchangeStatus.Complete, i * 1000L, settings.model, 0, 0, 0, 0,
            )
        }
    }

    private companion object {
        const val HISTORY = 20
    }
}
