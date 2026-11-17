package app.lectura.data

import app.lectura.LecturaApp
import app.lectura.model.CURRICULUM_LEVELS
import app.lectura.model.GrammarLevel
import app.lectura.model.VocabList
import app.lectura.ui.streak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Покрытие темы словаря уровня. */
data class ThemeProgress(val id: String, val title: String, val total: Int, val known: Int, val learning: Int)

/**
 * Прогресс уровня CEFR = взвешенная доля освоенных единиц:
 * словарь уровня (знаю = 1, учу = 0.5) и грамматические темы (освоена = 1, в процессе — по ступени).
 */
data class LevelProgress(
    val level: String,
    val vocabTotal: Int,
    val vocabKnown: Int,
    val vocabLearning: Int,
    val grammarTotal: Int,
    val grammarMastered: Int,
    val grammarStarted: Int,
    /** 0..100 */
    val vocabPct: Int,
    val grammarPct: Int,
    val percent: Int,
    val themes: List<ThemeProgress>,
) {
    val hasContent: Boolean get() = vocabTotal > 0 || grammarTotal > 0

    companion object {
        /** С этого процента уровень считается покрытым (и будет открываться итоговый тест). */
        const val COVERED_PCT = 80
        const val VOCAB_WEIGHT = 0.5
        const val GRAMMAR_WEIGHT = 0.5
    }
}

/**
 * ЕДИНЫЙ объект со всей статистикой пользователя. Считается только в [ProgressService.compute]
 * из базы (Room) — это единственный источник правды; здесь ничего не хранится отдельно.
 */
data class UserProgress(
    val levels: List<LevelProgress>,
    /** Уровень, над которым сейчас идёт работа (первый не покрытый на 80%). */
    val currentLevel: String,
    /** Последний уровень, покрытый на 80% и больше (null — пока ни одного). */
    val coveredLevel: String?,
    // Словарь
    val words: Int,
    val phrases: Int,
    val knownWords: Int,
    val memoryNowPct: Int,
    val memoryIn30Pct: Int,
    // Грамматика
    val grammarMastered: Int,
    val grammarStarted: Int,
    val grammarTotal: Int,
    // Чтение
    val booksStarted: Int,
    val chaptersRead: Int,
    // Активность за всё время
    val exercises: Int,
    val correctPct: Int,
    val practice: Int,
    val speaking: Int,
    val sentencesListened: Int,
    val activeDays: Int,
    val streak: Int,
) {
    val current: LevelProgress? get() = levels.firstOrNull { it.level == currentLevel }
}

object ProgressService {
    suspend fun compute(app: LecturaApp = LecturaApp.instance): UserProgress = withContext(Dispatchers.Default) {
        val db = app.db
        val words = db.words().allOnce()
        val grammarProgress = db.grammar().allOnce().associateBy { it.topicId }
        val stats = db.stats().allOnce()
        val activity = db.activity().allOnce()
        val vocab = app.curriculum.vocab()
        val grammar = app.curriculum.grammar()

        val index = vocabIndex(words)
        val levels = CURRICULUM_LEVELS.map { l -> levelProgress(l, vocab[l], grammar[l], index, grammarProgress) }
        val withContent = levels.filter { it.hasContent }
        val current = withContent.firstOrNull { it.percent < LevelProgress.COVERED_PCT }?.level
            ?: withContent.lastOrNull()?.level ?: CURRICULUM_LEVELS.first()
        val covered = withContent.takeWhile { it.percent >= LevelProgress.COVERED_PCT }.lastOrNull()?.level

        val memory = Memory.summarize(words)
        val reviews = stats.sumOf { it.reviews }
        UserProgress(
            levels = levels,
            currentLevel = current,
            coveredLevel = covered,
            words = words.count { !it.isPhrase },
            phrases = words.count { it.isPhrase },
            knownWords = index.values.count { it == WordStatus.KNOWN },
            memoryNowPct = memory.nowPct,
            memoryIn30Pct = memory.in30Pct,
            grammarMastered = grammarProgress.values.count { it.mastered },
            grammarStarted = grammarProgress.values.count { it.sessions > 0 },
            grammarTotal = grammar.values.sumOf { it.topics.size },
            booksStarted = (activity.map { it.bookId } + db.progress().allOnce().map { it.bookId }).toSet().size,
            chaptersRead = activity.count { it.kind == ChapterActivity.READ },
            exercises = reviews,
            correctPct = if (reviews == 0) 0 else stats.sumOf { it.correct } * 100 / reviews,
            practice = stats.sumOf { it.practice },
            speaking = stats.sumOf { it.speaking },
            sentencesListened = stats.sumOf { it.sentencesRead },
            activeDays = stats.count { it.reviews > 0 || it.sentencesRead > 0 || it.newWords > 0 || it.speaking > 0 },
            streak = streak(stats),
        )
    }

    private fun levelProgress(
        level: String,
        vocab: VocabList?,
        grammar: GrammarLevel?,
        index: Map<String, WordStatus>,
        gp: Map<String, GrammarProgress>,
    ): LevelProgress {
        val themes = vocab?.themes.orEmpty().map { t ->
            val st = t.words.map { index[it.l.lowercase()] }
            ThemeProgress(t.id, t.title, t.words.size, st.count { it == WordStatus.KNOWN }, st.count { it == WordStatus.LEARNING })
        }
        val vTotal = themes.sumOf { it.total }
        val vKnown = themes.sumOf { it.known }
        val vLearning = themes.sumOf { it.learning }
        val vocabFrac = if (vTotal == 0) 0.0 else (vKnown + 0.5 * vLearning) / vTotal

        val topics = grammar?.topics.orEmpty()
        val gFrac = if (topics.isEmpty()) 0.0 else topics.sumOf { t ->
            val p = gp[t.id]
            when {
                p == null || p.sessions == 0 -> 0.0
                p.mastered -> 1.0
                else -> (p.stage.toDouble() / GrammarProgress.MASTERED_STAGE).coerceIn(0.1, 0.9)
            }
        } / topics.size

        val percent = when {
            vTotal > 0 && topics.isNotEmpty() -> LevelProgress.VOCAB_WEIGHT * vocabFrac + LevelProgress.GRAMMAR_WEIGHT * gFrac
            vTotal > 0 -> vocabFrac
            else -> gFrac
        }
        return LevelProgress(
            level = level,
            vocabTotal = vTotal,
            vocabKnown = vKnown,
            vocabLearning = vLearning,
            grammarTotal = topics.size,
            grammarMastered = topics.count { gp[it.id]?.mastered == true },
            grammarStarted = topics.count { (gp[it.id]?.sessions ?: 0) > 0 },
            vocabPct = (vocabFrac * 100).toInt(),
            grammarPct = (gFrac * 100).toInt(),
            percent = (percent * 100).toInt(),
            themes = themes,
        )
    }
}
