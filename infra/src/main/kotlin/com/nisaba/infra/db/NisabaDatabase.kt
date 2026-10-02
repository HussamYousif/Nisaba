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
        "SELECT n.id, n.name, n.updatedAt, n.coverSeed, n.documentId IS NOT NULL AS hasDocument, " +
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
}

@Database(
    entities = [NotebookDto::class, PageDto::class, DocumentDto::class, ExchangeDto::class],
    version = 2,
    exportSchema = true,
)
abstract class NisabaDatabase : RoomDatabase() {
    abstract fun notebooks(): NotebookDao
    abstract fun exchanges(): ExchangeDao

    companion object {
        @Volatile private var shared: NisabaDatabase? = null

        /** One connection pool for the whole process (notebooks and exchanges share it). */
        fun shared(context: Context): NisabaDatabase = shared ?: synchronized(this) {
            shared ?: Room.databaseBuilder(context.applicationContext, NisabaDatabase::class.java, "nisaba.db")
                .addMigrations(MIGRATION_1_2).build().also { shared = it }
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
