package com.nisaba.llm

import com.nisaba.notebook.model.AnswerBlock
import com.nisaba.notebook.model.AnswerGroup
import com.nisaba.notebook.model.TranscriptEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PromptBuilder (docs/07 §4–5, audit P3-3): message shape, transcript
 * flattening (role dispatch, complete-only answers, drawing/table fallback,
 * last-10-turns cap) and the note suffix.
 */
class PromptBuilderTest {

    private val groups = listOf(
        AnswerGroup(
            id = "g1", status = "complete",
            blocks = listOf(AnswerBlock(type = "text", markdown = "**Forty-two.**")),
        ),
        AnswerGroup(
            id = "g2", status = "complete",
            blocks = listOf(
                AnswerBlock(type = "text", markdown = "Lead text"),
                AnswerBlock(type = "table", headers = listOf("A"), rows = listOf(listOf("1"))),
            ),
        ),
        AnswerGroup(
            id = "g3", status = "complete",
            blocks = listOf(AnswerBlock(type = "drawing", svg = "<svg/>")),
        ),
        AnswerGroup(
            id = "g4", status = "cancelled",
            blocks = listOf(AnswerBlock(type = "text", markdown = "never said")),
        ),
    )

    private fun build(transcript: List<TranscriptEntry>, note: String? = null): List<ChatContract.Message> =
        PromptBuilder.buildMessages(
            imageDataUri = "data:image/jpeg;base64,AAAA",
            transcript = transcript,
            answerGroupsByPage = mapOf(0 to groups),
            note = note,
        )

    /** Flattened text of a message (string or multimodal parts). */
    private fun textOf(msg: ChatContract.Message): String {
        val c = msg.content ?: return ""
        return when (c) {
            is JsonPrimitive -> c.content
            is JsonArray -> c.mapNotNull { el ->
                (el as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString(" ")
            else -> ""
        }
    }

    private fun imageUris(msg: ChatContract.Message): List<String> =
        (msg.content as? JsonArray).orEmpty().mapNotNull { el ->
            (el as? JsonObject)?.get("image_url") as? JsonObject
        }.mapNotNull { it.get("url")?.jsonPrimitive?.contentOrNull }

    @Test
    fun buildMessages_systemPlusVisionUser() {
        val messages = build(emptyList())
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertTrue(textOf(messages[0]).contains("Nisaba"))
        assertEquals("user", messages[1].role)
        assertTrue(textOf(messages[1]).contains("The image is the current page"))
        assertEquals(listOf("data:image/jpeg;base64,AAAA"), imageUris(messages[1]))
    }

    @Test
    fun transcript_userEntries_becomeQuestions() {
        val messages = build(listOf(TranscriptEntry(role = "user", at = "t", pageIndex = 2)))
        assertTrue(textOf(messages[1]).contains("Q: (the user wrote on page 3)"))
    }

    @Test
    fun transcript_completeAnswers_areFlattened() {
        val messages = build(
            listOf(
                TranscriptEntry(role = "user", at = "t", pageIndex = 0),
                TranscriptEntry(role = "assistant", at = "t", pageIndex = 0, answerGroupId = "g1"),
            )
        )
        assertTrue("answer text missing", textOf(messages[1]).contains("A: **Forty-two.**"))
    }

    @Test
    fun transcript_textPlusTable_joinsOnlyText() {
        val messages = build(
            listOf(
                TranscriptEntry(role = "user", at = "t", pageIndex = 0),
                TranscriptEntry(role = "assistant", at = "t", pageIndex = 0, answerGroupId = "g2"),
            )
        )
        val context = textOf(messages[1])
        assertTrue(context.contains("A: Lead text"))
        assertTrue("tables must not be flattened into the context", !context.contains("rows"))
    }

    @Test
    fun transcript_drawingOnlyAnswer_becomesFallback() {
        val messages = build(
            listOf(
                TranscriptEntry(role = "user", at = "t", pageIndex = 0),
                TranscriptEntry(role = "assistant", at = "t", pageIndex = 0, answerGroupId = "g3"),
            )
        )
        assertTrue(textOf(messages[1]).contains("A: «drawing/table»"))
    }

    @Test
    fun transcript_cancelledOrUnknownGroups_areOmitted() {
        val messages = build(
            listOf(
                TranscriptEntry(role = "assistant", at = "t", pageIndex = 0, answerGroupId = "g4"), // cancelled
                TranscriptEntry(role = "assistant", at = "t", pageIndex = 0, answerGroupId = "ghost"), // unknown
            )
        )
        assertTrue("cancelled/unknown answers must be omitted", !textOf(messages[1]).contains("A:"))
    }

    @Test
    fun transcript_isCappedAtTenTurns() {
        val transcript = (0 until 14).flatMap { i ->
            listOf(
                TranscriptEntry(role = "user", at = "t$i", pageIndex = i),
                TranscriptEntry(role = "assistant", at = "t$i", pageIndex = i, answerGroupId = "g1"),
            )
        }
        val context = textOf(build(transcript)[1])
        // Only the last 10 turns (5 Q/A pairs) make it in. ("page 1" would
        // match inside "page 10", so probe a definitely-cut page instead.)
        assertTrue("page 10 missing", context.contains("page 10"))
        assertTrue("older turns must be cut", !context.contains("page 2"))
    }

    @Test
    fun note_isAppended() {
        val messages = build(emptyList(), note = "the user is left-handed")
        assertTrue(textOf(messages[1]).contains("Note: the user is left-handed"))
    }
}
