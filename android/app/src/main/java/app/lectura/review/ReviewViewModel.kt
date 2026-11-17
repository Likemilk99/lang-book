package app.lectura.review

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectura.LecturaApp
import app.lectura.data.MAX_STAGE
import app.lectura.data.SavedWord
import app.lectura.data.Srs
import app.lectura.model.ReplyItem
import app.lectura.speech.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Сессия «Повторение»: очередь слов/фраз и диалогов, выбор упражнений, запись результатов. */
class ReviewViewModel(private val app: LecturaApp) : ViewModel() {
    private val player get() = app.player

    var loaded by mutableStateOf(false)
        private set
    var started by mutableStateOf(false)
        private set
    var nextDue by mutableStateOf<Long?>(null)
        private set

    /** Слова и фразы, которые пора повторить (на экране настройки). */
    var due by mutableStateOf<List<SavedWord>>(emptyList())
        private set
    var dialogsAvailable by mutableStateOf(0)
        private set
    var speechAvailable by mutableStateOf(false)
        private set

    // Настройки
    var mode by mutableStateOf(ReviewMode.of(app.prefs.reviewMode))
    var direction by mutableStateOf(Direction.of(app.prefs.reviewDirection))
    var autoWord by mutableStateOf(app.prefs.reviewAutoWord)
    var autoExample by mutableStateOf(app.prefs.reviewAutoExample)

    // Сессия
    private val queue = mutableStateListOf<Entry>()
    var current by mutableStateOf<Task?>(null)
        private set

    /** Ответ на текущее автоматически проверяемое упражнение (null — ещё не ответили). */
    var answer by mutableStateOf<Answer?>(null)
        private set
    val results = mutableStateListOf<ExerciseResult>()

    val remaining: Int get() = queue.size + if (current != null && answer == null) 1 else 0
    val progress: Float
        get() {
            val total = results.size + remaining
            return if (total == 0) 1f else results.size.toFloat() / total
        }

    private var pool = Pool.EMPTY
    private lateinit var builder: ExerciseBuilder
    private var lastType: ExerciseType? = null
    private var nextId = 0

    /** Последнее записанное в БД состояние слова (для повторов внутри сессии). */
    private val latest = HashMap<Long, SavedWord>()

    /** Записи в БД идут строго по очереди. */
    private var writeJob: Job? = null

    fun load() = viewModelScope.launch {
        loaded = false
        started = false
        current = null
        answer = null
        queue.clear()
        results.clear()
        latest.clear()
        val now = System.currentTimeMillis()
        val words = app.db.words()
        due = words.due(now).shuffled()
        nextDue = words.nextDue(now)
        speechAvailable = runCatching { SpeechInput(app).isAvailable() }.getOrDefault(false)
        val items = words.allOnce()
        // Материал из книг: варианты ответов и диалоги из пройденных глав.
        pool = runCatching { loadBookMaterial(items) }
            .onFailure { Log.w("Review", "book material", it) }
            .getOrDefault(Pool(items, emptyList(), emptyList()))
        dialogsAvailable = pool.replies.size
        loaded = true
    }

    private suspend fun loadBookMaterial(items: List<SavedWord>): Pool = withContext(Dispatchers.IO) {
        val repo = app.books
        repo.refresh()
        val reached = app.db.progress().allOnce().associate { it.bookId to it.chapter }
        val tokens = LinkedHashMap<String, BookToken>()
        val replies = ArrayList<ReplyItem>()
        for (info in repo.books.value) {
            val book = runCatching { repo.load(info.id) }.getOrNull() ?: continue
            book.chapters.forEachIndexed { i, ch ->
                for (s in ch.sentences) for (t in s.tokens) {
                    val ru = t.ru ?: continue
                    val g = t.g ?: continue
                    if (!t.isWord || ru.isBlank()) continue
                    val form = TextMatch.plainForm(t.t, t.l)
                    tokens.getOrPut(form.lowercase()) { BookToken(form, ru, g) }
                }
                val upTo = reached[book.id]
                if (upTo != null && i <= upTo) {
                    replies += ch.replies.filter { it.options.size >= 2 && it.answer in it.options.indices }
                }
            }
        }
        Pool(items, tokens.values.toList(), replies)
    }

    fun start() {
        app.prefs.reviewMode = mode.key
        app.prefs.reviewDirection = direction.key
        app.prefs.reviewAutoWord = autoWord
        app.prefs.reviewAutoExample = autoExample
        builder = ExerciseBuilder(pool, speechAvailable, direction)
        lastType = null
        queue.clear()
        queue.addAll(due.map { Entry.Word(it) })
        if (mode == ReviewMode.MIXED && queue.isNotEmpty()) {
            pool.replies.shuffled().take(MAX_DIALOGS).forEach { r ->
                queue.add(Random.nextInt(1, queue.size + 1), Entry.Dialog(r))
            }
        }
        results.clear()
        started = true
        advance()
    }

    private fun advance() {
        answer = null
        val e = queue.removeFirstOrNull()
        if (e == null) {
            current = null
            viewModelScope.launch {
                writeJob?.join()
                nextDue = app.db.words().nextDue(System.currentTimeMillis())
            }
            return
        }
        val ex = when (e) {
            is Entry.Word -> builder.forWord(e.word, lastType, e.avoid, cardsOnly = mode == ReviewMode.CARDS)
            is Entry.Dialog -> builder.forReply(e.reply)
        }
        lastType = ex.type
        current = Task(nextId++, e, ex)
    }

    /** Кнопка «Дальше»: сразу обрываем диктора и показываем следующее. */
    fun next() {
        player.stopSpeech()
        advance()
    }

    /** Итог автоматически проверяемого упражнения. */
    fun submit(a: Answer) {
        val task = current ?: return
        if (answer != null) return
        player.stopSpeech()
        answer = a
        results += ExerciseResult(task.exercise.type, a.outcome.correct)
        when (val ex = task.exercise) {
            is Exercise.ForWord -> record(ex, a.outcome.grade, a.outcome.stageDelta)
            is Exercise.DialogReply -> write { app.userData.recordFreeExercise(a.outcome.correct) }
        }
        speakFeedback(task.exercise)
    }

    /** Оценка карточки кнопкой: записываем и сразу переходим дальше. */
    fun gradeFlash(g: Int) {
        val task = current ?: return
        val ex = task.exercise as? Exercise.Flash ?: return
        player.stopSpeech()
        results += ExerciseResult(ExerciseType.FLASH, g >= Srs.HARD)
        val delta = when (g) {
            Srs.AGAIN -> -1
            Srs.HARD -> 0
            else -> +1
        }
        record(ex, g, delta)
        advance()
    }

    private fun record(ex: Exercise.ForWord, grade: Int, delta: Int) {
        val w = ex.word
        write { latest[w.id] = app.userData.recordReview(latest[w.id] ?: w, grade, delta) }
        if (grade == Srs.AGAIN) {
            // Вернётся через несколько упражнений и другим типом. Копия — как её запишет recordReview.
            val again = Srs.schedule(w, grade).copy(stage = (w.stage + delta).coerceIn(0, MAX_STAGE))
            val avoid = if (mode == ReviewMode.CARDS) null else ex.type
            queue.add(minOf(queue.size, REQUEUE_GAP + Random.nextInt(3)), Entry.Word(again, avoid))
        }
    }

    private fun write(block: suspend () -> Unit) {
        val prev = writeJob
        writeJob = viewModelScope.launch {
            prev?.join()
            runCatching { block() }.onFailure { Log.e("Review", "save failed", it) }
        }
    }

    // ------------------------------------------------------------ озвучка
    // Правило: только испанский и только в момент, когда он появляется на экране.

    /** Упражнение только что показано. */
    fun onAppear(task: Task) {
        when (val ex = task.exercise) {
            is Exercise.Flash -> if (ex.spanishFront) speakWord(ex.word)
            is Exercise.ListenChoice -> player.speakOnce(ex.word.word)
            is Exercise.DialogReply -> player.speakOnce(ex.reply.prompt)
            else -> Unit // испанский ответ появится только после ответа
        }
    }

    /** Карточку перевернули: если спереди был русский, испанский появился только сейчас. */
    fun onFlashRevealed(ex: Exercise.Flash) {
        if (!ex.spanishFront) speakWord(ex.word)
    }

    /** В «Сказать вслух» после неудачной попытки показали правильный ответ. */
    fun onAnswerShown(ex: Exercise.ForWord) {
        answerSpokenFor = current?.id
        if (autoWord) player.speakOnce(ex.answerEs)
    }

    /** Задание, для которого ответ уже был показан (и озвучен) до окончательной оценки. */
    private var answerSpokenFor: Int? = null

    private fun speakFeedback(ex: Exercise) {
        if (answerSpokenFor != null && answerSpokenFor == current?.id) return
        when (ex) {
            is Exercise.BuildSentence -> if (autoWord || autoExample) player.speakOnce(ex.word.sentence)
            is Exercise.DialogReply -> if (autoWord) player.speakOnce(ex.answerEs)
            is Exercise.ForWord -> speakWord(ex.word)
        }
    }

    private fun speakWord(w: SavedWord) {
        player.speakSequence(
            listOfNotNull(
                w.word.takeIf { autoWord },
                w.sentence.takeIf { autoExample && it.isNotBlank() },
            ),
        )
    }

    override fun onCleared() {
        player.stopSpeech()
    }

    companion object {
        const val MAX_DIALOGS = 3
        private const val REQUEUE_GAP = 3
    }
}
