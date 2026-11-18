package app.lectura.ui.grammar

import app.lectura.data.GrammarProgress
import app.lectura.data.WordStatus
import app.lectura.model.GrammarExercise
import app.lectura.review.TextMatch
import kotlin.random.Random

/** Упражнение из банка темы с его стабильным id ("<topicId>#<индекс в банке>"). */
data class PracticeItem(val id: String, val index: Int, val ex: GrammarExercise)

/** Результат проверки ответа. ACCENT — верно, но с ошибкой только в ударениях/ñ (засчитывается). */
enum class Verdict { RIGHT, ACCENT, WRONG }

/** Чистая логика практики (без Compose) — подбор упражнений и проверка ответов. */
object GrammarLogic {
    const val SESSION_SIZE = 10

    private val GAP = Regex("_{2,}")
    private val TILE_EDGE = charArrayOf('.', ',', ';', ':', '!', '?', '¡', '¿', '…', '"', '«', '»', '(', ')')

    fun exerciseId(topicId: String, index: Int) = "$topicId#$index"

    fun recentSet(p: GrammarProgress?): Set<String> =
        p?.recentIds?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /**
     * Оценка упражнения под словарь пользователя:
     *  +2 за каждую лемму, которую пользователь учит или знает (не больше трёх таких лемм);
     *  +1, если ровно одно незнакомое слово (новое слово в контексте), −1 — если незнакомых три и больше;
     *  −6, если упражнение было в последних сессиях; плюс случайный разброс 0..1.5.
     */
    fun score(ex: GrammarExercise, id: String, vocab: Map<String, WordStatus>, recent: Set<String>, rnd: Random): Double {
        val lemmas = ex.lemmas.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val familiar = lemmas.count { vocab.containsKey(it) }
        val unknown = lemmas.size - familiar
        var s = 2.0 * minOf(familiar, 3)
        if (unknown == 1) s += 1.0
        if (unknown >= 3) s -= 1.0
        if (id in recent) s -= 6.0
        return s + rnd.nextDouble() * 1.5
    }

    /**
     * Подборка из [n] упражнений: по убыванию оценки, но с чередованием типов — один тип не идёт
     * три раза подряд (если есть из чего выбрать), а уже набранные типы слегка штрафуются.
     */
    fun pick(
        topicId: String,
        bank: List<GrammarExercise>,
        vocab: Map<String, WordStatus>,
        recent: Set<String>,
        n: Int = SESSION_SIZE,
        rnd: Random = Random.Default,
    ): List<PracticeItem> {
        val pool = bank.mapIndexedNotNull { i, ex ->
            if (!isUsable(ex)) null
            else PracticeItem(exerciseId(topicId, i), i, ex) to score(ex, exerciseId(topicId, i), vocab, recent, rnd)
        }.toMutableList()
        val out = ArrayList<PracticeItem>()
        val typeCount = HashMap<String, Int>()
        while (out.size < n && pool.isNotEmpty()) {
            val lastTwo = out.takeLast(2).map { it.ex.type }
            val blocked = if (lastTwo.size == 2 && lastTwo[0] == lastTwo[1]) lastTwo[0] else null
            fun adjusted(p: Pair<PracticeItem, Double>) = p.second - 0.7 * (typeCount[p.first.ex.type] ?: 0)
            val best = pool.filter { it.first.ex.type != blocked }.maxByOrNull(::adjusted)
                ?: pool.maxBy(::adjusted)
            pool.remove(best)
            out += best.first
            typeCount[best.first.ex.type] = (typeCount[best.first.ex.type] ?: 0) + 1
        }
        return out
    }

    /** Упражнение можно показать: известный тип и есть ответ. */
    fun isUsable(ex: GrammarExercise): Boolean = when (ex.type) {
        "choice" -> ex.answer.isNotBlank() && ex.sentence.isNotBlank()
        "input" -> ex.answer.isNotBlank() && ex.sentence.isNotBlank()
        "order" -> orderTiles(ex.answer).size >= 2
        "transform" -> ex.answer.isNotBlank()
        else -> false
    }

    fun hasGap(sentence: String) = GAP.containsMatchIn(sentence)

    /** Части предложения вокруг пропусков. */
    fun gapParts(sentence: String): List<String> = sentence.split(GAP)

    /** Ответ для каждого пропуска: если пропусков несколько и ответ разделён «/», «...» или «…» — по частям. */
    fun gapFills(sentence: String, answer: String): List<String> {
        val gaps = GAP.findAll(sentence).count()
        if (gaps <= 1) return listOf(answer)
        val parts = answer.split(Regex("\\s*(?:/|\\.\\.\\.|…)\\s*")).filter { it.isNotBlank() }
        return if (parts.size == gaps) parts else List(gaps) { answer }
    }

    /** Предложение с заполненными пропусками. */
    fun filled(sentence: String, answer: String): String {
        if (!hasGap(sentence)) return sentence
        val parts = gapParts(sentence)
        val fills = gapFills(sentence, answer)
        return buildString {
            parts.forEachIndexed { i, p ->
                append(p)
                if (i < parts.size - 1) append(fills.getOrElse(i) { answer })
            }
        }
    }

    /** Полное правильное испанское предложение упражнения (для показа и озвучки). */
    fun fullSentence(ex: GrammarExercise): String = when (ex.type) {
        "choice", "input" -> filled(ex.sentence, ex.answer)
        else -> ex.answer
    }

    private val PAREN_HINT = Regex("\\s*\\([^)]*\\)")

    /**
     * Что произнести, когда упражнение появилось: части предложения между пропусками
     * (на месте пропуска будет пауза), без подсказок в скобках «(yo)». Пусто — нечего читать
     * (в «собери предложение» озвучка выдала бы ответ).
     */
    fun promptSpeech(ex: GrammarExercise): List<String> {
        val text = when (ex.type) {
            "choice", "input", "transform" -> ex.sentence
            else -> ""
        }
        if (text.isBlank()) return emptyList()
        return gapParts(text.replace(PAREN_HINT, "")).map { it.replace(Regex("\\s+"), " ") }
    }

    /** Варианты ответа: правильный обязательно среди них, без дублей. */
    fun choiceOptions(ex: GrammarExercise, rnd: Random = Random.Default): List<String> {
        val opts = ex.options.filter { it.isNotBlank() }.distinctBy { TextMatch.norm(it) }.toMutableList()
        if (opts.none { TextMatch.norm(it) == TextMatch.norm(ex.answer) }) opts += ex.answer
        return opts.shuffled(rnd)
    }

    fun isCorrectOption(ex: GrammarExercise, option: String): Boolean {
        val o = TextMatch.norm(option)
        return o == TextMatch.norm(ex.answer) || ex.accept.any { TextMatch.norm(it) == o }
    }

    /**
     * Сравнение без регистра и пунктуации, числа цифрами = словами («3» = «tres»);
     * ошибка только в ударениях — [Verdict.ACCENT].
     */
    fun check(typed: String, accepted: List<String>): Verdict {
        if (typed.isBlank()) return Verdict.WRONG
        val ok = accepted.filter { it.isNotBlank() }
        if (ok.any { TextMatch.same(typed, it) }) return Verdict.RIGHT
        if (ok.any { TextMatch.sameLoose(typed, it) }) return Verdict.ACCENT
        return Verdict.WRONG
    }

    /** Правильные ответы для input: вписанное слово или всё предложение целиком. */
    fun inputAccepted(ex: GrammarExercise): List<String> {
        val answers = (listOf(ex.answer) + ex.accept).filter { it.isNotBlank() }
        return answers + answers.map { filled(ex.sentence, it) }
    }

    fun transformAccepted(ex: GrammarExercise): List<String> = (listOf(ex.answer) + ex.accept).filter { it.isNotBlank() }

    /** Плитки для «собери предложение»: слова ответа без знаков препинания по краям. */
    fun orderTiles(answer: String): List<String> =
        TextMatch.tiles(answer).map { it.trim(*TILE_EDGE) }.filter { t -> t.any { it.isLetterOrDigit() } }

    /** Перемешать так, чтобы порядок не совпал с правильным (если это возможно). */
    fun shuffledTiles(tiles: List<String>, rnd: Random = Random.Default): List<String> {
        if (tiles.map { it.lowercase() }.distinct().size < 2) return tiles
        repeat(20) {
            val s = tiles.shuffled(rnd)
            if (s.map { it.lowercase() } != tiles.map { it.lowercase() }) return s
        }
        return tiles.reversed()
    }

    fun checkOrder(built: List<String>, ex: GrammarExercise): Verdict =
        check(built.joinToString(" "), transformAccepted(ex))
}
