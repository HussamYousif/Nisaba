package com.nisaba.notebook

import com.nisaba.domain.conversation.Purpose
import com.nisaba.domain.conversation.Reminder
import com.nisaba.domain.conversation.writeFresh
import com.nisaba.domain.lesson.dueTopics
import com.nisaba.domain.notebook.NotebookWithPages
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Learning and habits: a quiz, today's page, the week's recap, spaced review, private notebooks, reminders.

/** ⋯ → Quiz me: three questions on this notebook, on a fresh page at the end. */
fun NotebookViewModel.quizMe() = writeOnFreshPage(Purpose.Quiz)

/** ⋯ → Today's page: the date, what was left open, a question for the day. */
fun NotebookViewModel.todaysPage() = writeOnFreshPage(Purpose.Daily)

/** ⋯ → This week: the past week in this notebook on one page. */
fun NotebookViewModel.recapWeek() = writeOnFreshPage(Purpose.Recap)

/** ⋯ → Review (lesson notebooks): one question on each topic due again. */
fun NotebookViewModel.reviewDue() {
    val due = dueTopics(mutableState.value.lessonTopics, System.currentTimeMillis()).take(MAX_REVIEW).map { it.topic }
    if (due.isNotEmpty()) writeOnFreshPage(Purpose.Review(due))
}

/** How many lesson topics are due for review now. */
fun NotebookUiState.dueReviews(nowMillis: Long = System.currentTimeMillis()): Int = dueTopics(lessonTopics, nowMillis).size

/** A new page after the last one, then Nisaba writes [purpose] on it from the top (paged notebooks only). */
internal fun NotebookViewModel.writeOnFreshPage(purpose: Purpose) {
    val nb = notebook ?: return
    val last = nb.pages.lastOrNull() ?: return
    if (last.size.isEndless || deps.sessions == null) return
    ownScope.launch {
        if (!insertPageAfter(nb, last.id)) return@launch
        openPage(nb.pages.size, full = true)
        currentPage()?.let { session?.writeFresh(it.id, purpose) }
    }
}

/**
 * Settings → A page each morning: the first time a notebook is opened on a new day (and it was written in on an
 * earlier one), Nisaba starts the day's page at the end. Not in lesson notebooks or on an endless canvas.
 */
internal suspend fun NotebookViewModel.maybeDailyPage(nb: NotebookWithPages) {
    if (!settings.dailyPage || nb.notebook.teachGoal != null || deps.sessions == null) return
    if (nb.pages.lastOrNull()?.size?.isEndless != false) return
    val today = LocalDate.now().toString()
    if (deps.memory.lastDaily(deps.notebookId) == today) return
    val last = deps.sources.lastExchangeMillis() ?: return // never used: nothing to pick up yet
    val lastDay = java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    if (lastDay == today) return
    deps.memory.saveLastDaily(deps.notebookId, today)
    writeOnFreshPage(Purpose.Daily)
}

/** ⋯ → Private on/off. */
fun NotebookViewModel.togglePrivate() {
    val on = !(notebook?.notebook?.isPrivate ?: return)
    ownScope.launch {
        deps.changes.setPrivate(deps.notebookId, on)
        mutableState.update { it.copy(notice = if (on) PRIVATE_ON else PRIVATE_OFF) }
    }
}

/** The reminder chip's "Add": the screen opens the calendar (or an alarm) with it filled in. */
fun NotebookViewModel.addReminder() {
    val r = mutableState.value.reminder ?: return
    mutableState.update { it.copy(reminder = null) }
    effectSink.tryEmit(NotebookEffect.Remind(r))
}

fun NotebookViewModel.dismissReminder() = mutableState.update { it.copy(reminder = null) }

/** Nisaba will post the reminder itself at its time. */
fun NotebookViewModel.reminderSet(r: Reminder) = mutableState.update { it.copy(notice = "I'll remind you: " + reminderLine(r)) }

/** No app took the reminder: its words go on the clipboard and the page says so. */
fun NotebookViewModel.reminderNotAdded(r: Reminder) {
    effectSink.tryEmit(NotebookEffect.Copy(reminderLine(r)))
    mutableState.update { it.copy(notice = NO_CALENDAR) }
}

/** "call Ola · Thu 16 Oct, 09:00" for the chip. */
fun reminderLine(r: Reminder): String {
    val at = runCatching { LocalDateTime.parse(r.at).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())) }
    return r.text + (at.getOrNull()?.let { " · $it" } ?: "")
}

private const val MAX_REVIEW = 5
internal const val NO_CALENDAR = "Couldn't set the reminder (no calendar, notifications off): its words are on the clipboard."
internal const val PRIVATE_ON = "Private: nothing here is remembered, recalled or found by search."
internal const val PRIVATE_OFF = "No longer private: Nisaba remembers and recalls here again."
