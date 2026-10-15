package com.nisaba.domain.lesson

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewRulesTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun learnedAgainAndAgainComesUpLessOftenAndAShakyOneTomorrow() {
        var t = judged(null, LessonTopic("Fjords", TopicStatus.Learned, 0))
        assertEquals(1, t.streak); assertEquals(2 * day, dueAt(t))
        t = judged(t, LessonTopic("Fjords", TopicStatus.Learned, 10 * day))
        assertEquals(2, t.streak); assertEquals(14 * day, dueAt(t))
        t = judged(t, LessonTopic("Fjords", TopicStatus.Shaky, 20 * day))
        assertEquals(0, t.streak); assertEquals(21 * day, dueAt(t))
        val long = LessonTopic("Sills", TopicStatus.Learned, 0, streak = 40)
        assertEquals(60 * day, dueAt(long)) // the gap stops growing
    }

    @Test fun dueTopicsAreTheOverdueOnesMostOverdueFirst() {
        val topics = listOf(
            LessonTopic("A", TopicStatus.Learned, 0, streak = 1), // due day 2
            LessonTopic("B", TopicStatus.Shaky, 0), // due day 1
            LessonTopic("C", TopicStatus.Learned, 0, streak = 3), // due day 8
        )
        assertEquals(listOf("B", "A"), dueTopics(topics, 3 * day).map { it.topic })
        assertEquals(emptyList(), dueTopics(topics, day / 2))
    }
}
