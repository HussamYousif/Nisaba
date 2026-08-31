package com.nisaba.llm

import com.nisaba.notebook.model.AnswerBlock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses and validates the model's structured answer (docs/07 §6).
 * A malformed drawing/table block is dropped with a placeholder rather than
 * failing the whole answer; malformed JSON throws [ParseException] so the
 * caller can run one repair retry.
 */
object AnswerParser {

    @Serializable
    data class RawAnswer(val blocks: List<RawBlock> = emptyList())

    @Serializable
    data class RawBlock(
        val type: String = "",
        val markdown: String? = null,
        val svg: String? = null,
        val title: String? = null,
        val headers: List<String>? = null,
        val rows: List<List<String>>? = null,
    )

    class ParseException(message: String) : Exception(message)

    private const val MAX_BLOCKS = 8
    private const val MAX_TEXT_LEN = 8000
    private const val MAX_SVG_LEN = 2500
    private const val MAX_CELLS = 20
    private const val MAX_CELL_LEN = 40

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawContent: String): List<AnswerBlock> {
        val parsed = try {
            json.decodeFromString(RawAnswer.serializer(), rawContent)
        } catch (e: Exception) {
            throw ParseException("not valid JSON: ${e.message}")
        }
        val blocks = parsed.blocks.take(MAX_BLOCKS)
        if (blocks.isEmpty()) throw ParseException("no blocks in answer")

        return blocks.mapNotNull { raw ->
            when (raw.type) {
                "text" -> {
                    val md = raw.markdown?.take(MAX_TEXT_LEN)?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    AnswerBlock(type = "text", markdown = md)
                }
                "drawing" -> {
                    val svg = raw.svg?.take(MAX_SVG_LEN)?.takeIf { it.isNotBlank() }
                    if (svg == null) {
                        AnswerBlock(type = "text", markdown = "«the drawing smudged»")
                    } else {
                        AnswerBlock(type = "drawing", svg = svg)
                    }
                }
                "table" -> {
                    val headers = (raw.headers ?: emptyList()).take(8).map { it.take(MAX_CELL_LEN) }
                    val rows = (raw.rows ?: emptyList()).take(MAX_CELLS).map { row ->
                        row.take(8).map { it.take(MAX_CELL_LEN) }
                    }
                    if (headers.isEmpty() && rows.isEmpty()) {
                        AnswerBlock(type = "text", markdown = "«the table smudged»")
                    } else {
                        AnswerBlock(
                            type = "table",
                            title = raw.title?.take(120),
                            headers = headers,
                            rows = rows,
                        )
                    }
                }
                else -> null // unknown block types are dropped
            }
        }.ifEmpty {
            throw ParseException("no valid blocks in answer")
        }
    }
}
