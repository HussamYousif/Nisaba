package com.nisaba.domain.lesson

enum class TopicStatus { Learned, Shaky }

/** What the tutor has noted about one topic of a lesson notebook's document. */
data class LessonTopic(val topic: String, val status: TopicStatus, val updatedAtMillis: Long)
