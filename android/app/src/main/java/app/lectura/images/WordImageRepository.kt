package app.lectura.images

import android.util.Log
import app.lectura.data.WordImage
import app.lectura.data.WordImageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

sealed interface WordImageState {
    data object Loading : WordImageState
    data class Ready(val file: File, val author: String?, val pageUrl: String?) : WordImageState
    data object NotFound : WordImageState

    /** Пользователь выбрал «Убрать картинку» — ничего не показываем и не ищем. */
    data object Hidden : WordImageState
    data class Error(val message: String) : WordImageState
}

/**
 * Картинки к словам карточек: Room → локальный файл; если нет — Pixabay (по требованию), скачивание,
 * запись в Room. Повторно в сеть не ходим: найденная картинка дальше показывается офлайн.
 */
class WordImageRepository(
    private val dao: WordImageDao,
    private val api: PixabayApi,
    private val downloader: ImageDownloader,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val inFlight = HashMap<String, Deferred<WordImageState>>()
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Картинка слова; после «Другая картинка» / «Убрать картинку» выдаёт новое состояние. */
    fun image(word: String, translation: String? = null): Flow<WordImageState> = flow {
        val key = normalize(word)
        if (key.isEmpty()) {
            emit(WordImageState.NotFound)
            return@flow
        }
        val cached = cached(dao.get(key))
        if (cached != null) {
            emit(cached)
        } else {
            emit(WordImageState.Loading)
            emit(resolve(word, translation))
        }
        changes.filter { it == key }.collect { emit(cached(dao.get(key)) ?: WordImageState.NotFound) }
    }

    /** Найти и скачать (или вернуть уже скачанное). Параллельные вызовы для одного слова ждут один результат. */
    suspend fun resolve(word: String, translation: String? = null, force: Boolean = false): WordImageState {
        val key = normalize(word)
        if (key.isEmpty()) return WordImageState.NotFound
        val job = mutex.withLock {
            inFlight[key]?.takeIf { it.isActive } ?: scope.async { load(key, translation, force) }.also { inFlight[key] = it }
        }
        return try {
            job.await()
        } finally {
            mutex.withLock { if (inFlight[key] === job && job.isCompleted) inFlight.remove(key) }
        }
    }

    /** Фоновая предзагрузка (например, следующих 5 карточек). Ошибки не важны — попробуем при показе. */
    fun prefetch(words: List<Pair<String, String?>>) {
        if (!api.hasKey || words.isEmpty()) return
        scope.launch { for ((w, tr) in words) runCatching { resolve(w, tr) } }
    }

    /** «Другая картинка»: следующий кандидат (или новый поиск, если кандидатов нет / им больше 24 ч). */
    suspend fun next(word: String, translation: String? = null): WordImageState {
        val key = normalize(word)
        val rec = dao.get(key)
        if (!api.hasKey) return WordImageState.Error(PixabayApi.NO_KEY)
        val state = try {
            val fresh = rec?.let(::freshCandidates)
            if (fresh != null && rec.candidateIndex + 1 < fresh.size) {
                store(key, fresh, rec.candidateIndex + 1, rec.candidatesFetchedAt ?: now(), rec)
            } else {
                val hits = search(key, translation)
                val start = hits.indexOfFirst { it.id != rec?.pixabayId }
                if (start < 0) cached(rec) ?: WordImageState.NotFound else store(key, hits, start, now(), rec)
            }
        } catch (e: PixabayKeyException) {
            WordImageState.Error(e.message ?: PixabayApi.NO_KEY)
        } catch (e: IOException) {
            WordImageState.Error(OFFLINE)
        }
        changes.tryEmit(key)
        return state
    }

    /** «Убрать картинку»: файл удаляется, слово больше не ищем. */
    suspend fun disable(word: String) {
        val key = normalize(word)
        val rec = dao.get(key)
        rec?.localPath?.let { File(it).delete() }
        dao.upsert(
            (rec ?: WordImage(word = key, status = WordImage.DISABLED, updatedAt = now()))
                .copy(status = WordImage.DISABLED, localPath = null, updatedAt = now()),
        )
        changes.tryEmit(key)
    }

    /** Состояние без сети: готовый файл, «убрано» или недавнее «не найдено»; null — нужно искать. */
    private fun cached(rec: WordImage?): WordImageState? {
        rec ?: return null
        return when (rec.status) {
            WordImage.DISABLED -> WordImageState.Hidden
            WordImage.FOUND, WordImage.MANUAL ->
                rec.localPath?.let(::File)?.takeIf { it.isFile }?.let { WordImageState.Ready(it, rec.author, rec.pageUrl) }
            WordImage.NOT_FOUND -> if (now() - rec.updatedAt < NOT_FOUND_RETRY_MS) WordImageState.NotFound else null
            else -> null
        }
    }

    private suspend fun load(key: String, translation: String?, force: Boolean): WordImageState {
        val rec = dao.get(key)
        if (!force) cached(rec)?.let { return it }
        if (rec?.status == WordImage.DISABLED) return WordImageState.Hidden
        if (!api.hasKey) return WordImageState.Error(PixabayApi.NO_KEY)
        return try {
            // Запись есть, а файл пропал: берём того же кандидата, если ссылки ещё живы, иначе ищем заново.
            val fresh = rec?.takeIf { it.status == WordImage.FOUND }?.let(::freshCandidates)
            if (fresh != null && rec.candidateIndex in fresh.indices) {
                return store(key, fresh, rec.candidateIndex, rec.candidatesFetchedAt ?: now(), rec)
            }
            val hits = search(key, translation)
            if (hits.isEmpty()) {
                dao.upsert(WordImage(word = key, status = WordImage.NOT_FOUND, updatedAt = now()))
                WordImageState.NotFound
            } else {
                store(key, hits, 0, now(), rec)
            }
        } catch (e: PixabayKeyException) {
            WordImageState.Error(e.message ?: PixabayApi.NO_KEY)
        } catch (e: IOException) {
            runCatching { Log.w(TAG, "image for '$key': ${e.message}") }
            WordImageState.Error(OFFLINE)
        }
    }

    /** photo (es) → illustration (es) → перевод (ru). */
    private suspend fun search(key: String, translation: String?): List<PixabayHit> {
        api.search(key, "es", "photo").hits.takeIf { it.isNotEmpty() }?.let { return it }
        api.search(key, "es", "illustration").hits.takeIf { it.isNotEmpty() }?.let { return it }
        val tr = translation?.let(::cleanTranslation).orEmpty()
        if (tr.isNotEmpty()) api.search(tr, "ru", "photo").hits.takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    /** Скачать кандидата [index] (если ссылка умерла — следующего) и записать в Room; старый файл удаляется. */
    private suspend fun store(
        key: String,
        hits: List<PixabayHit>,
        index: Int,
        fetchedAt: Long,
        old: WordImage?,
    ): WordImageState {
        var last: IOException? = null
        for (i in index until minOf(hits.size, index + 3)) {
            val hit = hits[i]
            val file = try {
                downloader.save(hit.webformatURL, "${slug(key)}-${hit.id}.jpg")
            } catch (e: IOException) {
                last = e
                continue
            }
            old?.localPath?.takeIf { it != file.path }?.let { File(it).delete() }
            dao.upsert(
                WordImage(
                    word = key, status = WordImage.FOUND, localPath = file.path, pixabayId = hit.id,
                    pageUrl = hit.pageURL, author = hit.user, candidatesJson = json.encodeToString(hits),
                    candidatesFetchedAt = fetchedAt, candidateIndex = i, updatedAt = now(),
                ),
            )
            return WordImageState.Ready(file, hit.user, hit.pageURL)
        }
        throw last ?: IOException("нет картинок")
    }

    private fun freshCandidates(rec: WordImage): List<PixabayHit>? {
        val at = rec.candidatesFetchedAt ?: return null
        if (now() - at >= CANDIDATES_TTL_MS) return null
        return rec.candidatesJson?.let { runCatching { json.decodeFromString<List<PixabayHit>>(it) }.getOrNull() }
    }

    companion object {
        private const val TAG = "WordImages"
        const val OFFLINE = "Нет сети — картинка загрузится позже"
        const val NOT_FOUND_RETRY_MS = 7L * 24 * 3600 * 1000
        const val CANDIDATES_TTL_MS = 24L * 3600 * 1000
        private val ES = Locale.forLanguageTag("es")
        private val ARTICLE = Regex("""^(el|la|los|las|un|una|unos|unas)\s+""")

        /** «  La Manzana » → «manzana». Диакритика остаётся: año ≠ ano. */
        fun normalize(word: String): String =
            word.trim().lowercase(ES)
                .trim { !it.isLetterOrDigit() }
                .replace(Regex("""\s+"""), " ")
                .replace(ARTICLE, "")

        /** «есть, кушать (разг.)» → «есть». */
        fun cleanTranslation(ru: String): String =
            ru.replace(Regex("""\(.*?\)"""), " ").split(',', ';', '/').first().trim()

        /** Имя файла: без диакритики и пробелов (само слово хранится в Room как есть). */
        fun slug(key: String): String =
            Normalizer.normalize(key, Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "")
                .replace(Regex("""[^a-z0-9]+"""), "_").trim('_').ifEmpty { "w" }
    }
}
