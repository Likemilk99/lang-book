package app.lectura.data

import android.content.Context
import android.util.Log
import app.lectura.model.Scenario
import app.lectura.model.ScenarioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Шаги сценария по порядку. [key] хранится в БД — не менять. */
enum class ScenarioStep(val key: String, val emoji: String, val title: String) {
    PHRASES("phrases", "💬", "Ключевые фразы"),
    LISTEN("listen", "🎧", "Послушать разговор"),
    ROLE_ME("role_me", "🗣", "Ваша роль"),
    ROLE_THEM("role_them", "🎭", "Роль собеседника"),
    TWIST("twist", "🌀", "Неожиданный поворот"),
    UNDERSTAND("understand", "👂", "Понять ответ"),
    ANSWER("answer", "💭", "Ответить своими словами"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Состояние сценария по результатам шагов (best, %). */
data class ScenarioState(val steps: List<ScenarioStep>, val best: Map<ScenarioStep, Int>) {
    fun done(s: ScenarioStep) = (best[s] ?: 0) >= DONE_PCT
    val doneCount: Int get() = steps.count(::done)
    /** «Могу»: все шаги пройдены. */
    val canDo: Boolean get() = steps.isNotEmpty() && steps.all(::done)
    val started: Boolean get() = best.isNotEmpty()
    /** Следующий непройденный шаг (null — всё пройдено). */
    val next: ScenarioStep? get() = steps.firstOrNull { !done(it) }

    companion object {
        /** С этого результата шаг засчитан (как упражнения главы). */
        const val DONE_PCT = 60

        /** Шаги, которые есть у сценария (без «понять» / «ответить», если для них нет заданий). */
        fun stepsOf(s: Scenario): List<ScenarioStep> = ScenarioStep.entries.filter {
            when (it) {
                ScenarioStep.TWIST -> s.dialogs.size > 1
                ScenarioStep.UNDERSTAND -> s.understand.isNotEmpty()
                ScenarioStep.ANSWER -> s.prompts.isNotEmpty()
                else -> true
            }
        }

        fun of(s: Scenario, progress: List<ScenarioProgress>): ScenarioState = ScenarioState(
            stepsOf(s),
            progress.filter { it.scenarioId == s.id }.mapNotNull { p -> ScenarioStep.of(p.step)?.let { it to p.best } }.toMap(),
        )
    }
}

/** Сценарии из assets/scenarios/ и их озвучка (встроено в приложение). */
class ScenarioRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cache: List<Scenario>? = null

    private val clips: Set<String> by lazy {
        runCatching { context.assets.list(AUDIO_DIR).orEmpty().toSet() }.getOrDefault(emptySet())
    }

    suspend fun all(): List<Scenario> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            runCatching {
                json.decodeFromString<ScenarioFile>(context.assets.open(FILE).use { it.readBytes().decodeToString() }).scenarios
            }.onFailure { Log.w("Scenarios", "load", it) }.getOrDefault(emptyList())
        }.also { cache = it }
    }

    suspend fun get(id: String): Scenario? = all().firstOrNull { it.id == id }

    /** Файл озвучки по имени без расширения ("cafe_a_3"), или null. */
    fun clip(name: String): String? = "$name.m4a".takeIf { it in clips }?.let { "$AUDIO_DIR/$it" }

    companion object {
        const val FILE = "scenarios/scenarios.json"
        const val AUDIO_DIR = "scenarios/audio"

        fun lineClip(scenarioId: String, dialogId: String, index: Int) = "${scenarioId}_${dialogId}_$index"
        fun understandClip(scenarioId: String, index: Int) = "${scenarioId}_u$index"
        fun promptClip(scenarioId: String, index: Int) = "${scenarioId}_q$index"
        fun answerClip(scenarioId: String, index: Int) = "${scenarioId}_q${index}a"
    }
}
