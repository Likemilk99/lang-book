package app.lectura.data

import android.content.Context
import android.util.Log
import app.lectura.model.Phrase
import app.lectura.model.PhraseTheme
import app.lectura.model.Phrasebook
import app.lectura.model.ScenarioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Разговорники из assets/phrasebook/ (A0, A1…) и их озвучка, всё встроено в приложение.
 * Индекс фраз по id включает и ключевые фразы сценариев (их озвучка лежит там же).
 */
class PhrasebookRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var books: List<Phrasebook>? = null
    private var byId: Map<String, Phrase> = emptyMap()
    private var themeOf: Map<String, PhraseTheme> = emptyMap()

    /** Имена файлов озвучки (без папки) — список читается один раз. */
    private val clips: Set<String> by lazy {
        runCatching { context.assets.list(AUDIO_DIR).orEmpty().toSet() }.getOrDefault(emptySet())
    }

    suspend fun all(): List<Phrasebook> = mutex.withLock {
        books ?: withContext(Dispatchers.IO) {
            val names = context.assets.list(DIR).orEmpty().filter { it.startsWith("phrasebook_") && it.endsWith(".json") }
            names.mapNotNull { name ->
                runCatching {
                    json.decodeFromString<Phrasebook>(context.assets.open("$DIR/$name").use { it.readBytes().decodeToString() })
                }.onFailure { Log.w("Phrasebook", name, it) }.getOrNull()
            }.sortedBy { it.level }
        }.also { list ->
            books = list
            // Ключевые фразы сценариев — в том же индексе (шаблоны, озвучка, связь со словарём).
            val scenarioPhrases = withContext(Dispatchers.IO) {
                runCatching {
                    json.decodeFromString<ScenarioFile>(
                        context.assets.open(ScenarioRepository.FILE).use { it.readBytes().decodeToString() },
                    ).scenarios.flatMap { it.phrases }
                }.getOrDefault(emptyList())
            }
            byId = (scenarioPhrases + list.flatMap { b -> b.themes.flatMap { it.phrases } }).associateBy { it.id }
            themeOf = list.flatMap { b -> b.themes.flatMap { t -> t.phrases.map { it.id to t } } }.toMap()
        }
    }

    suspend fun level(level: String): Phrasebook? = all().firstOrNull { it.level == level }

    suspend fun phrase(id: String): Phrase? {
        all()
        return byId[id]
    }

    suspend fun themeOfPhrase(id: String): PhraseTheme? {
        all()
        return themeOf[id]
    }

    /** «Мои фразы» (pipeline/personal.py): фразы и их озвучка в files/personal/ на телефоне. */
    @Volatile private var extra: Map<String, Phrase> = emptyMap()
    @Volatile private var extraClip: (String) -> String? = { null }

    fun registerExtra(phrases: List<Phrase>, clip: (String) -> String?) {
        extra = phrases.associateBy { it.id }
        extraClip = clip
    }

    /**
     * Файл озвучки фразы ([variant] — номер варианта шаблона, null — сама фраза), или null.
     * Путь в assets или абсолютный путь к файлу («Мои фразы»).
     */
    fun clip(phraseId: String, variant: Int? = null): String? {
        // Вариант 0 — это сама фраза (отдельного файла у него нет).
        val name = if (variant == null || variant == 0) phraseId else "${phraseId}_$variant"
        return if ("$name.m4a" in clips) "$AUDIO_DIR/$name.m4a" else extraClip(name)
    }

    /** Озвучка испанского текста фразы: сама фраза или один из её вариантов; null — такого текста у фразы нет. */
    fun clipFor(phrase: Phrase, es: String): String? {
        fun same(a: String) = a.trim().equals(es.trim(), ignoreCase = true)
        if (same(phrase.es)) return clip(phrase.id)
        val k = phrase.slots.indexOfFirst { same(it.es) }
        return if (k >= 0) clip(phrase.id, k) else null
    }

    /** То же по id фразы (SavedWord.ref). Работает после первой загрузки [all]. */
    fun clipFor(ref: String, es: String): String? = (byId[ref] ?: extra[ref])?.let { clipFor(it, es) }

    companion object {
        const val DIR = "phrasebook"
        const val AUDIO_DIR = "phrasebook/audio"
        /** SavedWord.bookId у фраз из разговорника. */
        const val BOOK_ID = "phrasebook"
    }
}
