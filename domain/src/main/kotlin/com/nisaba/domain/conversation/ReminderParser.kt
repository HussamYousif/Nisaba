package com.nisaba.domain.conversation

private val TASK_RE = Regex(
    """<task\s+when\s*=\s*"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(?::\d{2})?"\s*>(.*?)</task>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)
private const val MAX_TASKS = 3
private const val MAX_TASK_CHARS = 120

/** The `<task when="2026-10-16T09:00">call Ola</task>` tags of a reply: what to remind them of, and when. */
fun parseReminders(reply: String): List<Reminder> = TASK_RE.findAll(reply)
    .map { Reminder(it.groupValues[2].trim().replace(Regex("""\s+"""), " ").take(MAX_TASK_CHARS), it.groupValues[1]) }
    .filter { it.text.isNotEmpty() }.distinct().take(MAX_TASKS).toList()

/** [line] without task tags, so they are never handwritten. */
fun stripTaskTags(line: String): String = line.replace(TASK_RE, "")
