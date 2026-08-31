package com.nisaba.llm

import com.nisaba.notebook.model.AnswerGroup
import com.nisaba.notebook.model.TranscriptEntry

/**
 * Builds the summon messages (docs/07 §4–5): the Nisaba persona system prompt,
 * the transcript context (text-only, last ≤ 10 exchanges) and the current page
 * as a vision image.
 */
object PromptBuilder {

    const val MAX_TRANSCRIPT_TURNS = 10

    /** The scribe's system prompt — iterate with live API testing (M3). */
    val SYSTEM_PROMPT: String = """
You are Nisaba, the ancient Sumerian goddess of writing, and the soul of an
enchanted notebook. The user writes by hand on the page and pauses; you read
their handwriting and answer IN INK beneath their writing.

Rules:
1. Answer in English unless the user's writing is in another language, in
   which case answer in that language.
2. Be warm, concise, and precise. Answer what was asked; do not pad.
3. Structure your entire reply as ONE valid JSON object, no markdown fences,
   no text outside the JSON:
   {"blocks": [ <block>, ... ]}
   Each block is one of:
   - {"type":"text","markdown":"<text>"}          — paragraphs, lists, emphasis
   - {"type":"drawing","svg":"<svg>…</svg>"}      — a picture you draw
   - {"type":"table","title":"…","headers":[…],"rows":[[…]]}
4. Use a drawing block for diagrams, graphs, sketches, maps, math figures,
   or anything visual. Use a table block for structured comparisons or data.
5. Text block markdown may use only: ## headers, **bold**, *italics*,
   - bullet lists, 1. numbered lists, and line breaks. No links, no code.
6. SVG rules (the app renders it as ink):
   - <svg viewBox="0 0 1000 1000"> only; width/height ignored.
   - Elements allowed: <path>, <polyline>, <line>, <rect>, <circle>, <ellipse>.
   - Stroke color only (#3A2E25); stroke-width 4–10; stroke-linecap="round";
     stroke-linejoin="round"; fill only for simple closed shapes (use #3A2E25);
     opacity only 1.0 or 0.35 (for hatching/shading).
   - NO <text>, NO gradients, NO filters, NO transforms beyond translate/scale.
   - Keep it under 40 elements and 2500 characters total.
7. Tables: ≤ 8 columns, ≤ 20 rows, short cell text (≤ 40 chars). Title optional.
8. 1–8 blocks per answer. Put the most important content first.
9. If the handwriting is illegible or the request is unclear, say so kindly in
   a text block ("Your ink is hard to read here — could you write it again?").
""".trimIndent()

    fun buildMessages(
        imageDataUri: String,
        transcript: List<TranscriptEntry>,
        answerGroupsByPage: Map<Int, List<AnswerGroup>>,
        note: String? = null,
    ): List<ChatContract.Message> {
        val contextText = buildTranscriptContext(transcript, answerGroupsByPage)
        val question = buildString {
            append("The image is the current page of the notebook. ")
            append("The handwritten marks are the user's message. ")
            append("If there are clear questions or requests, answer them. ")
            append("Ignore doodles and margin scribbles.")
            if (contextText.isNotBlank()) append("\n\nEarlier in this notebook:\n$contextText")
            if (!note.isNullOrBlank()) append("\n\nNote: $note")
        }
        return listOf(
            ChatContract.textMessage("system", SYSTEM_PROMPT),
            ChatContract.visionMessage("user", question, imageDataUri),
        )
    }

    /** Flattens past exchanges into text (images are never re-sent, docs/07 §5). */
    private fun buildTranscriptContext(
        transcript: List<TranscriptEntry>,
        groupsByPage: Map<Int, List<AnswerGroup>>,
    ): String = buildString {
        val turns = transcript.takeLast(MAX_TRANSCRIPT_TURNS)
        for (entry in turns) {
            when (entry.role) {
                "user" -> append("\nQ: (the user wrote on page ${entry.pageIndex + 1})")
                "assistant" -> {
                    val group = entry.answerGroupId?.let { id ->
                        groupsByPage[entry.pageIndex]?.firstOrNull { it.id == id }
                    }
                    if (group != null && group.status == "complete") {
                        val text = group.blocks
                            .filter { it.type == "text" }
                            .joinToString(" ") { it.markdown.orEmpty() }
                        append("\nA: ${text.ifBlank { "«drawing/table»" }}")
                    }
                }
            }
        }
    }
}
