package com.nisaba.domain.lesson

import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.flow.Flow

/** Per-notebook lesson progress. A topic is identified by its name (case-insensitive); upserting replaces its status. */
interface LessonStore {
    suspend fun topics(notebookId: NotebookId): List<LessonTopic>
    fun observe(notebookId: NotebookId): Flow<List<LessonTopic>>
    suspend fun upsert(notebookId: NotebookId, topics: List<LessonTopic>)
}
