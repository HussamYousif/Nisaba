package com.nisaba.infra.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2: adds the `exchanges` table (M2.2). Existing notebooks and pages are untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exchanges` (`id` TEXT NOT NULL, `notebookId` TEXT NOT NULL, `pageId` TEXT NOT NULL, " +
                "`heard` TEXT NOT NULL, `answerMarkup` TEXT NOT NULL, `status` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`model` TEXT NOT NULL, `promptVersion` INTEGER NOT NULL, `latencyMillis` INTEGER NOT NULL, " +
                "`inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exchanges_notebookId_createdAt` ON `exchanges` (`notebookId`, `createdAt`)")
    }
}

/** v2 → v3: adds the `facts` table (long-term memory, DESIGN §4.5). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `facts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, `sourceExchangeId` TEXT, `createdAt` INTEGER NOT NULL)",
        )
    }
}

/** v3 → v4: lesson notebooks remember the learner's goal (null = an ordinary notebook). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `teachGoal` TEXT")
    }
}

/** v4 → v5: cached tokens and the answered region on exchanges (usage, "ask again"); lesson progress. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exchanges` ADD COLUMN `cachedTokens` INTEGER NOT NULL DEFAULT 0")
        for (c in listOf("focusLeft", "focusTop", "focusRight", "focusBottom")) db.execSQL("ALTER TABLE `exchanges` ADD COLUMN `$c` REAL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `lesson_topics` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `notebookId` TEXT NOT NULL, " +
                "`topicKey` TEXT NOT NULL, `topic` TEXT NOT NULL, `status` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_lesson_topics_notebookId_topicKey` ON `lesson_topics` (`notebookId`, `topicKey`)",
        )
    }
}

/**
 * v5 → v6: every notebook answers in a voice (DESIGN D17). Notebooks from before keep the one they always had (the
 * sassy book); exchanges record the voice that answered (null = before voices).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `persona` TEXT NOT NULL DEFAULT 'book'")
        db.execSQL("ALTER TABLE `exchanges` ADD COLUMN `persona` TEXT")
    }
}

/** v6 → v7: how long each answer took to start (first token, first block the hand could write), for Usage. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exchanges` ADD COLUMN `firstTokenMillis` INTEGER")
        db.execSQL("ALTER TABLE `exchanges` ADD COLUMN `firstBlockMillis` INTEGER")
    }
}

/** v7 → v8: answering habits per notebook (everything / questions only; brief / normal / detailed). */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `questionsOnly` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `answerLength` TEXT NOT NULL DEFAULT 'Normal'")
    }
}

/** v8 → v9: the person's own voices, drafted from a description (DESIGN §5.3a). */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `own_voices` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `tagline` TEXT NOT NULL, " +
                "`sample` TEXT NOT NULL, `voice` TEXT NOT NULL, `words` INTEGER NOT NULL, `temperature` REAL NOT NULL, " +
                "`ink` INTEGER, `hint` TEXT NOT NULL, `oops` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/** Every step from v1 to the current schema, in order. */
val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
)
