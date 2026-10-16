package com.nisaba.infra.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), NisabaDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun v1ToV2KeepsNotebooksAndAddsExchanges() {
        helper.createDatabase("mig", 1).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed) VALUES ('n1','Old',1,2,NULL,1,7)")
            execSQL("INSERT INTO pages (id,notebookId,position,width,height,bgKind,bgDocumentId,bgIndex) VALUES ('p1','n1',1.0,1860,2480,0,NULL,NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig", 2, true, MIGRATION_1_2)
        db.query("SELECT name FROM notebooks").use { c -> c.moveToFirst(); assertEquals("Old", c.getString(0)) }
        db.query("SELECT COUNT(*) FROM pages").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.execSQL(
            "INSERT INTO exchanges (id,notebookId,pageId,heard,answerMarkup,status,createdAt,model,promptVersion,latencyMillis,inputTokens,outputTokens) " +
                "VALUES ('e1','n1','p1','q','a',0,5,'m',1,900,10,20)",
        )
        db.execSQL("PRAGMA foreign_keys = ON") // Room enables this itself at runtime; the raw helper connection does not
        db.execSQL("DELETE FROM notebooks WHERE id='n1'")
        db.query("SELECT COUNT(*) FROM exchanges").use { c -> c.moveToFirst(); assertEquals("cascade delete", 0, c.getInt(0)) }
    }

    @Test fun v2ToV3AddsFactsAndKeepsExchanges() {
        helper.createDatabase("mig3", 2).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed) VALUES ('n1','Old',1,2,NULL,1,7)")
            execSQL(
                "INSERT INTO exchanges (id,notebookId,pageId,heard,answerMarkup,status,createdAt,model,promptVersion,latencyMillis,inputTokens,outputTokens) " +
                    "VALUES ('e1','n1','p1','q','a',0,5,'m',1,900,10,20)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig3", 3, true, MIGRATION_2_3)
        db.query("SELECT COUNT(*) FROM exchanges").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.execSQL("INSERT INTO facts (category,text,sourceExchangeId,createdAt) VALUES ('books','Loved Dune','e1',9)")
        db.query("SELECT id, text FROM facts").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)); assertEquals("Loved Dune", c.getString(1)) }
    }

    @Test fun v3ToV4KeepsNotebooksAsOrdinaryOnes() {
        helper.createDatabase("mig4", 3).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed) VALUES ('n1','Old',1,2,NULL,1,7)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig4", 4, true, MIGRATION_3_4)
        db.query("SELECT name, teachGoal FROM notebooks").use { c ->
            c.moveToFirst(); assertEquals("Old", c.getString(0)); assertTrue(c.isNull(1))
        }
    }

    @Test fun v4ToV5AddsUsageColumnsAndLessonTopics() {
        helper.createDatabase("mig5", 4).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed,teachGoal) VALUES ('n1','Old',1,2,NULL,1,7,'')")
            execSQL(
                "INSERT INTO exchanges (id,notebookId,pageId,heard,answerMarkup,status,createdAt,model,promptVersion,latencyMillis,inputTokens,outputTokens) " +
                    "VALUES ('e1','n1','p1','q','a',0,5,'m',1,900,10,20)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig5", 5, true, MIGRATION_4_5)
        db.query("SELECT cachedTokens, focusLeft FROM exchanges").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)); assertTrue(c.isNull(1)) }
        db.execSQL("INSERT INTO lesson_topics (notebookId,topicKey,topic,status,updatedAt) VALUES ('n1','fjords','Fjords','Learned',9)")
        db.query("SELECT COUNT(*) FROM lesson_topics").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    @Test fun v5ToV6GivesOldNotebooksTheSassyBookAndExchangesNoVoice() {
        helper.createDatabase("mig6", 5).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed,teachGoal) VALUES ('n1','Old',1,2,NULL,1,7,NULL)")
            execSQL(
                "INSERT INTO exchanges (id,notebookId,pageId,heard,answerMarkup,status,createdAt,model,promptVersion,latencyMillis,inputTokens,outputTokens," +
                    "cachedTokens) VALUES ('e1','n1','p1','q','a',0,5,'m',1,900,10,20,0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig6", 6, true, MIGRATION_5_6)
        db.query("SELECT persona FROM notebooks").use { c -> c.moveToFirst(); assertEquals("book", c.getString(0)) }
        db.query("SELECT persona FROM exchanges").use { c -> c.moveToFirst(); assertTrue(c.isNull(0)) }
    }

    @Test fun v6ToV7AddsAnswerTimings() {
        helper.createDatabase("mig7", 6).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed,teachGoal,persona) VALUES ('n1','Old',1,2,NULL,1,7,NULL,'book')")
            execSQL(
                "INSERT INTO exchanges (id,notebookId,pageId,heard,answerMarkup,status,createdAt,model,promptVersion,latencyMillis,inputTokens,outputTokens," +
                    "cachedTokens,persona) VALUES ('e1','n1','p1','q','a',0,5,'m',1,900,10,20,0,'book')",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig7", 7, true, MIGRATION_6_7)
        db.query("SELECT firstTokenMillis, firstBlockMillis FROM exchanges").use { c -> c.moveToFirst(); assertTrue(c.isNull(0) && c.isNull(1)) }
    }

    @Test fun v7ToV8GivesOldNotebooksTheirUsualAnswers() {
        helper.createDatabase("mig8", 7).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed,teachGoal,persona) VALUES ('n1','Old',1,2,NULL,1,7,NULL,'book')")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig8", 8, true, MIGRATION_7_8)
        db.query("SELECT questionsOnly, answerLength FROM notebooks").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0)); assertEquals("Normal", c.getString(1))
        }
    }

    @Test fun v8ToV9AddsOwnVoicesAndKeepsNotebooks() {
        helper.createDatabase("mig9", 8).apply {
            execSQL(
                "INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed,teachGoal,persona,questionsOnly,answerLength) " +
                    "VALUES ('n1','Old',1,2,NULL,1,7,NULL,'yukino',0,'Normal')",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig9", 9, true, MIGRATION_8_9)
        db.query("SELECT persona FROM notebooks").use { c -> c.moveToFirst(); assertEquals("yukino", c.getString(0)) }
        db.execSQL(
            "INSERT INTO own_voices (id,name,tagline,sample,voice,words,temperature,ink,hint,oops,createdAt) " +
                "VALUES ('own-1','Pirate','Arr','Arr.','You are Nisaba, a pirate.',50,0.75,NULL,'Write, then pause.','Blast.',3)",
        )
        db.query("SELECT name FROM own_voices").use { c -> c.moveToFirst(); assertEquals("Pirate", c.getString(0)) }
    }

    @Test fun v9ToV10KeepsNotebooksOpenAndTopicsUnreviewed() {
        helper.createDatabase("mig10", 9).apply {
            execSQL(
                "INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed,teachGoal,persona,questionsOnly,answerLength) " +
                    "VALUES ('n1','Old',1,2,NULL,1,7,'',' book',0,'Normal')",
            )
            execSQL("INSERT INTO lesson_topics (notebookId,topicKey,topic,status,updatedAt) VALUES ('n1','fjords','Fjords','Learned',5)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig10", 10, true, MIGRATION_9_10)
        db.query("SELECT isPrivate FROM notebooks").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.query("SELECT streak FROM lesson_topics").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }

    @Test fun v10ToV11LeavesNotebooksUnpinnedOutOfTheTrashOnBlankPaper() {
        helper.createDatabase("mig11", 10).apply {
            execSQL(
                "INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed,teachGoal,persona,questionsOnly," +
                    "answerLength,isPrivate) VALUES ('n1','Old',1,2,NULL,1,7,NULL,'book',0,'Normal',0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig11", 11, true, MIGRATION_10_11)
        db.query("SELECT pinned, deletedAt, paper FROM notebooks").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0)); assertTrue(c.isNull(1)); assertEquals("Blank", c.getString(2))
        }
    }

    @Test fun v11ToV12MakesNoNotebookABookWalkAndAddsMaps() {
        helper.createDatabase("mig12", 11).apply {
            execSQL(
                "INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed,teachGoal,persona,questionsOnly," +
                    "answerLength,isPrivate,pinned,deletedAt,paper) VALUES ('n1','Old',1,2,NULL,1,7,NULL,'book',0,'Normal',0,0,NULL,'Blank')",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig12", 12, true, MIGRATION_11_12)
        db.query("SELECT book FROM notebooks").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.execSQL("INSERT INTO book_maps (notebookId, json) VALUES ('n1', '{}')")
        db.query("SELECT COUNT(*) FROM book_maps").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    @Test fun v1MigratesAllTheWayToTheCurrentSchema() {
        helper.createDatabase("migAll", 1).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed) VALUES ('n1','Old',1,2,NULL,1,7)")
            close()
        }
        helper.runMigrationsAndValidate("migAll", 12, true, *MIGRATIONS)
            .query("SELECT COUNT(*) FROM notebooks").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }
}
