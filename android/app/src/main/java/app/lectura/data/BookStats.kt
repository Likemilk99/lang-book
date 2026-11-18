package app.lectura.data

import app.lectura.model.Book
import app.lectura.model.Chapter

/** Статус слова для подсветки в книге и для статистики. */
enum class WordStatus { LEARNING, KNOWN }

/**
 * Карта «слово или начальная форма → статус». Ищем по обоим, чтобы слово, выученное в одной форме
 * (tengo), считалось знакомым и в другой (tienes → tener), в том числе в других книгах.
 */
fun vocabIndex(words: List<SavedWord>): Map<String, WordStatus> {
    val map = HashMap<String, WordStatus>()
    for (w in words) {
        if (w.isPhrase) continue
        val st = if (w.stage >= KNOWN_STAGE || w.intervalDays >= KNOWN_INTERVAL_DAYS) WordStatus.KNOWN else WordStatus.LEARNING
        for (k in listOf(w.key, w.lemma.lowercase())) {
            if (k.isBlank()) continue
            if (map[k] != WordStatus.KNOWN) map[k] = st
        }
    }
    return map
}

data class ChapterStat(
    val index: Int,
    val title: String,
    val read: Boolean,
    /** Лучший результат, % (null — ещё не делали). */
    val questions: Int?,
    val hasQuestions: Boolean,
    val shadow: Int?,
    val role: Int?,
    val hasRole: Boolean,
    val phrasesSaved: Int,
    val phrasesTotal: Int,
    val vocabTotal: Int,
    val vocabKnown: Int,
    val vocabInDict: Int,
) {
    /** Упражнения главы: прочитать, вопросы, повторение за диктором, ролевая игра (если есть). */
    val exercisesTotal: Int get() = 2 + (if (hasQuestions) 1 else 0) + (if (hasRole) 1 else 0)
    val exercisesDone: Int
        get() = listOf(
            read,
            (shadow ?: 0) >= DONE_PCT,
            hasQuestions && (questions ?: 0) >= DONE_PCT,
            hasRole && (role ?: 0) >= DONE_PCT,
        ).count { it }

    companion object {
        /** Упражнение считается закрытым с этого результата. */
        const val DONE_PCT = 60
    }
}

data class BookStat(
    val chapters: List<ChapterStat>,
    /** Уникальные слова книги (по начальной форме, без имён собственных). */
    val vocabTotal: Int,
    val vocabInDict: Int,
    val vocabKnown: Int,
) {
    val chaptersRead: Int get() = chapters.count { it.read }
    val exercisesDone: Int get() = chapters.sumOf { it.exercisesDone }
    val exercisesTotal: Int get() = chapters.sumOf { it.exercisesTotal }
    val vocabPct: Int get() = if (vocabTotal == 0) 0 else vocabInDict * 100 / vocabTotal
    val knownPct: Int get() = if (vocabTotal == 0) 0 else vocabKnown * 100 / vocabTotal

    /** Общий прогресс книги: чтение, упражнения и словарь поровну. */
    val overallPct: Int
        get() {
            val read = if (chapters.isEmpty()) 0.0 else chaptersRead.toDouble() / chapters.size
            val ex = if (exercisesTotal == 0) 0.0 else exercisesDone.toDouble() / exercisesTotal
            val voc = if (vocabTotal == 0) 0.0 else vocabInDict.toDouble() / vocabTotal
            return ((read + ex + voc) / 3 * 100).toInt()
        }
}

object BookStats {
    fun compute(book: Book, words: List<SavedWord>, activity: List<ChapterActivity>, progress: Progress?): BookStat {
        val index = vocabIndex(words)
        val phraseKeys = words.filter { it.isPhrase }.map { it.key }.toSet()
        val names = book.characters.map { it.name.lowercase() }.toSet()
        val act = activity.filter { it.bookId == book.id }.associateBy { it.chapter to it.kind }
        val allLemmas = HashSet<String>()

        val chapters = book.chapters.mapIndexed { i, ch ->
            val lemmas = lemmasOf(ch, names)
            allLemmas += lemmas
            fun best(kind: String) = act[i to kind]?.best
            ChapterStat(
                index = i,
                title = ch.title,
                read = act.containsKey(i to ChapterActivity.READ) || (progress != null && progress.chapter > i),
                questions = best(ChapterActivity.QUESTIONS),
                hasQuestions = ch.questions.isNotEmpty(),
                shadow = best(ChapterActivity.SHADOW),
                role = best(ChapterActivity.ROLE),
                hasRole = ch.sentences.any { it.sp != null },
                phrasesSaved = ch.phrases.count { it.es.lowercase().trim() in phraseKeys },
                phrasesTotal = ch.phrases.size,
                vocabTotal = lemmas.size,
                vocabKnown = lemmas.count { index[it] == WordStatus.KNOWN },
                vocabInDict = lemmas.count { it in index },
            )
        }
        return BookStat(
            chapters = chapters,
            vocabTotal = allLemmas.size,
            vocabInDict = allLemmas.count { it in index },
            vocabKnown = allLemmas.count { index[it] == WordStatus.KNOWN },
        )
    }

    /** Начальные формы слов главы без имён собственных. */
    private fun lemmasOf(ch: Chapter, names: Set<String>): Set<String> {
        val out = HashSet<String>()
        for (s in ch.sentences) for (t in s.tokens) {
            if (!t.isWord || t.r != null) continue
            val lemma = (t.l ?: t.t).lowercase().trim()
            if (lemma.isBlank() || lemma in names) continue
            val proper = t.t.firstOrNull()?.isUpperCase() == true && t.l?.firstOrNull()?.isUpperCase() == true
            if (proper) continue
            out += lemma
        }
        return out
    }
}

/** Метка сложности текста по доле знакомых слов (исследования чтения: 98% — свободно, 95% — с усилием). */
enum class Difficulty(val label: String, val emoji: String) {
    EASY("Легко", "🟢"),
    ASSISTED("С подсказками", "🟡"),
    HARD("Трудно", "🟠"),
    TOO_EARLY("Рано", "🔴"),
    ;

    companion object {
        fun of(coveragePct: Int): Difficulty = when {
            coveragePct >= 95 -> EASY
            coveragePct >= 90 -> ASSISTED
            coveragePct >= 80 -> HARD
            else -> TOO_EARLY
        }
    }
}

object TextCoverage {
    /**
     * Слова, которые пользователь скорее всего знает: его словарь (по форме и начальной форме),
     * словари уже пройденных уровней учебной программы и служебные слова A1 (тема «basic»).
     */
    fun knownLemmas(
        index: Map<String, WordStatus>,
        curriculum: Map<String, app.lectura.model.VocabList>,
        levelsBelow: List<String>,
    ): Set<String> {
        val out = HashSet<String>(index.keys)
        levelsBelow.forEach { l -> curriculum[l]?.themes?.forEach { t -> t.words.forEach { out += it.l.lowercase() } } }
        curriculum["A1"]?.themes?.filter { it.id.contains("basic") || it.id.contains("grammar") }
            ?.forEach { t -> t.words.forEach { out += it.l.lowercase() } }
        return out
    }

    /** Доля знакомых словоупотреблений в тексте, % (имена собственные и числа не считаем). */
    fun percent(book: Book, known: Set<String>): Int {
        val names = book.characters.map { it.name.lowercase() }.toSet()
        var total = 0
        var hit = 0
        for (ch in book.chapters) for (s in ch.sentences) for (t in s.tokens) {
            if (!t.isWord || t.t.any { it.isDigit() }) continue
            val lemma = (t.l ?: t.t).lowercase()
            if (lemma in names) continue
            if (t.t.firstOrNull()?.isUpperCase() == true && t.l?.firstOrNull()?.isUpperCase() == true) continue
            total++
            if (lemma in known || t.key in known) hit++
        }
        return if (total == 0) 100 else hit * 100 / total
    }
}
