package app.lectura.data

import android.content.Context
import app.lectura.model.CURRICULUM_LEVELS
import app.lectura.model.GrammarLevel
import app.lectura.model.GrammarTopic
import app.lectura.model.VocabList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Учебная программа из assets/curriculum (только чтение, кэшируется в памяти). */
class CurriculumRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var vocab: Map<String, VocabList>? = null
    private var grammar: Map<String, GrammarLevel>? = null

    suspend fun vocab(): Map<String, VocabList> = mutex.withLock {
        vocab ?: withContext(Dispatchers.IO) {
            CURRICULUM_LEVELS.mapNotNull { l -> read<VocabList>("vocab_$l.json")?.let { l to it } }.toMap()
        }.also { vocab = it }
    }

    suspend fun grammar(): Map<String, GrammarLevel> = mutex.withLock {
        grammar ?: withContext(Dispatchers.IO) {
            CURRICULUM_LEVELS.mapNotNull { l -> read<GrammarLevel>("grammar_$l.json")?.let { l to it } }.toMap()
        }.also { grammar = it }
    }

    suspend fun topic(id: String): GrammarTopic? = grammar().values.flatMap { it.topics }.firstOrNull { it.id == id }

    suspend fun levelOfTopic(id: String): String? =
        grammar().entries.firstOrNull { (_, g) -> g.topics.any { it.id == id } }?.key

    private inline fun <reified T> read(name: String): T? = runCatching {
        context.assets.open("curriculum/$name").use { json.decodeFromString<T>(it.readBytes().decodeToString()) }
    }.getOrNull()
}
