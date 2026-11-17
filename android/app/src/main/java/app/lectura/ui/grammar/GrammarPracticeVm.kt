package app.lectura.ui.grammar

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectura.LecturaApp
import app.lectura.data.GrammarProgress
import app.lectura.data.vocabIndex
import app.lectura.model.GrammarTopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ответ на упражнение сессии. given — что ввёл/выбрал пользователь (пусто — «Не знаю»). */
data class ItemResult(val item: PracticeItem, val verdict: Verdict, val given: String) {
    val correct: Boolean get() = verdict != Verdict.WRONG
}

/** Сессия практики по теме: подбор 10 упражнений, ответы, итог (записывается ровно один раз). */
class GrammarPracticeVm(private val topicId: String) : ViewModel() {
    private val app = LecturaApp.instance

    var loading by mutableStateOf(true)
        private set
    var topic by mutableStateOf<GrammarTopic?>(null)
        private set
    var items by mutableStateOf<List<PracticeItem>>(emptyList())
        private set
    var pos by mutableIntStateOf(0)
        private set
    /** Ответ на текущее упражнение (null — ещё не ответили). */
    var current by mutableStateOf<ItemResult?>(null)
        private set
    val results = mutableStateListOf<ItemResult>()
    var finished by mutableStateOf(false)
        private set
    /** Прогресс темы до и после сессии. */
    var before by mutableStateOf<GrammarProgress?>(null)
        private set
    var after by mutableStateOf<GrammarProgress?>(null)
        private set
    var saveError by mutableStateOf(false)
        private set

    private var recorded = false
    private var started = false

    fun ensureStarted() {
        if (!started) start()
    }

    fun start() {
        started = true
        loading = true
        viewModelScope.launch {
            val t = runCatching { app.curriculum.topic(topicId) }.getOrNull()
            val (picked, prev) = withContext(Dispatchers.Default) {
                val prev = runCatching { app.db.grammar().get(topicId) }.getOrNull()
                val words = runCatching { app.db.words().allOnce() }.getOrDefault(emptyList())
                val vocab = vocabIndex(words)
                GrammarLogic.pick(topicId, t?.exercises.orEmpty(), vocab, GrammarLogic.recentSet(prev)) to prev
            }
            topic = t
            items = picked
            before = prev
            after = null
            pos = 0
            current = null
            results.clear()
            finished = false
            recorded = false
            saveError = false
            loading = false
        }
    }

    fun answer(verdict: Verdict, given: String) {
        val item = items.getOrNull(pos) ?: return
        if (current != null) return
        val r = ItemResult(item, verdict, given)
        current = r
        results += r
    }

    fun next() {
        app.player.stopSpeech()
        if (current == null) return
        if (pos + 1 < items.size) {
            pos++
            current = null
        } else {
            finish()
        }
    }

    private fun finish() {
        if (recorded) return
        recorded = true
        finished = true
        val total = results.size
        val correct = results.count { it.correct }
        val shown = results.map { it.item.id }
        viewModelScope.launch {
            try {
                val level = app.curriculum.levelOfTopic(topicId) ?: before?.level ?: ""
                after = app.userData.recordGrammarSession(topicId, level, correct, total, shown)
            } catch (e: Exception) {
                Log.e("GrammarPractice", "record failed", e)
                saveError = true
            }
        }
    }

    override fun onCleared() {
        app.player.stopSpeech()
    }
}
