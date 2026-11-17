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
    /** "word" или "phrase". */
    @ColumnInfo(defaultValue = "word") val kind: String = KIND_WORD,
    /** Ступень «лестницы сложности» (0 — новое, растёт с правильными ответами). */
    @ColumnInfo(defaultValue = "0") val stage: Int = 0,
    /** Когда слово последний раз вспоминали (повторение или практика), мс; 0 — неизвестно. */
    @ColumnInfo(defaultValue = "0") val lastReviewed: Long = 0,
    /** Сколько раз слово отрабатывали в дополнительной практике. */
    @ColumnInfo(defaultValue = "0") val practiceCount: Int = 0,
) {
    val isPhrase: Boolean get() = kind == KIND_PHRASE

    companion object {
        const val KIND_WORD = "word"
        const val KIND_PHRASE = "phrase"
    }
}

/** Статистика по дням (day = "yyyy-MM-dd" по локальному времени). */
@Entity(tableName = "daily_stats")
data class DailyStats(
    @PrimaryKey val day: String,
    @ColumnInfo(defaultValue = "0") val reviews: Int = 0,
    @ColumnInfo(defaultValue = "0") val correct: Int = 0,
    @ColumnInfo(defaultValue = "0") val sentencesRead: Int = 0,
    @ColumnInfo(defaultValue = "0") val speaking: Int = 0,
    @ColumnInfo(defaultValue = "0") val newWords: Int = 0,
    /** Ответов в дополнительной практике. */
    @ColumnInfo(defaultValue = "0") val practice: Int = 0,
    /** Снимок «памяти словаря» на этот день: средняя вероятность вспомнить, %. -1 — нет данных. */
    @ColumnInfo(defaultValue = "-1") val memoryPct: Int = -1,
    /** Снимок: слов «крепко» (интервал ≥ 21 дня) и всего слов. */
    @ColumnInfo(defaultValue = "0") val strongWords: Int = 0,
    @ColumnInfo(defaultValue = "0") val totalWords: Int = 0,
)

/** Прохождение упражнений главы: kind = read | questions | shadow | role; best — лучший результат, %. */
@Entity(tableName = "chapter_activity", primaryKeys = ["bookId", "chapter", "kind"])
data class ChapterActivity(
    val bookId: String,
    val chapter: Int,
    val kind: String,
    val best: Int,
    val count: Int,
    val updatedAt: Long,
) {
    companion object {
        const val READ = "read"
        const val QUESTIONS = "questions"
        const val SHADOW = "shadow"
        const val ROLE = "role"
    }
}

/** Прогресс по грамматической теме: интервальное повторение темы + точность. */
@Entity(tableName = "grammar_progress")
data class GrammarProgress(
    @PrimaryKey val topicId: String,
    val level: String,
    val sessions: Int = 0,
    val attempts: Int = 0,
    val correct: Int = 0,
    /** Последняя сессия, % */
    val lastPct: Int = 0,
    val bestPct: Int = 0,
    /** Ступень 0..6: растёт за сессии ≥ 80%, падает за < 60%. Освоено — от MASTERED_STAGE. */
    val stage: Int = 0,
    val due: Long = 0,
    val intervalDays: Int = 0,
    val lastPracticed: Long = 0,
    /** id упражнений, показанных в последних сессиях (через запятую) — чтобы не повторяться. */
    val recentIds: String = "",
) {
    val mastered: Boolean get() = stage >= MASTERED_STAGE

    companion object {
        const val MASTERED_STAGE = 3
    }
}

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
interface ActivityDao {
    @Query("SELECT * FROM chapter_activity WHERE bookId = :bookId")
    fun forBook(bookId: String): Flow<List<ChapterActivity>>

    @Query("SELECT * FROM chapter_activity")
    fun all(): Flow<List<ChapterActivity>>

    @Query("SELECT * FROM chapter_activity")
    suspend fun allOnce(): List<ChapterActivity>

    @Query("SELECT * FROM chapter_activity WHERE bookId = :bookId AND chapter = :chapter AND kind = :kind")
    suspend fun get(bookId: String, chapter: Int, kind: String): ChapterActivity?

    @Upsert
    suspend fun put(a: ChapterActivity)
}

@Dao
interface GrammarDao {
    @Query("SELECT * FROM grammar_progress")
    fun all(): Flow<List<GrammarProgress>>

    @Query("SELECT * FROM grammar_progress")
    suspend fun allOnce(): List<GrammarProgress>

    @Query("SELECT * FROM grammar_progress WHERE topicId = :id")
    suspend fun get(id: String): GrammarProgress?

    @Upsert
    suspend fun put(p: GrammarProgress)
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM daily_stats WHERE day = :day")
    suspend fun get(day: String): DailyStats?

    @Query("SELECT * FROM daily_stats ORDER BY day DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<DailyStats>>

    @Query("SELECT * FROM daily_stats")
    suspend fun allOnce(): List<DailyStats>

    @Upsert
    suspend fun put(stats: DailyStats)
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
    entities = [SavedWord::class, Progress::class, AppMeta::class, DailyStats::class, ChapterActivity::class, GrammarProgress::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun words(): WordDao
    abstract fun progress(): ProgressDao
    abstract fun meta(): MetaDao
    abstract fun stats(): StatsDao
    abstract fun activity(): ActivityDao
    abstract fun grammar(): GrammarDao
}

/** Ручные миграции (для изменений, которые AutoMigration не умеет). */
val MIGRATIONS = arrayOf<androidx.room.migration.Migration>()
