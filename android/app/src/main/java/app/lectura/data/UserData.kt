package app.lectura.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import app.lectura.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppVersion {
    val code: Int = BuildConfig.VERSION_CODE
    val name: String = BuildConfig.VERSION_NAME

    /** 10203 -> "1.2.3" */
    fun nameOf(code: Int): String =
        if (code <= 0) "?" else "${code / 10000}.${code / 100 % 100}.${code % 100}"
}

object MetaKeys {
    const val FIRST_VERSION = "firstVersion"
    const val LAST_LAUNCH_VERSION = "lastLaunchVersion"
    const val LAST_SAVE_VERSION = "lastSaveVersion"
    const val LAST_SAVE_AT = "lastSaveAt"
}

/**
 * Все записи пользовательских данных идут через этот класс:
 * каждая запись помечается versionCode приложения, а в meta обновляется «последнее сохранение».
 */
class UserData(private val context: Context, private val db: AppDatabase) {

    suspend fun saveProgress(bookId: String, chapter: Int, sentence: Int) = db.withTransaction {
        db.progress().put(Progress(bookId, chapter, sentence, System.currentTimeMillis(), AppVersion.code))
        stamp()
    }

    suspend fun addWord(word: SavedWord) = db.withTransaction {
        val id = db.words().insert(word.copy(appVersion = AppVersion.code))
        if (id > 0) bumpStats { it.copy(newWords = it.newWords + 1) }
        stamp()
    }

    suspend fun updateWord(word: SavedWord) = db.withTransaction {
        db.words().update(word.copy(appVersion = AppVersion.code))
        stamp()
    }

    /**
     * Результат упражнения по слову/фразе: обновляет интервал (SM-2), ступень лестницы и статистику дня.
     * @param grade оценка Srs.AGAIN..Srs.EASY
     * @param stageDelta +1 за верный ответ в «объективном» упражнении, -1 за ошибку, 0 — без изменения
     * @return обновлённая запись
     */
    suspend fun recordReview(word: SavedWord, grade: Int, stageDelta: Int = 0): SavedWord {
        val upd = Srs.schedule(word, grade).let {
            it.copy(
                stage = (it.stage + stageDelta).coerceIn(0, MAX_STAGE),
                appVersion = AppVersion.code,
                lastReviewed = System.currentTimeMillis(),
            )
        }
        db.withTransaction {
            db.words().update(upd)
            bumpStats { it.copy(reviews = it.reviews + 1, correct = it.correct + if (grade >= Srs.HARD) 1 else 0) }
            stamp()
        }
        return upd
    }

    /**
     * Дополнительная практика (слово ещё не пора повторять по плану).
     * Верно — расписание не трогаем (повтор «раньше срока» не доказывает долгую память), только отмечаем,
     * что слово вспоминали. Ошибка — слово забыто: возвращаем его в обычное повторение (как «Снова»).
     */
    suspend fun recordPractice(word: SavedWord, correct: Boolean): SavedWord {
        val now = System.currentTimeMillis()
        val upd = if (correct) {
            word.copy(lastReviewed = now, practiceCount = word.practiceCount + 1, appVersion = AppVersion.code)
        } else {
            Srs.schedule(word, Srs.AGAIN).copy(
                stage = (word.stage - 1).coerceAtLeast(0),
                lastReviewed = now,
                practiceCount = word.practiceCount + 1,
                appVersion = AppVersion.code,
            )
        }
        db.withTransaction {
            db.words().update(upd)
            bumpStats { it.copy(practice = it.practice + 1, correct = it.correct + if (correct) 1 else 0, reviews = it.reviews + 1) }
            stamp()
        }
        return upd
    }

    /** Записать в статистику дня текущее состояние памяти словаря (для графика). */
    suspend fun snapshotMemory() = withContext(Dispatchers.IO) {
        val words = db.words().allOnce()
        if (words.isEmpty()) return@withContext
        val sum = Memory.summarize(words)
        bumpStats {
            it.copy(
                memoryPct = sum.nowPct,
                strongWords = sum.byLevel[Memory.Level.STRONG] ?: 0,
                totalWords = sum.total,
            )
        }
    }

    /**
     * «Я это слово знаю»: сохраняем сразу на средней ступени — оно не считается новым,
     * попадает в практику, а повтор по плану — через неделю.
     */
    suspend fun markKnown(word: SavedWord): Unit = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = db.words().byKey(word.key)
        val known = (existing ?: word).copy(
            stage = maxOf(existing?.stage ?: 0, KNOWN_STAGE),
            intervalDays = maxOf(existing?.intervalDays ?: 0, KNOWN_INTERVAL_DAYS),
            reps = maxOf(existing?.reps ?: 0, 2),
            due = maxOf(existing?.due ?: 0, now + KNOWN_INTERVAL_DAYS * 24L * 60 * 60 * 1000),
            lastReviewed = now,
            appVersion = AppVersion.code,
        )
        if (existing == null) db.words().insert(known) else db.words().update(known)
        stamp()
    }

    /**
     * Итог сессии по грамматической теме. ≥ 80% — тема закрепляется (ступень +1, интервал растёт),
     * 60–79% — без изменения, < 60% — ступень −1 и тема снова «пора повторить».
     */
    suspend fun recordGrammarSession(
        topicId: String,
        level: String,
        correct: Int,
        total: Int,
        shownIds: List<String>,
    ): GrammarProgress = db.withTransaction {
        val now = System.currentTimeMillis()
        val cur = db.grammar().get(topicId) ?: GrammarProgress(topicId, level)
        val pct = if (total == 0) 0 else correct * 100 / total
        val stage = when {
            pct >= 80 -> cur.stage + 1
            pct < 60 -> cur.stage - 1
            else -> cur.stage
        }.coerceIn(0, MAX_STAGE)
        val interval = when {
            pct < 60 -> 0
            pct < 80 -> maxOf(1, cur.intervalDays)
            cur.intervalDays == 0 -> 1
            else -> minOf(120, cur.intervalDays * 2 + 1)
        }
        val recent = (shownIds + cur.recentIds.split(',').filter { it.isNotBlank() }).distinct().take(60)
        val upd = cur.copy(
            level = level,
            sessions = cur.sessions + 1,
            attempts = cur.attempts + total,
            correct = cur.correct + correct,
            lastPct = pct,
            bestPct = maxOf(cur.bestPct, pct),
            stage = stage,
            intervalDays = interval,
            due = now + interval * 24L * 60 * 60 * 1000,
            lastPracticed = now,
            recentIds = recent.joinToString(","),
        )
        db.grammar().put(upd)
        bumpStats { it.copy(reviews = it.reviews + total, correct = it.correct + correct) }
        stamp()
        upd
    }

    /** Результат упражнения главы (лучший процент сохраняется). */
    suspend fun recordChapterActivity(bookId: String, chapter: Int, kind: String, percent: Int) = db.withTransaction {
        val cur = db.activity().get(bookId, chapter, kind)
        db.activity().put(
            ChapterActivity(
                bookId, chapter, kind,
                best = maxOf(cur?.best ?: 0, percent.coerceIn(0, 100)),
                count = (cur?.count ?: 0) + 1,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        stamp()
    }

    /** Упражнение без привязки к слову (ответ в диалоге, вопрос по главе). */
    suspend fun recordFreeExercise(correct: Boolean) = db.withTransaction {
        bumpStats { it.copy(reviews = it.reviews + 1, correct = it.correct + if (correct) 1 else 0) }
        stamp()
    }

    suspend fun recordSpeaking() = db.withTransaction {
        bumpStats { it.copy(speaking = it.speaking + 1) }
        stamp()
    }

    suspend fun recordSentenceRead() = bumpStats { it.copy(sentencesRead = it.sentencesRead + 1) }

    private suspend fun bumpStats(change: (DailyStats) -> DailyStats) {
        val day = today()
        db.stats().put(change(db.stats().get(day) ?: DailyStats(day)))
    }

    private suspend fun stamp() {
        db.meta().put(
            AppMeta(MetaKeys.LAST_SAVE_VERSION, AppVersion.code.toString()),
            AppMeta(MetaKeys.LAST_SAVE_AT, System.currentTimeMillis().toString()),
        )
    }

    /**
     * Вызывается при каждом запуске. Структурные миграции делает Room при открытии БД,
     * здесь — шаги «по данным» при переходе между версиями приложения.
     */
    suspend fun onAppStart() = withContext(Dispatchers.IO) {
        val meta = db.meta()
        val last = meta.get(MetaKeys.LAST_LAUNCH_VERSION)?.toIntOrNull()
        if (meta.get(MetaKeys.FIRST_VERSION) == null) {
            // Данные без meta появились до 0.2.0 (в 0.1.0 таблицы meta не было).
            val hadData = db.words().count() > 0 || db.progress().allOnce().isNotEmpty()
            meta.put(AppMeta(MetaKeys.FIRST_VERSION, (if (hadData) 100 else AppVersion.code).toString()))
        }
        val from = last ?: 0
        if (from < AppVersion.code) {
            Log.i("UserData", "upgrade data from ${AppVersion.nameOf(from)} to ${AppVersion.name}")
            // Здесь добавлять шаги вида: if (from < 30000) { ... }
        }
        meta.put(AppMeta(MetaKeys.LAST_LAUNCH_VERSION, AppVersion.code.toString()))
        snapshotMemory()
    }

    suspend fun info(): DataInfo = withContext(Dispatchers.IO) {
        val m = db.meta()
        DataInfo(
            appVersion = AppVersion.name,
            firstVersion = m.get(MetaKeys.FIRST_VERSION)?.toIntOrNull() ?: 0,
            lastSaveVersion = m.get(MetaKeys.LAST_SAVE_VERSION)?.toIntOrNull() ?: 0,
            lastSaveAt = m.get(MetaKeys.LAST_SAVE_AT)?.toLongOrNull() ?: 0,
            words = db.words().count(),
            books = db.progress().allOnce().size,
            dbVersion = db.openHelper.readableDatabase.version,
        )
    }

    // ------------------------------------------------------------ резервная копия

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val backup = Backup(
            format = Backup.FORMAT,
            appVersionName = AppVersion.name,
            appVersionCode = AppVersion.code,
            exportedAt = System.currentTimeMillis(),
            progress = db.progress().allOnce().map {
                ProgressDto(it.bookId, it.chapter, it.sentence, it.updatedAt, it.appVersion)
            },
            words = db.words().allOnce().map {
                WordDto(
                    it.key, it.word, it.lemma, it.ru, it.grammar, it.sentence, it.sentenceRu, it.bookId,
                    it.createdAt, it.due, it.intervalDays, it.ease, it.reps, it.lapses, it.appVersion,
                    it.kind, it.stage, it.lastReviewed, it.practiceCount,
                )
            },
            grammar = db.grammar().allOnce().map {
                GrammarDto(
                    it.topicId, it.level, it.sessions, it.attempts, it.correct, it.lastPct, it.bestPct,
                    it.stage, it.due, it.intervalDays, it.lastPracticed, it.recentIds,
                )
            },
            activity = db.activity().allOnce().map {
                ActivityDto(it.bookId, it.chapter, it.kind, it.best, it.count, it.updatedAt)
            },
            stats = db.stats().allOnce().map {
                StatsDto(
                    it.day, it.reviews, it.correct, it.sentencesRead, it.speaking, it.newWords,
                    it.practice, it.memoryPct, it.strongWords, it.totalWords,
                )
            },
        )
        context.contentResolver.openOutputStream(uri, "wt")!!.use {
            it.write(json.encodeToString(Backup.serializer(), backup).toByteArray())
        }
        backup.words.size
    }

    /** Слияние: прогресс берётся более свежий, слова — добавляются те, которых нет. */
    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        val b = json.decodeFromString(Backup.serializer(), text)
        require(b.format <= Backup.FORMAT) {
            "Копия сделана более новой версией приложения (${b.appVersionName}). Сначала обновите приложение."
        }
        var words = 0
        var books = 0
        db.withTransaction {
            b.progress.forEach { p ->
                val cur = db.progress().get(p.bookId)
                if (cur == null || p.updatedAt > cur.updatedAt) {
                    db.progress().put(Progress(p.bookId, p.chapter, p.sentence, p.updatedAt, p.appVersion))
                    books++
                }
            }
            b.words.forEach { w ->
                if (db.words().byKey(w.key) == null) {
                    db.words().insert(
                        SavedWord(
                            key = w.key, word = w.word, lemma = w.lemma, ru = w.ru, grammar = w.grammar,
                            sentence = w.sentence, sentenceRu = w.sentenceRu, bookId = w.bookId,
                            createdAt = w.createdAt, due = w.due, intervalDays = w.intervalDays, ease = w.ease,
                            reps = w.reps, lapses = w.lapses, appVersion = w.appVersion,
                            kind = w.kind, stage = w.stage, lastReviewed = w.lastReviewed, practiceCount = w.practiceCount,
                        ),
                    )
                    words++
                }
            }
            b.grammar.forEach { g ->
                val cur = db.grammar().get(g.topicId)
                if (cur == null || g.lastPracticed > cur.lastPracticed) {
                    db.grammar().put(
                        GrammarProgress(
                            g.topicId, g.level, g.sessions, g.attempts, g.correct, g.lastPct, g.bestPct,
                            g.stage, g.due, g.intervalDays, g.lastPracticed, g.recentIds,
                        ),
                    )
                }
            }
            b.activity.forEach { a ->
                val cur = db.activity().get(a.bookId, a.chapter, a.kind)
                if (cur == null || a.best > cur.best) {
                    db.activity().put(ChapterActivity(a.bookId, a.chapter, a.kind, a.best, a.count, a.updatedAt))
                }
            }
            b.stats.forEach { st ->
                if (db.stats().get(st.day) == null) {
                    db.stats().put(
                        DailyStats(
                            st.day, st.reviews, st.correct, st.sentencesRead, st.speaking, st.newWords,
                            st.practice, st.memoryPct, st.strongWords, st.totalWords,
                        ),
                    )
                }
            }
            stamp()
        }
        ImportResult(words, books, b.appVersionName)
    }
}

data class DataInfo(
    val appVersion: String,
    val firstVersion: Int,
    val lastSaveVersion: Int,
    val lastSaveAt: Long,
    val words: Int,
    val books: Int,
    val dbVersion: Int,
)

data class ImportResult(val words: Int, val books: Int, val fromVersion: String)

/**
 * Формат файла резервной копии. Правила совместимости:
 * новые поля — только с значениями по умолчанию; при несовместимом изменении — FORMAT + 1.
 */
@Serializable
data class Backup(
    val format: Int = FORMAT,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val exportedAt: Long = 0,
    val progress: List<ProgressDto> = emptyList(),
    val words: List<WordDto> = emptyList(),
    val stats: List<StatsDto> = emptyList(),
    val activity: List<ActivityDto> = emptyList(),
    val grammar: List<GrammarDto> = emptyList(),
) {
    companion object {
        const val FORMAT = 1
    }
}

@Serializable
data class ProgressDto(
    val bookId: String,
    val chapter: Int = 0,
    val sentence: Int = 0,
    val updatedAt: Long = 0,
    val appVersion: Int = 0,
)

@Serializable
data class WordDto(
    val key: String,
    val word: String,
    val lemma: String = "",
    val ru: String = "",
    val grammar: String = "",
    val sentence: String = "",
    val sentenceRu: String = "",
    val bookId: String = "",
    val createdAt: Long = 0,
    val due: Long = 0,
    val intervalDays: Int = 0,
    val ease: Double = 2.5,
    val reps: Int = 0,
    val lapses: Int = 0,
    val appVersion: Int = 0,
    val kind: String = SavedWord.KIND_WORD,
    val stage: Int = 0,
    val lastReviewed: Long = 0,
    val practiceCount: Int = 0,
)

@Serializable
data class StatsDto(
    val day: String,
    val reviews: Int = 0,
    val correct: Int = 0,
    val sentencesRead: Int = 0,
    val speaking: Int = 0,
    val newWords: Int = 0,
    val practice: Int = 0,
    val memoryPct: Int = -1,
    val strongWords: Int = 0,
    val totalWords: Int = 0,
)

@Serializable
data class GrammarDto(
    val topicId: String,
    val level: String = "",
    val sessions: Int = 0,
    val attempts: Int = 0,
    val correct: Int = 0,
    val lastPct: Int = 0,
    val bestPct: Int = 0,
    val stage: Int = 0,
    val due: Long = 0,
    val intervalDays: Int = 0,
    val lastPracticed: Long = 0,
    val recentIds: String = "",
)

@Serializable
data class ActivityDto(
    val bookId: String,
    val chapter: Int,
    val kind: String,
    val best: Int = 0,
    val count: Int = 0,
    val updatedAt: Long = 0,
)

const val MAX_STAGE = 6
const val KNOWN_STAGE = 3
const val KNOWN_INTERVAL_DAYS = 7

fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
