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

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** Days until a topic comes up for review again: shaky the next day; learned after 2, 4, 8, 16, 30, 60 days. */
private val LEARNED_GAPS = listOf(2, 4, 8, 16, 30, 60)

/** [topic] as just judged [status] at [atMillis], its streak carried on from what was recorded before ([old]). */
fun judged(old: LessonTopic?, topic: LessonTopic): LessonTopic = when (topic.status) {
    TopicStatus.Shaky -> topic.copy(streak = 0)
    TopicStatus.Learned -> topic.copy(streak = if (old?.status == TopicStatus.Learned) old.streak + 1 else 1)
}

/** When [t] is next due for review. */
fun dueAt(t: LessonTopic): Long {
    val days = if (t.status == TopicStatus.Shaky) 1 else LEARNED_GAPS[(t.streak - 1).coerceIn(0, LEARNED_GAPS.lastIndex)]
    return t.updatedAtMillis + days * DAY_MILLIS
}

/** The topics due for review at [nowMillis], the most overdue first (shaky ones before learned ones on a tie). */
fun dueTopics(topics: List<LessonTopic>, nowMillis: Long): List<LessonTopic> =
    topics.filter { dueAt(it) <= nowMillis }.sortedWith(compareBy({ dueAt(it) }, { it.status != TopicStatus.Shaky }))
