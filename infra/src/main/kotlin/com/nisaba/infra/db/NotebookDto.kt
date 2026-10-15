package com.nisaba.infra.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class NotebookDto(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val documentId: String?,
    val listening: Boolean,
    val coverSeed: Int,
    /** Lesson notebooks only (v4). */
    val teachGoal: String? = null,
    /** The voice Nisaba answers in (v6): a `PersonaRegistry` id. */
    val persona: String,
    // v8: answering habits
    val questionsOnly: Boolean = false,
    val answerLength: String = "Normal",
)

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(NotebookDto::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["notebookId", "position"])],
)
data class PageDto(
    @PrimaryKey val id: String,
    val notebookId: String,
    val position: Double,
    val width: Float,
    val height: Float,
    val bgKind: Int,
    val bgDocumentId: String?,
    val bgIndex: Int?,
)

@Entity(tableName = "documents")
data class DocumentDto(
    @PrimaryKey val id: String,
    val mime: String,
    val title: String,
    val fileName: String,
    val pageCount: Int,
    val hasText: Boolean,
    val status: Int,
)

data class SummaryRowDto(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val coverSeed: Int,
    val hasDocument: Boolean,
    val pageCount: Int,
    val endless: Boolean,
    val lesson: Boolean = false,
    val persona: String,
)

@Entity(
    tableName = "exchanges",
    foreignKeys = [ForeignKey(NotebookDto::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["notebookId", "createdAt"])],
)
data class ExchangeDto(
    @PrimaryKey val id: String,
    val notebookId: String,
    val pageId: String,
    val heard: String,
    val answerMarkup: String,
    val status: Int,
    val createdAt: Long,
    val model: String,
    val promptVersion: Int,
    val latencyMillis: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    // v5
    val cachedTokens: Int = 0,
    val focusLeft: Float? = null,
    val focusTop: Float? = null,
    val focusRight: Float? = null,
    val focusBottom: Float? = null,
    // v6
    val persona: String? = null,
    // v7
    val firstTokenMillis: Long? = null,
    val firstBlockMillis: Long? = null,
)

/** The token columns of an exchange, for the usage summary. */
data class UsageRowDto(
    val model: String,
    val createdAt: Long,
    val inputTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    val status: Int = 0,
    val firstBlockMillis: Long? = null,
)

/** Lesson progress (v5): one row per topic of a lesson notebook. */
@Entity(
    tableName = "lesson_topics",
    foreignKeys = [ForeignKey(NotebookDto::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["notebookId", "topicKey"], unique = true)],
)
data class LessonTopicDto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notebookId: String,
    /** Lower-cased [topic], so "Fjords" and "fjords" are one topic. */
    val topicKey: String,
    val topic: String,
    val status: String,
    val updatedAt: Long,
)
