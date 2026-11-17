package app.lectura.data

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "words", indices = [Index(value = ["key"], unique = true)])
data class SavedWord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Слово в нижнем регистре — по нему не даём сохранить дубликат. */
    val key: String,
    val word: String,
    val lemma: String,
    val ru: String,
    val grammar: String,
    val sentence: String,
    val sentenceRu: String,
    val bookId: String,
    val createdAt: Long,
    // Интервальное повторение (SM-2)
    val due: Long,
    val intervalDays: Int = 0,
    val ease: Double = 2.5,
    val reps: Int = 0,
    val lapses: Int = 0,
    /** versionCode приложения, в котором запись последний раз сохранялась (0 = до версии 0.2.0). */
    @ColumnInfo(defaultValue = "0") val appVersion: Int = 0,
)

@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val bookId: String,
    val chapter: Int,
    val sentence: Int,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val appVersion: Int = 0,
)

/** Служебные пары ключ-значение: версии приложения, время последнего сохранения и т.п. */
@Entity(tableName = "meta")
data class AppMeta(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface WordDao {
    @Query("SELECT * FROM words ORDER BY createdAt DESC")
    fun all(): Flow<List<SavedWord>>

    @Query("SELECT key FROM words")
    fun keys(): Flow<List<String>>

    @Query("SELECT * FROM words")
    suspend fun allOnce(): List<SavedWord>

    @Query("SELECT * FROM words WHERE `key` = :key")
    suspend fun byKey(key: String): SavedWord?

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    @Query("SELECT * FROM words WHERE due <= :now ORDER BY due LIMIT 200")
    suspend fun due(now: Long): List<SavedWord>

    @Query("SELECT COUNT(*) FROM words WHERE due <= :now")
    fun dueCount(now: Long): Flow<Int>

    @Query("SELECT MIN(due) FROM words WHERE due > :now")
    suspend fun nextDue(now: Long): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(word: SavedWord): Long

    @Update
    suspend fun update(word: SavedWord)

    @Query("DELETE FROM words WHERE `key` = :key")
    suspend fun deleteKey(key: String)

    @Delete
    suspend fun delete(word: SavedWord)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): Progress?

    @Query("SELECT * FROM progress")
    fun all(): Flow<List<Progress>>

    @Query("SELECT * FROM progress")
    suspend fun allOnce(): List<Progress>

    @Upsert
    suspend fun put(progress: Progress)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM meta")
    fun all(): Flow<List<AppMeta>>

    @Upsert
    suspend fun put(vararg items: AppMeta)
}

/**
 * ПРАВИЛА ИЗМЕНЕНИЯ СХЕМЫ (данные пользователя должны переживать обновления):
 *  1. Любое изменение сущностей => version + 1 и новая запись в autoMigrations
 *     (или ручная Migration в [MIGRATIONS]). Только добавлять — не удалять и не переименовывать
 *     колонки без явной миграции. Новые колонки — с defaultValue.
 *  2. Файл схемы app/schemas/.../<version>.json коммитится; CI падает, если он не закоммичен.
 *  3. fallbackToDestructiveMigration НЕ использовать никогда.
 */
@Database(
    entities = [SavedWord::class, Progress::class, AppMeta::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun words(): WordDao
    abstract fun progress(): ProgressDao
    abstract fun meta(): MetaDao
}

/** Ручные миграции (для изменений, которые AutoMigration не умеет). */
val MIGRATIONS = arrayOf<androidx.room.migration.Migration>()
