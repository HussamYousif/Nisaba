package com.nisaba.infra.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Query(
        "SELECT n.id, n.name, n.updatedAt, n.coverSeed, n.documentId IS NOT NULL AS hasDocument, n.teachGoal IS NOT NULL AS lesson, " +
            "n.persona, n.isPrivate, n.pinned, n.deletedAt, " +
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

    @Query("UPDATE notebooks SET isPrivate = :isPrivate WHERE id = :id")
    suspend fun setPrivate(id: String, isPrivate: Boolean)

    @Query("UPDATE notebooks SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE notebooks SET deletedAt = :at WHERE id = :id")
    suspend fun setTrashed(id: String, at: Long?)

    @Query("UPDATE notebooks SET paper = :paper WHERE id = :id")
    suspend fun setPaper(id: String, paper: String)

    @Query("UPDATE notebooks SET teachGoal = :goal WHERE id = :id")
    suspend fun setTeachGoal(id: String, goal: String?)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePage(pageId: String)

    @Query("UPDATE notebooks SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE notebooks SET listening = :listening, questionsOnly = :questionsOnly, answerLength = :length WHERE id = :id")
    suspend fun setStyle(id: String, listening: Boolean, questionsOnly: Boolean, length: String)

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
        "SELECT * FROM exchanges WHERE status NOT IN (3, 4, 5) AND (heard LIKE :like ESCAPE '\\' OR answerMarkup LIKE :like ESCAPE '\\') " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun search(like: String, limit: Int): List<ExchangeDto>

    @Query("SELECT model, createdAt, inputTokens, cachedTokens, outputTokens, status, firstBlockMillis FROM exchanges")
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

    @Query("UPDATE lesson_topics SET topic = :topic, status = :status, updatedAt = :at, streak = :streak WHERE id = :rowId")
    suspend fun update(rowId: Long, topic: String, status: String, at: Long, streak: Int)

    /** Replaces each topic's status, adding the ones not seen before, in one transaction. */
    @Transaction
    suspend fun upsert(rows: List<LessonTopicDto>) {
        for (r in rows) {
            val old = find(r.notebookId, r.topicKey)
            if (old == null) insert(r) else update(old.id, r.topic, r.status, r.updatedAt, r.streak)
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

@Dao
interface OwnVoiceDao {
    @Query("SELECT * FROM own_voices ORDER BY createdAt, rowid")
    fun observe(): Flow<List<OwnVoiceDto>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(v: OwnVoiceDto)

    @Query("DELETE FROM own_voices WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [
        NotebookDto::class, PageDto::class, DocumentDto::class, ExchangeDto::class, FactDto::class, LessonTopicDto::class,
        OwnVoiceDto::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class NisabaDatabase : RoomDatabase() {
    abstract fun notebooks(): NotebookDao
    abstract fun exchanges(): ExchangeDao
    abstract fun facts(): FactDao
    abstract fun lessons(): LessonDao
    abstract fun ownVoices(): OwnVoiceDao

    companion object {
        @Volatile private var shared: NisabaDatabase? = null

        /** One connection pool for the whole process (notebooks, exchanges and facts share it). */
        @Suppress("SpreadOperator") // once per process
        fun shared(context: Context): NisabaDatabase = shared ?: synchronized(this) {
            shared ?: Room.databaseBuilder(context.applicationContext, NisabaDatabase::class.java, "nisaba.db")
                .addMigrations(*MIGRATIONS)
                .build().also { shared = it }
        }

        /** Closes the shared database (a restore is about to replace its file); the next [shared] opens it again. */
        fun closeShared() = synchronized(this) { shared?.close(); shared = null }

        fun inMemory(context: Context): NisabaDatabase =
            Room.inMemoryDatabaseBuilder(context, NisabaDatabase::class.java).build()
    }
}
