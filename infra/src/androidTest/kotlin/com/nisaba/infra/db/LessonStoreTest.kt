package com.nisaba.infra.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nisaba.domain.lesson.LessonTopic
import com.nisaba.domain.lesson.TopicStatus
import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.shared.NotebookId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonStoreTest {
    private lateinit var db: NisabaDatabase
    private val nb = NotebookId("n1")

    @Before fun setUp() {
        db = NisabaDatabase.inMemory(ApplicationProvider.getApplicationContext())
        runBlocking { RoomNotebookStore(db.notebooks()).create(Notebook(nb, "N", 1, 1, null, true, 0, teachGoal = ""), emptyList()) }
    }

    @After fun tearDown() { db.close() }

    @Test fun upsertKeepsOneRowPerTopicWhateverItsCase() = runBlocking {
        val store = RoomLessonStore(db.lessons())
        store.upsert(nb, listOf(LessonTopic("Fjords", TopicStatus.Shaky, 1), LessonTopic("Cities", TopicStatus.Learned, 1)))
        store.upsert(nb, listOf(LessonTopic("fjords", TopicStatus.Learned, 2)))
        assertEquals(
            listOf(LessonTopic("fjords", TopicStatus.Learned, 2), LessonTopic("Cities", TopicStatus.Learned, 1)),
            store.topics(nb),
        )
    }
}
