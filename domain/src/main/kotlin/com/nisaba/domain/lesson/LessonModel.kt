package com.nisaba.domain.lesson

enum class TopicStatus { Learned, Shaky }

/**
 * What the tutor has noted about one topic of a lesson notebook's document. [streak] = how many times in a row it was
 * judged learned (0 when shaky): the gap before it is reviewed grows with it.
 */
data class LessonTopic(val topic: String, val status: TopicStatus, val updatedAtMillis: Long, val streak: Int = 0)
