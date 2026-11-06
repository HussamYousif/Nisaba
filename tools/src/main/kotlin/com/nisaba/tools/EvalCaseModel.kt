package com.nisaba.tools

/**
 * One check of the model's behaviour: what is "handwritten" on the page and what the reply must do. [mustContain]
 * = words the answer needs (any one of each inner list counts); [silent] = it must reply `<silent/>`; [remembers]
 * = it must (true) or must not (false) add a `<remember>`; [norwegian] = it must answer in Norwegian.
 */
data class EvalCase(
    val id: String,
    val writing: String,
    val mustContain: List<List<String>> = emptyList(),
    val silent: Boolean = false,
    val remembers: Boolean? = null,
    val norwegian: Boolean = false,
    /** Earlier turns in another voice, to see whether the notebook's voice survives them. */
    val oldVoiceHistory: Boolean = false,
    /** Grief and hardship: whatever the voice, no act (checked by reading; listed in the report). */
    val serious: Boolean = false,
)

val EVAL_CASES = listOf(
    EvalCase("fact", "What is the capital of Norway?", mustContain = listOf(listOf("Oslo"))),
    EvalCase("math", "Solve x^2 - 5x + 6 = 0", mustContain = listOf(listOf("2"), listOf("3"))),
    EvalCase("norsk", "Hva er 12 ganger 12?", mustContain = listOf(listOf("144")), norwegian = true),
    EvalCase("note", "Buy milk, eggs, call Ola tomorrow"),
    EvalCase("me", "I'm a nurse and I loved Dune", remembers = true),
    EvalCase("joke", "lol that was funny", remembers = false),
    EvalCase("grief", "My grandmother died yesterday", serious = true, remembers = null),
    EvalCase("switch", "What is the boiling point of water?", mustContain = listOf(listOf("100")), oldVoiceHistory = true),
)

/** Phrases that belong to one voice: after a switch they should not leak into another. */
val VOICE_MARKERS = mapOf(
    "book" to listOf("Shadowlord", "Sealed Verses", "Weiss"),
    "jensen" to listOf("never asked for this", "Sarif"),
)

data class EvalResult(
    val persona: String,
    val case: String,
    val ok: Boolean,
    val problems: List<String>,
    val reply: String,
    val words: Int,
    val millis: Long,
    val inputTokens: Int,
    val outputTokens: Int,
)
