package com.nisaba.infra.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
}
