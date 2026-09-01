package com.nisaba.notebook.model

import kotlinx.serialization.Serializable

/**
 * The notebook data model (docs/05 §2). kotlinx-serialization DTOs; JSON is the
 * single source of truth. Fields are `var` where the notebook screen mutates
 * them in place before saving.
 */

@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val p: Float,
    val t: Long, // ms since stroke start
)

@Serializable
data class Stroke(
    val id: String,
    val tool: String = "pen", // "pen" | "eraser"
    val color: String = "sepia",
    val width: Float = 3.0f, // base width in page px
    val points: List<StrokePoint> = emptyList(),
)

@Serializable
data class AnswerBlock(
    val type: String, // "text" | "drawing" | "table"
    val markdown: String? = null,
    val svg: String? = null,
    val title: String? = null,
    val headers: List<String>? = null,
    val rows: List<List<String>>? = null,
)

@Serializable
data class AnswerGroup(
    val id: String,
    val summonedAt: String = "",
    val questionPage: Int = 0,
    val model: String = "",
    val status: String = "pending", // "pending" | "complete" | "cancelled" | "error"
    val error: String? = null,
    val blocks: List<AnswerBlock> = emptyList(),
)

@Serializable
data class NotebookPage(
    val index: Int,
    val width: Int = 2480,
    val height: Int = 1860,
    val background: String? = null, // null = blank parchment
    var strokes: List<Stroke> = emptyList(),
    var answers: List<AnswerGroup> = emptyList(),
)

@Serializable
data class TranscriptEntry(
    val role: String, // "user" | "assistant"
    val at: String = "",
    val pageIndex: Int = 0,
    val answerGroupId: String? = null,
)

@Serializable
data class NotebookSource(
    val type: String, // "pdf"
    val file: String? = null,
    val pageCount: Int = 0,
    val importedAt: String = "",
)

@Serializable
data class Notebook(
    val schemaVersion: Int = 1,
    val id: String,
    var title: String,
    var createdAt: String = "",
    var updatedAt: String = "",
    var theme: String = "parchment-light",
    var inkColor: String = "sepia",
    var source: NotebookSource? = null,
    var pages: List<NotebookPage> = mutableListOf(NotebookPage(index = 0)),
    var transcript: List<TranscriptEntry> = emptyList(),
    var lastOpenedPage: Int = 0,
) {
    companion object {
        /** Bump when the JSON shape changes incompatibly (audit P2-5). */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
