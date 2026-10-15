package com.nisaba.infra.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Query(
        "SELECT n.id, n.name, n.updatedAt, n.coverSeed, n.documentId IS NOT NULL AS hasDocument, n.teachGoal IS NOT NULL AS lesson, " +
            "n.persona, " +
            "(SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount, " +
            "(SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id AND p.width >= 100000) > 0 AS endless FROM notebooks n",
    )
    fun observeSummaries(): Flow<List<SummaryRowDto>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observeNotebook(id: String): Flow<NotebookDto?>

    @Query("SELECT * FROM pages WHERE notebookId = :id ORDER BY position")
    fun observePages(id: String): Flow<List<PageDto>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun notebook(id: String): NotebookDto?

    @Query("SELECT * FROM pages WHERE notebookId = :id ORDER BY position")
    suspend fun pages(id: String): List<PageDto>

    @Query("SELECT name FROM notebooks")
    suspend fun names(): List<String>

    @Insert
    suspend fun insertNotebook(n: NotebookDto)

    @Insert
    suspend fun insertPages(p: List<PageDto>)

    @Insert
    suspend fun insertPage(p: PageDto)

    @Query("UPDATE notebooks SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE pages SET position = :position WHERE id = :pageId")
    suspend fun setPosition(pageId: String, position: Double)

    @Query("UPDATE notebooks SET listening = :listening WHERE id = :id")
    suspend fun setListening(id: String, listening: Boolean)

    @Query("UPDATE notebooks SET persona = :persona WHERE id = :id")
    suspend fun setPersona(id: String, persona: String)

    @Query("UPDATE notebooks SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Transaction
    suspend fun createWithPages(n: NotebookDto, p: List<PageDto>) {
        insertNotebook(n)
        insertPages(p)
    }

    @Transaction
    suspend fun reposition(positions: List<Pair<String, Double>>) = positions.forEach { (id, pos) -> setPosition(id, pos) }
}

@Dao
interface ExchangeDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(e: ExchangeDto)

    @Query("SELECT * FROM exchanges WHERE notebookId = :id ORDER BY createdAt")
    suspend fun list(id: String): List<ExchangeDto>

    @Query("SELECT * FROM exchanges WHERE notebookId = :id ORDER BY createdAt")
    fun observe(id: String): Flow<List<ExchangeDto>>

    @Query("SELECT * FROM exchanges WHERE id = :id")
    suspend fun get(id: String): ExchangeDto?

    /** [like] is a LIKE pattern with `\\` as the escape character. */
    @Query(
        "SELECT * FROM exchanges WHERE status != 3 AND (heard LIKE :like ESCAPE '\\' OR answerMarkup LIKE :like ESCAPE '\\') " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun search(like: String, limit: Int): List<ExchangeDto>

    @Query("SELECT model, createdAt, inputTokens, cachedTokens, outputTokens FROM exchanges")
    suspend fun usage(): List<UsageRowDto>
}

@Dao
interface LessonDao {
    @Query("SELECT * FROM lesson_topics WHERE notebookId = :id ORDER BY id")
    suspend fun topics(id: String): List<LessonTopicDto>

    @Query("SELECT * FROM lesson_topics WHERE notebookId = :id ORDER BY id")
    fun observe(id: String): Flow<List<LessonTopicDto>>

    @Query("SELECT * FROM lesson_topics WHERE notebookId = :id AND topicKey = :key")
    suspend fun find(id: String, key: String): LessonTopicDto?

    @Insert
    suspend fun insert(t: LessonTopicDto)

    @Query("UPDATE lesson_topics SET topic = :topic, status = :status, updatedAt = :at WHERE id = :rowId")
    suspend fun update(rowId: Long, topic: String, status: String, at: Long)

    /** Replaces each topic's status, adding the ones not seen before, in one transaction. */
    @Transaction
    suspend fun upsert(rows: List<LessonTopicDto>) {
        for (r in rows) {
            val old = find(r.notebookId, r.topicKey)
            if (old == null) insert(r) else update(old.id, r.topic, r.status, r.updatedAt)
        }
    }
}

@Dao
interface FactDao {
    @Query("SELECT * FROM facts ORDER BY id")
    suspend fun all(): List<FactDto>

    @Query("SELECT * FROM facts ORDER BY id")
    fun observe(): Flow<List<FactDto>>

    @Insert
    suspend fun insert(f: List<FactDto>)

    @Query("DELETE FROM facts WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Transaction
    suspend fun apply(add: List<FactDto>, forget: List<Long>) {
        if (forget.isNotEmpty()) delete(forget)
        if (add.isNotEmpty()) insert(add)
    }
}

@Database(
    entities = [NotebookDto::class, PageDto::class, DocumentDto::class, ExchangeDto::class, FactDto::class, LessonTopicDto::class],
    version = 6,
    exportSchema = true,
)
abstract class NisabaDatabase : RoomDatabase() {
    abstract fun notebooks(): NotebookDao
    abstract fun exchanges(): ExchangeDao
    abstract fun facts(): FactDao
    abstract fun lessons(): LessonDao

    companion object {
        @Volatile private var shared: NisabaDatabase? = null

        /** One connection pool for the whole process (notebooks, exchanges and facts share it). */
        fun shared(context: Context): NisabaDatabase = shared ?: synchronized(this) {
            shared ?: Room.databaseBuilder(context.applicationContext, NisabaDatabase::class.java, "nisaba.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { shared = it }
        }

        fun inMemory(context: Context): NisabaDatabase =
            Room.inMemoryDatabaseBuilder(context, NisabaDatabase::class.java).build()
    }
}

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
