package com.nisaba.domain.lesson

/** One line for the request: what the tutor has recorded so far (null when nothing yet). */
fun formatLessonProgress(topics: List<LessonTopic>): String? {
    if (topics.isEmpty()) return null
    val learned = topics.filter { it.status == TopicStatus.Learned }.joinToString(", ") { it.topic }
    val shaky = topics.filter { it.status == TopicStatus.Shaky }.joinToString(", ") { it.topic }
    return buildString {
        append("Lesson progress so far")
        if (learned.isNotEmpty()) append(" (learned: ").append(learned).append(")")
        if (shaky.isNotEmpty()) append(" (shaky: ").append(shaky).append(")")
        append(".")
    }
}
