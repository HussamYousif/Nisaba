package com.nisaba.domain.lesson

private val TOPIC_RE = Regex(
    """<topic(?:\s+status\s*=\s*"([^"]*)")?\s*>(.*?)</topic>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)
private const val MAX_TOPICS_PER_REPLY = 5
private const val MAX_TOPIC_CHARS = 60

/**
 * The `<topic status="learned|shaky">Name</topic>` tags of a reply (lesson notebooks). Unknown status = shaky; blank or
 * repeated names are dropped (the last mention wins), long names cut, at most a few per reply.
 */
fun parseTopics(reply: String, atMillis: Long): List<LessonTopic> {
    val byKey = LinkedHashMap<String, LessonTopic>()
    for (m in TOPIC_RE.findAll(reply)) {
        val name = m.groupValues[2].trim().replace(Regex("""\s+"""), " ").take(MAX_TOPIC_CHARS)
        if (name.isEmpty()) continue
        val status = if (m.groupValues[1].trim().equals("learned", ignoreCase = true)) TopicStatus.Learned else TopicStatus.Shaky
        byKey.remove(name.lowercase())
        byKey[name.lowercase()] = LessonTopic(name, status, atMillis)
    }
    return byKey.values.toList().takeLast(MAX_TOPICS_PER_REPLY)
}

/** [line] without topic tags, so they are never handwritten. */
fun stripTopicTags(line: String): String = line.replace(TOPIC_RE, "")
