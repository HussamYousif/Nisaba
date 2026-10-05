package com.nisaba.infra.db

import com.nisaba.domain.lesson.LessonStore
import com.nisaba.domain.lesson.LessonTopic
import com.nisaba.domain.lesson.TopicStatus
import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLessonStore(private val dao: LessonDao) : LessonStore {
    companion object {
        /** Opens the shared on-disk database; keeps Room types out of the app module. */
        fun open(context: android.content.Context) = RoomLessonStore(NisabaDatabase.shared(context).lessons())
    }

    override suspend fun topics(notebookId: NotebookId): List<LessonTopic> = dao.topics(notebookId.value).map { it.toModel() }

    override fun observe(notebookId: NotebookId): Flow<List<LessonTopic>> =
        dao.observe(notebookId.value).map { rows -> rows.map { it.toModel() } }

    override suspend fun upsert(notebookId: NotebookId, topics: List<LessonTopic>) = dao.upsert(topics.map { it.toDto(notebookId) })
}

private fun LessonTopicDto.toModel() =
    LessonTopic(topic, TopicStatus.entries.firstOrNull { it.name == status } ?: TopicStatus.Shaky, updatedAt)

private fun LessonTopic.toDto(nb: NotebookId) = LessonTopicDto(
    notebookId = nb.value, topicKey = topic.trim().lowercase(), topic = topic.trim(), status = status.name, updatedAt = updatedAtMillis,
)
