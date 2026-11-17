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
        db.words().insert(word.copy(appVersion = AppVersion.code))
        stamp()
    }

    suspend fun updateWord(word: SavedWord) = db.withTransaction {
        db.words().update(word.copy(appVersion = AppVersion.code))
        stamp()
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
                        ),
                    )
                    words++
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
)
