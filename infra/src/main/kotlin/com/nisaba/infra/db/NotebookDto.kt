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
)
