package app.lectura.data

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
)

@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val bookId: String,
    val chapter: Int,
    val sentence: Int,
    val updatedAt: Long,
)

@Dao
interface WordDao {
    @Query("SELECT * FROM words ORDER BY createdAt DESC")
    fun all(): Flow<List<SavedWord>>

    @Query("SELECT key FROM words")
    fun keys(): Flow<List<String>>

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

    @Query("DELETE FROM words WHERE key = :key")
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

    @Upsert
    suspend fun put(progress: Progress)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

@Database(entities = [SavedWord::class, Progress::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun words(): WordDao
    abstract fun progress(): ProgressDao
}
