package app.lectura.playback

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.lectura.LecturaApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Фраза урока: [clip] — озвучка диктора (путь в APK или файл), иначе системный голос.
 * [topic] — тема, из которой фраза (когда по кругу играют несколько тем).
 */
data class LessonItem(val es: String, val ru: String, val clip: String? = null, val topic: String = "")

enum class LessonMode(val label: String) {
    /** Русская фраза → пауза (вы говорите по-испански) → эталон дважды. */
    ANTICIPATE("Предугадай"),

    /** Испанская фраза → пауза (повторяете) → ещё раз. */
    REPEAT("Слушай и повторяй"),
}

/**
 * Аудиоурок без экрана: фразы по очереди, с паузами на ответ. Играет в [LessonService] (уведомление,
 * кнопки гарнитуры, экран можно выключить). Один урок за раз; книга на время урока ставится на паузу.
 */
class LessonPlayer(private val app: LecturaApp) {
    enum class Phase { PROMPT, YOUR_TURN, ANSWER, DONE }

    data class State(
        val title: String = "",
        val items: List<LessonItem> = emptyList(),
        val index: Int = 0,
        val phase: Phase = Phase.PROMPT,
        val playing: Boolean = false,
        val mode: LessonMode = LessonMode.ANTICIPATE,
        val slow: Boolean = false,
        /** Русского голоса нет — «Предугадай» показывает русский только на экране. */
        val ruMissing: Boolean = false,
        /** По кругу: после последней фразы — снова первая, пока не остановят. */
        val loop: Boolean = false,
        /** Номер круга (с 1). */
        val round: Int = 1,
    ) {
        val active: Boolean get() = items.isNotEmpty() && phase != Phase.DONE
        val current: LessonItem? get() = items.getOrNull(index)

        /** Тема текущей фразы (у нескольких тем по кругу), иначе название урока. */
        val topic: String get() = current?.topic?.takeIf { it.isNotBlank() } ?: title
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val clips by lazy { ClipPlayer(app) }
    private val ruReady = CompletableDeferred<Boolean>()
    private var ruTts: TextToSpeech? = null

    /** Русский голос для подсказок; создаётся при первом уроке «Предугадай». */
    private fun ru(): TextToSpeech = ruTts ?: TextToSpeech(app) { status ->
        // Колбэк может прийти раньше, чем конструктор вернёт объект, — язык ставим следующим кадром.
        main.post {
            val t = ruTts
            val ok = status == TextToSpeech.SUCCESS && t != null &&
                runCatching { t.setLanguage(Locale("ru", "RU")) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
            ruReady.complete(ok)
        }
    }.also { ruTts = it }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun start(title: String, items: List<LessonItem>, mode: LessonMode, loop: Boolean = false) {
        if (items.isEmpty()) return
        stopSound()
        if (app.player.state.value.playing) app.player.pause()
        _state.value = State(title, items, 0, Phase.PROMPT, playing = true, mode = mode, slow = _state.value.slow, loop = loop)
        startService()
        run()
    }

    fun toggle() = if (_state.value.playing) pause() else resume()

    fun pause() {
        job?.cancel()
        stopSound()
        _state.update { it.copy(playing = false) }
    }

    fun resume() {
        if (!_state.value.active) return
        _state.update { it.copy(playing = true, phase = Phase.PROMPT) }
        startService()
        run()
    }

    fun next() = jump(+1)
    fun prev() = jump(-1)

    private fun jump(d: Int) {
        val s = _state.value
        if (s.items.isEmpty()) return
        job?.cancel()
        stopSound()
        _state.update { it.copy(index = step(s.index, d, s.items.size, s.loop), phase = Phase.PROMPT) }
        if (s.playing) run()
    }

    fun setMode(m: LessonMode) {
        _state.update { it.copy(mode = m) }
        if (_state.value.playing) jump(0)
    }

    fun setSlow(v: Boolean) = _state.update { it.copy(slow = v) }

    fun stop() {
        job?.cancel()
        stopSound()
        _state.value = State(slow = _state.value.slow)
    }

    private fun run() {
        job?.cancel()
        job = scope.launch {
            while (true) {
                val s = _state.value
                val item = s.current ?: break
                val rate = if (s.slow) 0.75f else 1f
                set(Phase.PROMPT)
                if (s.mode == LessonMode.ANTICIPATE) sayRu(item.ru) else clips.play(item.clip, item.es, rate)
                set(Phase.YOUR_TURN)
                delay(pauseFor(item.es, s.slow))
                set(Phase.ANSWER)
                clips.play(item.clip, item.es, rate)
                delay(700)
                clips.play(item.clip, item.es, rate)
                delay(1300)
                if (s.index >= s.items.lastIndex) {
                    if (s.loop) {
                        _state.update { it.copy(index = 0, round = it.round + 1) }
                        continue
                    }
                    _state.update { it.copy(phase = Phase.DONE, playing = false) }
                    break
                }
                _state.update { it.copy(index = it.index + 1) }
            }
        }
    }

    private fun set(p: Phase) = _state.update { it.copy(phase = p) }

    private suspend fun sayRu(text: String) {
        val tts = ru()
        val ok = ruReady.await()
        _state.update { it.copy(ruMissing = !ok) }
        if (!ok) {
            delay(1500) // без голоса — время прочитать с экрана
            return
        }
        suspendCancellableCoroutine { cont ->
            val id = "ru${System.nanoTime()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("deprecated in API")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { tts.stop() }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
    }

    private fun stopSound() {
        clips.stop()
        runCatching { ruTts?.stop() }
    }

    private fun startService() {
        try {
            app.startForegroundService(Intent(app, LessonService::class.java))
        } catch (e: Exception) {
            Log.w("Lessons", "cannot start service", e)
        }
    }

    companion object {
        /** Несколько тем подряд одним уроком: каждая фраза помнит свою тему. */
        fun playlist(topics: List<Pair<String, List<LessonItem>>>): List<LessonItem> =
            topics.flatMap { (title, items) -> items.map { it.copy(topic = title) } }

        /** Следующая/предыдущая фраза: по кругу — с переходом через край, иначе — в пределах урока. */
        fun step(index: Int, d: Int, size: Int, loop: Boolean): Int =
            if (loop) Math.floorMod(index + d, size) else (index + d).coerceIn(0, size - 1)

        /** Пауза на ответ: чем длиннее фраза, тем дольше (≈ время произнести её дважды медленно). */
        fun pauseFor(es: String, slow: Boolean): Long {
            val base = 1200L + es.length * 75L
            return (if (slow) base * 13 / 10 else base).coerceIn(2000L, 8000L)
        }
    }
}
