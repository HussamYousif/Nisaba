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

    @Test fun v1MigratesAllTheWayToTheCurrentSchema() {
        helper.createDatabase("migAll", 1).apply {
            execSQL("INSERT INTO notebooks (id,name,createdAt,updatedAt,documentId,listening,coverSeed) VALUES ('n1','Old',1,2,NULL,1,7)")
            close()
        }
        helper.runMigrationsAndValidate("migAll", 5, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .query("SELECT COUNT(*) FROM notebooks").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }
}
