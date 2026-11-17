package app.lectura.playback

import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import app.lectura.LecturaApp
import app.lectura.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Читает книгу вслух по предложениям через системный TTS.
 * Единственный источник правды о текущей позиции (книга / глава / предложение):
 * ридер и уведомление только отображают это состояние.
 */
class Player(private val app: LecturaApp) {

    data class State(
        val bookId: String? = null,
        val bookTitle: String = "",
        val chapter: Int = 0,
        val chapterCount: Int = 0,
        val sentence: Int = 0,
        val sentenceCount: Int = 0,
        val playing: Boolean = false,
        val rate: Float = 0.8f,
        val ttsReady: Boolean = false,
        val voiceMissing: Boolean = false,
        val voiceName: String = "",
    )

    private val _state = MutableStateFlow(State(rate = app.prefs.rate))
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var book: Book? = null
    private var generation = 0
    private var playWhenReady = false

    private val tts: TextToSpeech = TextToSpeech(app) { status -> main.post { onInit(status) } }

    val currentText: String
        get() {
            val b = book ?: return ""
            val s = _state.value
            return b.chapters.getOrNull(s.chapter)?.sentences?.getOrNull(s.sentence)?.text.orEmpty()
        }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _state.update { it.copy(voiceMissing = true) }
            return
        }
        val spain = Locale.forLanguageTag("es-ES")
        val res = tts.setLanguage(spain)
        val voice = pickVoice()
        voice?.let { tts.voice = it }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { onUtteranceDone(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { onUtteranceDone(utteranceId) }
            }
        })
        _state.update {
            it.copy(
                ttsReady = true,
                voiceMissing = res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED,
                voiceName = voice?.name.orEmpty(),
            )
        }
        if (playWhenReady) play()
    }

    /** Лучший испанский (Испания) голос, предпочитаем офлайновые. */
    private fun pickVoice(): Voice? = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == "es" && it.locale.country == "ES" }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }
            .maxByOrNull { it.quality - if (it.isNetworkConnectionRequired) 1000 else 0 }
    }.getOrNull()

    /** Открыть книгу. Если уже открыта эта же — позицию не трогаем (может играть в фоне). */
    fun open(b: Book, chapter: Int, sentence: Int) {
        if (_state.value.bookId == b.id) {
            book = b
            return
        }
        stopSpeaking()
        book = b
        val ch = chapter.coerceIn(0, b.chapters.lastIndex)
        _state.update {
            it.copy(
                bookId = b.id, bookTitle = b.title, playing = false,
                chapter = ch, chapterCount = b.chapters.size,
                sentence = sentence.coerceIn(0, maxOf(0, b.chapters[ch].sentences.lastIndex)),
                sentenceCount = b.chapters[ch].sentences.size,
            )
        }
    }

    fun play() {
        if (book == null) return
        if (!_state.value.ttsReady) {
            playWhenReady = true
            return
        }
        playWhenReady = false
        _state.update { it.copy(playing = true) }
        app.startPlaybackService()
        speakCurrent()
    }

    fun pause() {
        playWhenReady = false
        _state.update { it.copy(playing = false) }
        stopSpeaking()
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun next() = seek(_state.value.sentence + 1)
    fun prev() = seek(_state.value.sentence - 1)

    fun seek(sentence: Int) {
        val s = _state.value
        when {
            sentence < 0 -> if (s.chapter > 0) {
                seekChapter(s.chapter - 1, atEnd = true)
                return
            }
            sentence >= s.sentenceCount -> {
                if (s.chapter + 1 < s.chapterCount) seekChapter(s.chapter + 1) else pause()
                return
            }
        }
        _state.update { it.copy(sentence = sentence.coerceIn(0, maxOf(0, it.sentenceCount - 1))) }
        saveProgress()
        if (_state.value.playing) speakCurrent()
    }

    fun seekChapter(chapter: Int, atEnd: Boolean = false) {
        val b = book ?: return
        if (chapter !in b.chapters.indices) return
        val count = b.chapters[chapter].sentences.size
        _state.update {
            it.copy(chapter = chapter, sentenceCount = count, sentence = if (atEnd) maxOf(0, count - 1) else 0)
        }
        saveProgress()
        if (_state.value.playing) speakCurrent() else stopSpeaking()
    }

    fun setRate(rate: Float) {
        app.prefs.rate = rate
        _state.update { it.copy(rate = rate) }
        if (_state.value.playing) speakCurrent()
    }

    /** Произнести отдельно слово или предложение (чтение книги ставится на паузу). */
    fun speakOnce(text: String, slow: Boolean = false) {
        if (_state.value.playing) pause()
        generation++
        tts.setSpeechRate(if (slow) 0.6f else _state.value.rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "once")
    }

    /** Произнести по очереди несколько фраз (например, слово и пример). Прерывает текущую речь. */
    fun speakSequence(texts: List<String>) {
        val items = texts.filter { it.isNotBlank() }
        if (items.isEmpty()) return
        if (_state.value.playing) pause()
        generation++
        tts.setSpeechRate(_state.value.rate)
        items.forEachIndexed { i, t ->
            tts.speak(t, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "once$i")
        }
    }

    /** Замолчать (если не идёт чтение книги — его не трогаем). */
    fun stopSpeech() {
        if (_state.value.playing) return
        stopSpeaking()
    }

    private fun speakCurrent() {
        val text = currentText
        if (text.isBlank()) {
            onUtteranceDone(utteranceId())
            return
        }
        generation++
        tts.setSpeechRate(_state.value.rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId())
    }

    private fun utteranceId() = "s|$generation"

    private fun onUtteranceDone(id: String?) {
        if (id != utteranceId() || !_state.value.playing) return
        val s = _state.value
        when {
            s.sentence + 1 < s.sentenceCount -> {
                _state.update { it.copy(sentence = it.sentence + 1) }
                saveProgress()
                speakCurrent()
            }
            s.chapter + 1 < s.chapterCount -> seekChapter(s.chapter + 1)
            else -> pause()
        }
    }

    private fun stopSpeaking() {
        generation++
        runCatching { tts.stop() }
    }

    private fun saveProgress() {
        val s = _state.value
        val id = s.bookId ?: return
        io.launch {
            runCatching {
                app.userData.saveProgress(id, s.chapter, s.sentence)
            }.onFailure { Log.w("Player", "progress", it) }
        }
    }
}
