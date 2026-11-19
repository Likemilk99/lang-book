package app.lectura.data

import app.lectura.LecturaApp
import app.lectura.model.ScenarioLine
import kotlin.random.Random

/** Фраза для тренажёра слуха; [word] — если она из повторения (ошибка вернёт её в повторение). */
data class ListenItem(
    val es: String,
    val ru: String,
    val clip: String?,
    val word: SavedWord? = null,
    /** Ситуация (сценарий): «отвлекающие» варианты берутся сначала из неё — чтобы не угадывалось по теме. */
    val group: String = "",
)

/** «Понять на слух»: фраза и варианты смысла по-русски. */
data class ListenQuestion(val item: ListenItem, val options: List<String>, val answer: Int)

/**
 * Материал тренажёра слуха. Диктант — фразы из повторения (сначала уже знакомые), при нехватке — разговорник A0.
 * «Понять на слух» — реплики собеседников из сценариев (продавцы, врачи, чиновники — то, что вам будут говорить).
 */
object Listening {
    const val SESSION = 10

    suspend fun dictation(app: LecturaApp = LecturaApp.instance, n: Int = SESSION, rnd: Random = Random.Default): List<ListenItem> {
        val pb = app.phrasebook
        pb.all()
        val saved = app.db.words().allOnce()
            .filter { it.isPhrase && it.word.trim().contains(' ') }
            .let { list -> list.filter { it.reps > 0 }.shuffled(rnd) + list.filter { it.reps == 0 }.shuffled(rnd) }
            .map { ListenItem(it.word, it.ru, if (it.ref.isBlank()) null else pb.clipFor(it.ref, it.word), it) }
        val extra = pb.level("A0")?.themes.orEmpty().flatMap { it.phrases }.shuffled(rnd)
            .filter { it.es.contains(' ') }
            .map { ListenItem(it.es, it.ru, pb.clip(it.id)) }
        val seen = HashSet<String>()
        return (saved + extra).filter { seen.add(it.es.lowercase()) }.take(n)
    }

    suspend fun comprehension(app: LecturaApp = LecturaApp.instance, n: Int = SESSION, rnd: Random = Random.Default): List<ListenQuestion> {
        val repo = app.scenarios
        val lines = repo.all().flatMap { s ->
            s.dialogs.flatMap { d ->
                d.lines.mapIndexedNotNull { i, l ->
                    if (l.who == ScenarioLine.ME) null
                    else ListenItem(l.es, l.ru, repo.clip(ScenarioRepository.lineClip(s.id, d.id, i)), group = s.id)
                }
            } + s.understand.mapIndexed { i, u ->
                ListenItem(u.es, u.ru, repo.clip(ScenarioRepository.understandClip(s.id, i)), group = s.id)
            }
        }
        return questions(lines, n, rnd)
    }

    /**
     * Вопросы с тремя «отвлекающими» переводами других фраз — сначала из той же ситуации (так смысл
     * не угадать по теме), при нехватке — из остальных. Разные и не совпадающие с правильным.
     */
    fun questions(pool: List<ListenItem>, n: Int, rnd: Random = Random.Default): List<ListenQuestion> {
        val distinct = pool.distinctBy { it.es.lowercase() }
        return distinct.shuffled(rnd).take(n).map { item ->
            val same = distinct.filter { it.group == item.group }.shuffled(rnd)
            val other = distinct.filter { it.group != item.group }.shuffled(rnd)
            val wrong = (same + other).map { it.ru }.filter { it != item.ru }.distinct().take(3)
            val options = (wrong + item.ru).shuffled(rnd)
            ListenQuestion(item, options, options.indexOf(item.ru))
        }
    }
}
