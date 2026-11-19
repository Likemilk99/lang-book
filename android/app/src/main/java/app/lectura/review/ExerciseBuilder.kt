package app.lectura.review

import app.lectura.data.SavedWord
import app.lectura.model.Phrase
import app.lectura.model.ReplyItem
import kotlin.random.Random

/** Слово из книги пользователя — источник вариантов-«отвлекалок». */
data class BookToken(val form: String, val ru: String, val g: String)

/** Материал для построения упражнений. */
class Pool(
    /** Все сохранённые слова и фразы (не только те, что к повторению). */
    val items: List<SavedWord>,
    /** Уникальные слова из книг пользователя. */
    val tokens: List<BookToken>,
    /** Упражнения-диалоги из глав, до которых пользователь дошёл. */
    val replies: List<ReplyItem>,
    /** Фразы разговорника по id (SavedWord.ref) — для шаблонов. */
    val phrases: Map<String, Phrase> = emptyMap(),
) {
    companion object {
        val EMPTY = Pool(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Выбор и построение упражнений.
 *
 * Лестница сложности (по `SavedWord.stage`). Говорить — с первой встречи: сначала повторяя за диктором,
 * потом самому.
 *  0–2 → послушать и повторить / на слух / карточка / пропуск с выбором (узнавание + произношение)
 *  3–4 → сказать вслух / по шаблону / пропуск с вводом / собрать фразу / карточка RU→ES (вспоминание)
 *  5–6 → сказать вслух / по шаблону / пропуск с вводом / собрать фразу (продуктивная речь)
 * У фраз упражнения с речью встречаются вдвое чаще: фразы учим, чтобы говорить.
 *
 * Тип выбирается случайно среди доступных для этого слова, никогда не тот же, что был только что,
 * и не тот, на котором слово только что провалили.
 */
class ExerciseBuilder(
    private val pool: Pool,
    private val speechAvailable: Boolean,
    private val direction: Direction,
    private val rnd: Random = Random.Default,
) {
    fun forWord(w: SavedWord, last: ExerciseType?, avoid: ExerciseType?, cardsOnly: Boolean): Exercise {
        if (cardsOnly) return Exercise.Flash(w, spanishFront(w.stage))
        val ladder = ladder(w.stage, w.isPhrase)
        val everything = WORD_TYPES.shuffled(rnd)
        val attempts = listOf(
            ladder.filter { it != last && it != avoid },
            ladder.filter { it != last },
            everything.filter { it != last && it != avoid },
            everything.filter { it != last },
        )
        for (candidates in attempts) {
            candidates.shuffled(rnd).firstNotNullOfOrNull { build(w, it) }?.let { return it }
        }
        return Exercise.Flash(w, spanishFront(w.stage))
    }

    fun forReply(r: ReplyItem): Exercise.DialogReply {
        val order = r.options.indices.shuffled(rnd)
        return Exercise.DialogReply(r, order.map { r.options[it] }, order.indexOf(r.answer))
    }

    /** Типы для ступени; повтор типа в списке = он выпадает чаще. */
    internal fun ladder(stage: Int, phrase: Boolean): List<ExerciseType> {
        val speak = if (phrase) 2 else 1
        fun rep(t: ExerciseType) = List(speak) { t }
        return when {
            stage <= 0 -> rep(ExerciseType.LISTEN_REPEAT) +
                listOf(ExerciseType.LISTEN_CHOICE, ExerciseType.FLASH, ExerciseType.CLOZE_CHOICE)
            stage <= 2 -> rep(ExerciseType.LISTEN_REPEAT) +
                listOf(ExerciseType.CLOZE_CHOICE, ExerciseType.LISTEN_CHOICE, ExerciseType.FLASH)
            stage <= 4 -> rep(ExerciseType.SAY_ES) + rep(ExerciseType.PATTERN_SAY) +
                listOf(ExerciseType.CLOZE_INPUT, ExerciseType.BUILD_SENTENCE, ExerciseType.FLASH)
            else -> rep(ExerciseType.SAY_ES) + rep(ExerciseType.PATTERN_SAY) +
                listOf(ExerciseType.CLOZE_INPUT, ExerciseType.BUILD_SENTENCE)
        }
    }

    /** Новые слова — испанской стороной вперёд, уверенные — русской, в середине — как в настройках. */
    private fun spanishFront(stage: Int): Boolean = when {
        stage <= 0 -> true
        stage in 3..4 -> false
        else -> when (direction) {
            Direction.ES_RU -> true
            Direction.RU_ES -> false
            Direction.MIXED -> rnd.nextBoolean()
        }
    }

    /** null — упражнение этого типа для слова построить нельзя. */
    private fun build(w: SavedWord, type: ExerciseType): Exercise? = when (type) {
        ExerciseType.FLASH -> Exercise.Flash(w, spanishFront(w.stage))
        ExerciseType.SAY_ES -> if (speechAvailable && w.ru.isNotBlank()) Exercise.SayEs(w) else null
        ExerciseType.LISTEN_REPEAT -> if (speechAvailable) Exercise.ListenRepeat(w) else null
        ExerciseType.PATTERN_SAY -> patternSay(w)
        ExerciseType.CLOZE_CHOICE -> cloze(w)?.let { c ->
            val wrong = formDistractors(w, c.answer).map { TextMatch.matchCase(it, c.answer) }
            if (wrong.size < 2) null else Exercise.ClozeChoice(w, c, (wrong + c.answer).shuffled(rnd))
        }
        ExerciseType.CLOZE_INPUT -> cloze(w)?.let { Exercise.ClozeInput(w, it) }
        ExerciseType.LISTEN_CHOICE -> {
            val wrong = ruDistractors(w)
            if (w.ru.isBlank() || wrong.size < 2) null else Exercise.ListenChoice(w, (wrong + w.ru).shuffled(rnd))
        }
        ExerciseType.BUILD_SENTENCE -> {
            val target = TextMatch.tiles(w.sentence)
            if (target.size !in 3..12 || w.sentenceRu.isBlank()) {
                null
            } else {
                var tiles = target.shuffled(rnd)
                repeat(5) { if (tiles == target) tiles = target.shuffled(rnd) }
                Exercise.BuildSentence(w, target, tiles)
            }
        }
        ExerciseType.DIALOG_REPLY -> null
    }

    /** Пропуск в предложении-примере; если пропуском стало бы всё предложение — упражнения нет. */
    private fun cloze(w: SavedWord): Cloze? =
        TextMatch.cloze(w.sentence, w.word)?.takeIf { it.before.isNotBlank() || it.after.isNotBlank() }

    /** Другой вариант шаблона фразы из разговорника (не сама фраза — её и так учим). */
    private fun patternSay(w: SavedWord): Exercise.PatternSay? {
        if (!speechAvailable || w.ref.isBlank()) return null
        val p = pool.phrases[w.ref]?.takeIf { it.hasPattern } ?: return null
        val k = p.slots.indices.filter { p.slots[it].es != w.word }.randomOrNull(rnd) ?: return null
        val v = p.slots[k]
        return Exercise.PatternSay(w, p.pattern, k, v.es, v.ru)
    }

    /** Испанские формы того же рода: похожие сохранённые → слова из книг → любые другие. */
    private fun formDistractors(w: SavedWord, answer: String): List<String> {
        val cls = TextMatch.grammarClass(w.grammar)
        val others = pool.items.filter { it.id != w.id }
        val sameKind = others.filter { it.kind == w.kind }
        fun similarity(o: SavedWord): Int = when {
            o.grammar.isNotBlank() && o.grammar == w.grammar -> 2
            cls.isNotBlank() && TextMatch.grammarClass(o.grammar) == cls -> 1
            w.isPhrase -> 1 // у фраз грамматики обычно нет — любая другая фраза подходит
            else -> 0
        }
        val similar = sameKind.map { it to similarity(it) }.filter { it.second > 0 }
            .shuffled(rnd).sortedByDescending { it.second }.map { TextMatch.plainForm(it.first.word, it.first.lemma) }
        val fromBooks = if (w.isPhrase || cls.isBlank()) {
            emptyList()
        } else {
            pool.tokens.filter { TextMatch.grammarClass(it.g) == cls }.shuffled(rnd).map { it.form }
        }
        val rest = (sameKind.shuffled(rnd) + others.filter { it.kind != w.kind }.shuffled(rnd))
            .map { TextMatch.plainForm(it.word, it.lemma) }
        return distinct(similar + fromBooks + rest, exclude = listOf(answer, w.word))
    }

    /** Русские переводы: другие сохранённые того же рода → остальные → слова из книг. */
    private fun ruDistractors(w: SavedWord): List<String> {
        val others = pool.items.filter { it.id != w.id && it.ru.isNotBlank() }
        val candidates = others.filter { it.kind == w.kind }.shuffled(rnd).map { it.ru } +
            others.filter { it.kind != w.kind }.shuffled(rnd).map { it.ru } +
            pool.tokens.shuffled(rnd).take(200).map { it.ru }
        return distinct(candidates, exclude = listOf(w.ru))
    }

    /** Уникальные (без учёта регистра, пунктуации и ударений) варианты, не совпадающие с ответом. */
    private fun distinct(candidates: List<String>, exclude: List<String>, limit: Int = 3): List<String> {
        val seen = exclude.map { TextMatch.loose(it) }.toMutableSet()
        return candidates.asSequence().filter { c ->
            val k = TextMatch.loose(c)
            c.isNotBlank() && k.isNotBlank() && seen.add(k)
        }.take(limit).toList()
    }

    companion object {
        private val WORD_TYPES = ExerciseType.entries.filter { it != ExerciseType.DIALOG_REPLY }
    }
}
