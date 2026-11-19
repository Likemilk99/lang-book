package app.lectura.data

import app.lectura.LecturaApp
import app.lectura.model.CURRICULUM_LEVELS
import app.lectura.model.GrammarLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** День засчитывается в серию, если в нём было хоть какое-то занятие. */
fun DailyStats.isActive(): Boolean = reviews > 0 || sentencesRead > 0 || newWords > 0 || speaking > 0

/** Книга, которую читали последней, и глава для разговорной практики. */
data class CurrentBook(
    val id: String,
    val title: String,
    /** Глава, на которой остановились в чтении. */
    val chapter: Int,
    val chapterTitle: String,
    /** Глава для ролевой игры / повтора — последняя дочитанная, иначе текущая. */
    val speakChapter: Int,
    val speakChapterTitle: String,
    /** В главе для практики есть реплики персонажей → ролевая игра, иначе повтор за диктором. */
    val speakHasDialogue: Boolean,
)

/** Следующая грамматическая тема: пора повторить ([review]) или новая. */
data class GrammarNext(val id: String, val title: String, val level: String, val review: Boolean)

data class PlanInput(
    val today: DailyStats,
    /** Слов и фраз, которые пора повторять. */
    val due: Int,
    val book: CurrentBook?,
    val grammar: GrammarNext?,
    val grammarDoneToday: Boolean,
)

enum class StepKind { REVIEW, LISTEN, SPEAK, GRAMMAR }

/**
 * Шаг плана на сегодня. [route] — куда вести по нажатию: маршрут навигации
 * (`reader/…`, `speak/…`, `grammar/…`) или вкладка (`review`, `talk`, `library`).
 */
data class PlanStep(
    val kind: StepKind,
    val title: String,
    val detail: String,
    val progress: Int,
    /** 0 — у шага нет счётчика (например, грамматика: одна сессия). */
    val goal: Int,
    val done: Boolean,
    val route: String,
)

/**
 * План «Сегодня» на ~20 минут: повторение → чтение/слушание → говорение → грамматика.
 * Цели скромные, чтобы план можно было закрывать каждый день; считается только из Room.
 */
object DailyPlan {
    /** Один подход повторения (= [app.lectura.review.SessionBatch.SIZE]). */
    const val REVIEW_GOAL = 20
    /** Предложений прочитано или прослушано — примерно одна глава A1. */
    const val LISTEN_GOAL = 30
    /** Фраз, сказанных вслух (повтор, ролевая игра, упражнения). */
    const val SPEAK_GOAL = 10

    fun build(input: PlanInput): List<PlanStep> {
        val t = input.today
        val book = input.book
        return listOf(
            reviewStep(t.reviews, input.due),
            PlanStep(
                kind = StepKind.LISTEN,
                title = "Читать и слушать",
                detail = book?.let { "${it.title} · гл. ${it.chapter + 1}" } ?: "Выберите книгу в библиотеке",
                progress = t.sentencesRead,
                goal = LISTEN_GOAL,
                done = t.sentencesRead >= LISTEN_GOAL,
                route = book?.let { "reader/${it.id}" } ?: "library",
            ),
            PlanStep(
                kind = StepKind.SPEAK,
                title = when {
                    book == null -> "Говорить"
                    book.speakHasDialogue -> "Говорить: ролевая игра"
                    else -> "Говорить: повтор за диктором"
                },
                detail = book?.let { "Гл. ${it.speakChapter + 1} · ${it.speakChapterTitle}" }
                    ?: "Разговорник: послушайте и повторите фразы",
                progress = t.speaking,
                goal = SPEAK_GOAL,
                done = t.speaking >= SPEAK_GOAL,
                route = book?.let { "speak/${it.id}/${it.speakChapter}/${if (it.speakHasDialogue) "role" else "shadow"}" }
                    ?: "phrasebook/A0",
            ),
            grammarStep(input.grammar, input.grammarDoneToday),
        )
    }

    private fun reviewStep(reviews: Int, due: Int): PlanStep {
        val done = due == 0 || reviews >= REVIEW_GOAL
        val detail = when {
            due > 0 && reviews >= REVIEW_GOAL -> "Подход сделан · ещё к повторению: $due"
            due > 0 -> "К повторению: $due"
            reviews > 0 -> "Всё повторено"
            else -> "Сейчас нечего повторять — сохраняйте слова и фразы из книг"
        }
        return PlanStep(
            kind = StepKind.REVIEW,
            title = "Повторение",
            detail = detail,
            progress = reviews,
            goal = if (due == 0 && reviews == 0) 0 else REVIEW_GOAL,
            done = done,
            route = "review",
        )
    }

    private fun grammarStep(next: GrammarNext?, doneToday: Boolean): PlanStep = PlanStep(
        kind = StepKind.GRAMMAR,
        title = "Грамматика",
        detail = when {
            next == null -> "Все темы пройдены"
            next.review -> "${next.title} · пора повторить"
            else -> "${next.title} · новая тема ${next.level}"
        },
        progress = if (doneToday) 1 else 0,
        goal = 0,
        done = doneToday || next == null,
        route = next?.let { "grammar/${it.id}" } ?: "grammar",
    )

    /** Следующая тема: сначала те, что пора повторить, потом первая не начатая по порядку уровней. */
    fun nextGrammar(levels: Map<String, GrammarLevel>, progress: Map<String, GrammarProgress>, now: Long): GrammarNext? {
        val ordered = CURRICULUM_LEVELS.mapNotNull { l -> levels[l] }.flatMap { lv -> lv.topics.map { lv.level to it } }
        ordered.filter { (_, t) -> progress[t.id]?.let { it.sessions > 0 && it.due <= now } == true }
            .minByOrNull { (_, t) -> progress.getValue(t.id).due }
            ?.let { (l, t) -> return GrammarNext(t.id, t.title, l, review = true) }
        return ordered.firstOrNull { (_, t) -> (progress[t.id]?.sessions ?: 0) == 0 }
            ?.let { (l, t) -> GrammarNext(t.id, t.title, l, review = false) }
    }

    suspend fun load(app: LecturaApp = LecturaApp.instance, now: Long = System.currentTimeMillis()): PlanInput =
        withContext(Dispatchers.IO) {
            val db = app.db
            val day = today()
            val grammarProgress = db.grammar().allOnce().associateBy { it.topicId }
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            PlanInput(
                today = db.stats().get(day) ?: DailyStats(day),
                due = db.words().dueCountNow(now),
                book = currentBook(app),
                grammar = runCatching { nextGrammar(app.curriculum.grammar(), grammarProgress, now) }.getOrNull(),
                grammarDoneToday = grammarProgress.values.any { it.lastPracticed >= startOfDay },
            )
        }

    /** Последняя открытая книга (по времени сохранения позиции), если она ещё есть в библиотеке. */
    suspend fun currentBook(app: LecturaApp = LecturaApp.instance): CurrentBook? {
        // Список книг заполняется лениво (обычно при открытии библиотеки) — главная может открыться раньше.
        if (app.books.books.value.isEmpty()) app.books.refresh()
        val ids = app.books.books.value.map { it.id }.toSet()
        val p = app.db.progress().allOnce().filter { it.bookId in ids }.maxByOrNull { it.updatedAt } ?: return null
        val book = runCatching { app.books.load(p.bookId) }.getOrNull() ?: return null
        val chapter = p.chapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
        val lastRead = app.db.activity().allOnce()
            .filter { it.bookId == p.bookId && it.kind == ChapterActivity.READ && it.chapter < book.chapters.size }
            .maxByOrNull { it.updatedAt }?.chapter
        val speak = lastRead ?: chapter
        val speakCh = book.chapters.getOrNull(speak) ?: return null
        return CurrentBook(
            id = book.id,
            title = book.title,
            chapter = chapter,
            chapterTitle = book.chapters.getOrNull(chapter)?.title.orEmpty(),
            speakChapter = speak,
            speakChapterTitle = speakCh.title,
            speakHasDialogue = speakCh.sentences.any { !it.sp.isNullOrBlank() },
        )
    }
}
