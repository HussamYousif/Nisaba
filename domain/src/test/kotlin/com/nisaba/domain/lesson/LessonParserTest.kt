package com.nisaba.domain.lesson

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LessonParserTest {
    @Test fun topicTagsBecomeProgressAndTheLastMentionWins() {
        val reply = "<answer>\nRight!\n</answer><topic status=\"learned\">Fjords</topic>\n<topic status=\"shaky\"> Waterfalls </topic>" +
            "<topic status=\"weird\">Cities</topic><topic status=\"learned\">waterfalls</topic><topic> </topic><heard>x</heard>"
        assertEquals(
            listOf(
                LessonTopic("Fjords", TopicStatus.Learned, 5), LessonTopic("Cities", TopicStatus.Shaky, 5),
                LessonTopic("waterfalls", TopicStatus.Learned, 5),
            ),
            parseTopics(reply, 5),
        )
        assertEquals("ok ", stripTopicTags("ok <topic status=\"learned\">Fjords</topic>"))
    }

    @Test fun progressLineListsLearnedThenShaky() {
        assertNull(formatLessonProgress(emptyList()))
        val line = formatLessonProgress(listOf(LessonTopic("A", TopicStatus.Learned, 1), LessonTopic("B", TopicStatus.Shaky, 1)))
        assertEquals("Lesson progress so far (learned: A) (shaky: B).", line)
    }
}
