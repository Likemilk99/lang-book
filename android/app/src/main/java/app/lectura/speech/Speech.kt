package app.lectura.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lectura.LecturaApp
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

// КОНТРАКТ модуля речи. Сигнатуры менять нельзя — на них опираются упражнения.
// Реализация: агент «speech».

/**
 * Распознавание испанской речи (es-ES).
 *
 * Движок: по умолчанию системный распознаватель Google с сервером — тот же, что у голосового ввода
 * клавиатуры, он заметно точнее офлайн-модели. Распознаватель на телефоне (офлайн) — если выбран
 * [offline] или Google недоступен (нет сети).
 */
class SpeechInput(private val context: Context) {
    sealed interface Event {
        data object Ready : Event
        data class Partial(val text: String) : Event
        data class Final(val alternatives: List<String>) : Event
        data class Error(val message: String) : Event
    }

    /** Громкость голоса во время записи (≈ -2..10 дБ) — для анимации. Вызывается на главном потоке. */
    var onLevel: ((Float) -> Unit)? = null

    /**
     * Запись заканчивается только по [stop] (или через [MAX_MANUAL_MS]): паузы «на подумать» не обрывают ответ.
     * Просим у распознавателя сегментированную сессию (одна запись, результаты кусками). Если он её не умеет
     * и останавливается на тишине — незаметно перезапускаем его и склеиваем куски.
     */
    var manualStop: Boolean = false

    /** Распознавать на телефоне (офлайн) вместо Google онлайн. */
    var offline: Boolean = false

    /** Ожидаемые фразы: распознаватель охотнее выбирает эти слова, акцент ученика меньше мешает. */
    var bias: List<String> = emptyList()

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val appContext: Context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var recognizerOnDevice = false
    /** Номер текущей сессии распознавателя: колбэки от старых сессий игнорируются. */
    private var session = 0
    private var onEventCurrent: ((Event) -> Unit)? = null

    // Состояние одной записи (от start до результата).
    /** Распознанные куски; у каждого — варианты распознавания (лучший первый). */
    private val segments = ArrayList<List<String>>()
    private var lastPartial = ""
    private var finished = true
    private var stopRequested = false
    private var restartPending = false
    private var startedAt = 0L
    private var begunAt = 0L
    /** Текущая сессия распознавателя готова слушать. */
    private var ready = false
    private var everReady = false
    private var heardVoice = false
    private var quickFails = 0
    private var retries = 0
    private var switchedEngine = false
    /** Движок этой записи: true — на телефоне. */
    private var useOnDevice = false

    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext) || SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    /** Вызывать на главном потоке. Требует разрешения RECORD_AUDIO. */
    fun start(onEvent: (Event) -> Unit) {
        cancel()
        // Каждая запись — со свежим распознавателем: переиспользованный после ошибок иногда «зависает».
        destroyRecognizer()
        segments.clear()
        lastPartial = ""
        finished = false
        stopRequested = false
        restartPending = false
        startedAt = System.currentTimeMillis()
        everReady = false
        heardVoice = false
        quickFails = 0
        retries = 0
        switchedEngine = false
        onEventCurrent = onEvent
        if (!isAvailable()) return fail("Распознавание речи на телефоне недоступно")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return fail("Нет доступа к микрофону")
        }
        useOnDevice = chooseOnDevice()
        if (useOnDevice) probeOnDeviceSupport()
        begin()
    }

    /** Закончить запись: результат — по уже сказанному. */
    fun stop() {
        if (finished) return
        stopRequested = true
        when {
            restartPending -> finish()
            // Микрофон ещё не включился — ждать нечего.
            !ready -> finish()
            else -> {
                runCatching { recognizer?.stopListening() }
                // Распознаватель обязан ответить сам, но если молчит — не держим кнопку «■» вечно.
                val id = session
                main.postDelayed({
                    if (!finished && session == id) {
                        Log.i(TAG, "no result after stop — finishing with what we have")
                        finish()
                    }
                }, STOP_GRACE_MS)
            }
        }
    }

    /** Прервать без результата. */
    fun cancel() {
        session++
        finished = true
        restartPending = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        cancel()
        destroyRecognizer()
        onEventCurrent = null
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun chooseOnDevice(): Boolean {
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        val cloud = SpeechRecognizer.isRecognitionAvailable(appContext)
        return when {
            !onDevice -> false
            !cloud -> true
            else -> offline
        }
    }

    private fun keepListening(): Boolean =
        manualStop && !stopRequested && System.currentTimeMillis() - startedAt < MAX_MANUAL_MS

    private fun joined(tail: String = ""): String =
        (segments.map { it.first() } + tail).filter { it.isNotBlank() }.joinToString(" ").trim()

    private fun partialTail(): List<String> = listOfNotNull(lastPartial.takeIf { it.isNotBlank() })

    private fun begin() {
        if (finished) return
        val rec = obtain(useOnDevice) ?: return fail("Не удалось запустить распознавание")
        val id = ++session
        lastPartial = ""
        ready = false
        begunAt = System.currentTimeMillis()
        rec.setRecognitionListener(Listener(id))
        val left = (MAX_MANUAL_MS - (begunAt - startedAt)).coerceAtLeast(1_000L)
        try {
            rec.startListening(recognizerIntent(appContext, useOnDevice, if (manualStop) left else 0L, bias))
        } catch (e: Exception) {
            Log.w(TAG, "startListening", e)
            return fail("Не удалось включить микрофон")
        }
        // Распознаватель не сказал «готов» — пересоздаём его, а не ждём бесконечно.
        main.postDelayed({ if (session == id && !ready && !finished) onStartTimeout() }, READY_TIMEOUT_MS)
    }

    private fun onStartTimeout() {
        Log.w(TAG, "recognizer not ready in ${READY_TIMEOUT_MS}ms (onDevice=$useOnDevice, retries=$retries)")
        destroyRecognizer()
        when {
            retries < 1 -> {
                retries++
                begin()
            }
            segments.isNotEmpty() -> finish()
            else -> fail("Микрофон не включился. Закройте приложения, которые могут его занимать, и попробуйте снова")
        }
    }

    /** Кусок закончился (пауза) — запоминаем его и слушаем дальше. */
    private fun restart(piece: List<String>) {
        if (piece.isNotEmpty()) segments += piece
        lastPartial = ""
        // Распознаватель сразу же падает снова и снова — микрофон, видимо, недоступен; не крутимся вхолостую.
        quickFails = if (System.currentTimeMillis() - begunAt < QUICK_FAIL_MS) quickFails + 1 else 0
        if (quickFails >= 5) {
            Log.w(TAG, "recognizer keeps failing instantly")
            return if (segments.isNotEmpty()) finish() else fail("Микрофон не отвечает — попробуйте ещё раз")
        }
        if (quickFails == 3) destroyRecognizer()
        session++ // колбэки завершённого куска больше не нужны
        restartPending = true
        if (segments.isNotEmpty()) onEventCurrent?.invoke(Event.Partial(joined()))
        main.postDelayed({
            if (!restartPending) return@postDelayed
            restartPending = false
            if (stopRequested) finish() else begin()
        }, if (quickFails > 0) 300L else 50L)
    }

    /** Итог записи: склеенные куски + [tail] (варианты последнего куска). */
    private fun finish(tail: List<String> = partialTail()) {
        if (finished) return
        finished = true
        session++
        restartPending = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        val pieces = ArrayList(segments)
        tail.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { pieces += it }
        val cb = onEventCurrent ?: return
        if (pieces.isEmpty()) return cb(Event.Error(nothingHeardMessage()))
        val prefix = pieces.dropLast(1).map { it.first() }
        val alts = pieces.last().map { (prefix + it).joinToString(" ").trim() }.filter { it.isNotBlank() }.distinct()
        cb(if (alts.isEmpty()) Event.Error(nothingHeardMessage()) else Event.Final(alts))
    }

    private fun fail(message: String) {
        if (finished) return
        val cb = onEventCurrent
        cancel()
        cb?.invoke(Event.Error(message))
    }

    private fun nothingHeardMessage(): String = when {
        !everReady -> "Микрофон ещё не включился. Нажмите, дождитесь вибрации и говорите"
        !heardVoice -> "Микрофон не слышит голоса. Говорите громче или ближе к телефону"
        else -> errorMessage(SpeechRecognizer.ERROR_NO_MATCH)
    }

    private fun obtain(onDevice: Boolean): SpeechRecognizer? {
        recognizer?.let {
            if (recognizerOnDevice == onDevice) return it
            destroyRecognizer()
        }
        val r = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            else SpeechRecognizer.createSpeechRecognizer(appContext)
        }.onFailure { Log.w(TAG, "create recognizer (onDevice=$onDevice)", it) }.getOrNull()
        recognizer = r
        recognizerOnDevice = onDevice
        return r
    }

    private inner class Listener(private val id: Int) : RecognitionListener {
        private val live get() = id == session && !finished

        override fun onReadyForSpeech(params: Bundle?) {
            if (!live) return
            ready = true
            everReady = true
            onEventCurrent?.invoke(Event.Ready)
        }

        override fun onBeginningOfSpeech() {
            if (live) heardVoice = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!live) return
            if (rmsdB >= VOICE_RMS) heardVoice = true
            onLevel?.invoke(rmsdB)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() } ?: return
            lastPartial = text
            onEventCurrent?.invoke(Event.Partial(joined(text)))
        }

        /** Сегментированная сессия: готов очередной кусок, запись продолжается. */
        override fun onSegmentResults(segmentResults: Bundle) {
            if (!live) return
            val alts = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .filter { it.isNotBlank() }
            if (alts.isEmpty()) return
            segments += alts
            lastPartial = ""
            onEventCurrent?.invoke(Event.Partial(joined()))
        }

        override fun onEndOfSegmentedSession() {
            if (!live) return
            if (keepListening()) restart(partialTail()) else finish()
        }

        override fun onResults(results: Bundle?) {
            if (!live) return
            val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .filter { it.isNotBlank() }
                .ifEmpty { partialTail() }
            if (keepListening()) restart(alts) else finish(alts)
        }

        override fun onError(error: Int) {
            if (!live) return
            Log.i(TAG, "recognizer error $error (onDevice=$recognizerOnDevice, stop=$stopRequested)")
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            val language = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            val network = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
            when {
                // Пользователь нажал «стоп» — отдаём всё, что накопилось.
                stopRequested && (silence || error == SpeechRecognizer.ERROR_CLIENT) -> finish()
                // Ручной режим: тишина — это пауза «на подумать», слушаем дальше.
                silence && keepListening() -> restart(partialTail())
                // Google онлайн недоступен (нет сети) или не знает язык — переходим на распознаватель телефона.
                !useOnDevice && (network || language) && !switchedEngine &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) -> {
                    switchedEngine = true
                    useOnDevice = true
                    probeOnDeviceSupport()
                    begin()
                }
                // На телефоне нет испанской модели — просим скачать и пока работаем через Google.
                useOnDevice && language && !switchedEngine && SpeechRecognizer.isRecognitionAvailable(appContext) -> {
                    switchedEngine = true
                    recognizer?.let { requestModelDownload(it) }
                    useOnDevice = false
                    begin()
                }
                // Распознаватель занят/отключился — пересоздаём и пробуем ещё раз.
                (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    error == SpeechRecognizer.ERROR_CLIENT) && retries < 1 -> {
                    retries++
                    session++
                    destroyRecognizer()
                    main.postDelayed({ begin() }, 250L)
                }
                // Тишина или сбой, но что-то уже распознано — засчитываем это.
                silence || segments.isNotEmpty() || lastPartial.isNotBlank() -> finish()
                else -> fail(errorMessage(error))
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Один раз за запуск приложения: если испанской модели на телефоне нет — просим систему её скачать. */
    private fun probeOnDeviceSupport() {
        if (probed || !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) return
        probed = true
        val probe = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }.getOrNull() ?: return
        val intent = recognizerIntent(appContext, onDevice = true)
        runCatching {
            probe.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(appContext), object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installed = support.installedOnDeviceLanguages.any { it.equals(LANG, ignoreCase = true) }
                    val pending = support.pendingOnDeviceLanguages.any { it.equals(LANG, ignoreCase = true) }
                    val supported = support.supportedOnDeviceLanguages.any { it.equals(LANG, ignoreCase = true) }
                    Log.i(TAG, "on-device es-ES: installed=$installed pending=$pending supported=$supported")
                    if (!installed && supported && !pending) requestModelDownload(probe, intent)
                    runCatching { probe.destroy() }
                }

                override fun onError(error: Int) {
                    Log.i(TAG, "checkRecognitionSupport error $error")
                    runCatching { probe.destroy() }
                }
            })
        }.onFailure {
            Log.w(TAG, "checkRecognitionSupport", it)
            runCatching { probe.destroy() }
        }
    }

    private fun requestModelDownload(rec: SpeechRecognizer, intent: Intent = recognizerIntent(appContext, onDevice = true)) {
        if (downloadRequested) return
        downloadRequested = true
        // Не блокирует: система качает модель в фоне (может показать своё уведомление).
        runCatching { rec.triggerModelDownload(intent) }.onFailure { Log.w(TAG, "triggerModelDownload", it) }
    }

    companion object {
        private const val TAG = "SpeechInput"
        private const val LANG = "es-ES"

        private var probed = false
        private var downloadRequested = false

        /** Предел длины ответа в ручном режиме. */
        const val MAX_MANUAL_MS = 60_000L
        private const val READY_TIMEOUT_MS = 4_000L
        private const val STOP_GRACE_MS = 2_500L
        /** Ошибка раньше этого срока после запуска — «мгновенный» сбой. */
        private const val QUICK_FAIL_MS = 600L
        /** Громкость (дБ), выше которой считаем, что микрофон слышит голос. */
        private const val VOICE_RMS = 4f

        /** [manualMs] > 0 — запись по кнопке: просим одну длинную сегментированную сессию. */
        private fun recognizerIntent(
            ctx: Context,
            onDevice: Boolean,
            manualMs: Long = 0L,
            bias: List<String> = emptyList(),
        ) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val manual = manualMs > 0
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Офлайн-модель Google заметно хуже серверной — просим её, только если выбран распознаватель на телефоне.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Ученик говорит медленно, с паузами — не обрываем запись слишком рано.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (manual) 10_000L else 2000L)
            if (manual) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, manualMs)
            }
            if (bias.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias))
            // Звёздочки вместо слов сломали бы сравнение.
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        }

        fun errorMessage(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Сеть не отвечает. Попробуйте ещё раз"
            SpeechRecognizer.ERROR_NETWORK -> "Нужен интернет (испанская модель офлайн не установлена)"
            SpeechRecognizer.ERROR_AUDIO -> "Ошибка микрофона"
            SpeechRecognizer.ERROR_SERVER -> "Ошибка сервиса распознавания"
            SpeechRecognizer.ERROR_CLIENT -> "Распознавание прервано"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ничего не слышно — попробуйте ещё раз"
            SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал — скажите ещё раз, чётче"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят — повторите"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Слишком много запросов — подождите немного"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Сервис распознавания отключился — повторите"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Испанский (Испания) не поддерживается распознавателем"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Испанская модель пока недоступна (возможно, скачивается)"
            else -> "Ошибка распознавания ($code)"
        }
    }
}

data class WordMatch(val word: String, val matched: Boolean)

data class MatchResult(
    /** Доля совпавших слов 0..1 */
    val score: Float,
    /** Ожидаемые слова с отметкой, совпали ли они */
    val words: List<WordMatch>,
    /** Что распознано (лучший вариант) */
    val heard: String,
) {
    val passed: Boolean get() = score >= PASS_SCORE

    companion object {
        const val PASS_SCORE = 0.8f
    }
}

object SpeechMatch {
    /**
     * Свободный ответ: лучшее совпадение с любым из допустимых вариантов. Имена собственные внутри
     * предложения («Me llamo Iván», «Voy a Toledo») необязательны — ответ со своим именем тоже верный.
     */
    fun best(accepted: List<String>, heardAlternatives: List<String>): MatchResult =
        accepted.map { compare(withoutNames(it), heardAlternatives) }.maxBy { it.score }

    /** Убирает слова с заглавной буквы, кроме первого слова предложения. */
    fun withoutNames(s: String): String {
        var sentenceStart = true
        val kept = s.split(' ').filter { it.isNotBlank() }.filter { w ->
            val core = w.trimStart('¿', '¡', '«', '"', '(')
            val isName = !sentenceStart && core.firstOrNull()?.isUpperCase() == true
            sentenceStart = w.last() in ".?!…" || (core.isEmpty() && w.isNotEmpty())
            !isName
        }
        return kept.joinToString(" ").ifBlank { s }
    }

    /** Сравнение без учёта регистра, ударений и пунктуации; берётся лучший из вариантов. */
    fun compare(expected: String, heardAlternatives: List<String>): MatchResult {
        val chunks = displayChunks(expected)
        // Плоский список нормализованных ожидаемых слов и номер «отображаемого» слова для каждого.
        val expWords = ArrayList<String>()
        val owner = ArrayList<Int>()
        chunks.forEachIndexed { ci, c -> c.norm.forEach { expWords += it; owner += ci } }

        val alts = heardAlternatives.filter { it.isNotBlank() }.ifEmpty { listOf("") }
        var bestFlags = BooleanArray(expWords.size)
        var bestCount = -1
        var bestHeard = ""
        for (alt in alts) {
            val flags = lcsMatch(expWords, normalizeWords(alt))
            val count = flags.count { it }
            if (count > bestCount) {
                bestCount = count
                bestFlags = flags
                bestHeard = alt.trim()
            }
        }

        val chunkOk = BooleanArray(chunks.size) { true }
        expWords.indices.forEach { i -> if (!bestFlags[i]) chunkOk[owner[i]] = false }
        val score = when {
            expWords.isEmpty() -> if (bestHeard.isNotBlank()) 1f else 0f
            else -> bestCount.coerceAtLeast(0).toFloat() / expWords.size
        }
        return MatchResult(
            score = score,
            words = chunks.mapIndexed { ci, c -> WordMatch(c.display, chunkOk[ci]) },
            heard = bestHeard,
        )
    }

    private class Chunk(var display: String, val norm: List<String>)

    /**
     * Ожидаемый текст по словам в исходном виде («¿Qué», «tal?»). Куски без букв («—») приклеиваются к соседу,
     * чтобы не подсвечивать пунктуацию отдельно.
     */
    private fun displayChunks(expected: String): List<Chunk> {
        val res = ArrayList<Chunk>()
        var prefix = ""
        for (raw in expected.trim().split(WHITESPACE)) {
            if (raw.isEmpty()) continue
            val norm = normalizeWords(raw)
            when {
                norm.isNotEmpty() -> {
                    res += Chunk(if (prefix.isEmpty()) raw else "$prefix $raw", norm)
                    prefix = ""
                }
                res.isNotEmpty() -> res.last().display += " $raw"
                else -> prefix = if (prefix.isEmpty()) raw else "$prefix $raw"
            }
        }
        if (prefix.isNotEmpty()) res += Chunk(prefix, emptyList())
        return res
    }

    /** Нижний регистр, без диакритики (á→a, ñ→n, ü→u), без пунктуации, числа — словами. */
    internal fun normalizeWords(s: String): List<String> {
        val lower = Normalizer.normalize(SpanishNumbers.joinThousands(s), Normalizer.Form.NFC).lowercase(SPANISH)
        val words = lower.replace(NON_WORD, " ").split(' ')
            .filter { it.isNotEmpty() }
            .flatMap { expandNumber(it) }
        return words.map { Normalizer.normalize(it, Normalizer.Form.NFD).replace(MARKS, "") }
    }

    /** Отмечает, какие ожидаемые слова нашлись в услышанном (по наибольшей общей подпоследовательности). */
    private fun lcsMatch(exp: List<String>, heard: List<String>): BooleanArray {
        val m = exp.size
        val n = heard.size
        val flags = BooleanArray(m)
        if (m == 0 || n == 0) return flags
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) for (j in n - 1 downTo 0) {
            dp[i][j] = if (same(exp[i], heard[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                same(exp[i], heard[j]) && dp[i][j] == dp[i + 1][j + 1] + 1 -> {
                    flags[i] = true
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return flags
    }

    private fun same(a: String, b: String): Boolean = when {
        a == b -> true
        a in UNO && b in UNO -> true
        // Звучат одинаково (h немая, b = v, ll = y) — произношение верное, это распознаватель выбрал написание.
        sound(a) == sound(b) -> true
        // Мелкая оговорка/ошибка распознавания в длинном слове не считается промахом.
        minOf(a.length, b.length) >= 6 && editDistanceAtMostOne(a, b) -> true
        else -> false
    }

    /** Как слово звучит в испанском Испании: «hola» = «ola», «vaso» = «baso», «llamo» = «yamo». */
    internal fun sound(w: String): String = w.replace(SILENT_H, "").replace("ll", "y").replace('v', 'b')

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** Распознаватель часто пишет числа цифрами: «a las 8» → «a las ocho». */
    private fun expandNumber(w: String): List<String> {
        if (!w.all { it.isDigit() }) return listOf(w)
        if (w.length > 1 && w.all { it == '0' }) return emptyList() // «8:00» → «ocho»
        return SpanishNumbers.forms(w)?.first()?.split(' ') ?: listOf(w)
    }

    private val SPANISH = Locale.forLanguageTag("es-ES")
    private val WHITESPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val UNO = setOf("un", "uno", "una")
    private val SILENT_H = Regex("(?<!c)h")
}

private sealed interface SpeakUi {
    data object Idle : SpeakUi
    data class Listening(val partial: String = "", val ready: Boolean = false) : SpeakUi
    data class Done(val result: MatchResult) : SpeakUi
    data class Failed(val message: String) : SpeakUi
    data object NoPermission : SpeakUi
}

/**
 * Готовый элемент UI: кнопка микрофона, индикатор прослушивания, подсветка совпавших слов.
 * Сам запрашивает RECORD_AUDIO. onResult вызывается после окончательного распознавания.
 */
@Composable
fun SpeakButton(
    expected: String,
    modifier: Modifier = Modifier,
    accepted: List<String> = emptyList(),
    onResult: (MatchResult) -> Unit,
) {
    val context = LocalContext.current
    val input = remember { SpeechInput(context) }
    var ui by remember(expected) { mutableStateOf<SpeakUi>(SpeakUi.Idle) }
    var level by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val currentExpected by rememberUpdatedState(expected)
    val currentAccepted by rememberUpdatedState(accepted)
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = LecturaApp.instance.prefs
    var manualStop by remember { mutableStateOf(prefs.speechManualStop) }
    var offline by remember { mutableStateOf(prefs.speechOffline) }
    val view = LocalView.current

    DisposableEffect(input) {
        input.onLevel = { level = it }
        onDispose { input.destroy() }
    }
    // Сменилась фраза — прерываем запись для старой.
    DisposableEffect(expected) { onDispose { input.cancel() } }
    // Ушли из приложения — микрофон выключаем.
    DisposableEffect(lifecycleOwner, expected) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && ui is SpeakUi.Listening) {
                input.cancel()
                ui = SpeakUi.Idle
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun listen() {
        val player = LecturaApp.instance.player
        if (player.state.value.playing) player.pause() else player.stopSpeech()
        SpeechTts.stopAll()
        val exp = expected
        level = 0f
        ui = SpeakUi.Listening()
        input.manualStop = manualStop
        input.offline = offline
        // Имена из образца («Me llamo Iván») не подсказываем: у ученика своё имя.
        input.bias = currentAccepted.ifEmpty { listOf(exp) }.map { SpeechMatch.withoutNames(it) }.distinct().take(20)
        input.start { ev ->
            if (exp != currentExpected) return@start
            when (ev) {
                SpeechInput.Event.Ready -> (ui as? SpeakUi.Listening)?.takeIf { !it.ready }?.let {
                    // Вибрация = «можно говорить»: слова, сказанные раньше, микрофон не слышит.
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    ui = it.copy(ready = true)
                }
                is SpeechInput.Event.Partial -> ui = SpeakUi.Listening(ev.text, ready = true)
                is SpeechInput.Event.Final -> {
                    val r = SpeechMatch.best(currentAccepted.ifEmpty { listOf(exp) }, ev.alternatives)
                    ui = SpeakUi.Done(r)
                    scope.launch {
                        runCatching { LecturaApp.instance.userData.recordSpeaking() }
                            .onFailure { Log.w("SpeakButton", "recordSpeaking", it) }
                    }
                    currentOnResult(r)
                }
                is SpeechInput.Event.Error -> ui = SpeakUi.Failed(ev.message)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else ui = SpeakUi.NoPermission
    }

    fun onMicClick() {
        when {
            ui is SpeakUi.Listening -> input.stop()
            !input.isAvailable() -> ui = SpeakUi.Failed("Распознавание речи на телефоне недоступно")
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> listen()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val state = ui
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MicCircle(listening = state is SpeakUi.Listening, level = level, onClick = ::onMicClick)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                when (state) {
                    SpeakUi.Idle -> {
                        Text("Нажмите и скажите вслух", style = MaterialTheme.typography.bodyLarge)
                        StopModeToggle(manualStop) {
                            manualStop = it
                            prefs.speechManualStop = it
                        }
                        EngineToggle(offline) {
                            offline = it
                            prefs.speechOffline = it
                        }
                    }
                    is SpeakUi.Listening -> {
                        Text(
                            when {
                                !state.ready -> "Включаю микрофон… говорите после вибрации"
                                manualStop -> "Говорите, можно с паузами. Нажмите ■, когда закончите"
                                else -> "Говорите… (нажмите, чтобы закончить)"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (state.partial.isNotBlank()) Text(
                            state.partial,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            input.cancel()
                            ui = SpeakUi.Idle
                        }) { Text("Отмена") }
                    }
                    is SpeakUi.Done -> {
                        val pct = (state.result.score * 100).roundToInt()
                        Text(
                            if (state.result.passed) "$pct% — отлично! ✓" else "$pct% — попробуйте ещё раз",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (state.result.passed) goodColor() else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Нажмите микрофон, чтобы повторить",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is SpeakUi.Failed -> {
                        Text(state.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                        Text(
                            "Нажмите микрофон, чтобы попробовать снова",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SpeakUi.NoPermission -> {
                        Text(
                            "Без доступа к микрофону приложение не услышит ваш ответ. Разрешите микрофон в настройках.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text("Открыть настройки") }
                    }
                }
            }
        }
        if (state is SpeakUi.Done) MatchView(state.result, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/** Переключатель: запись заканчивается по кнопке (по умолчанию) или сама на паузе. */
@Composable
private fun StopModeToggle(manual: Boolean, onChange: (Boolean) -> Unit) {
    TextButton(onClick = { onChange(!manual) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(
            if (manual) "Конец записи: по кнопке ■ (сменить)" else "Конец записи: на паузе (сменить)",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Переключатель движка: Google онлайн (по умолчанию, точнее) или распознаватель на телефоне (офлайн). */
@Composable
private fun EngineToggle(offline: Boolean, onChange: (Boolean) -> Unit) {
    TextButton(onClick = { onChange(!offline) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(
            if (offline) "Распознавание: на телефоне, офлайн (сменить)" else "Распознавание: Google, онлайн (сменить)",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun goodColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

@Composable
private fun MatchView(result: MatchResult, modifier: Modifier = Modifier) {
    val good = goodColor()
    val bad = MaterialTheme.colorScheme.error
    Column(modifier) {
        Text(
            buildAnnotatedString {
                result.words.forEachIndexed { i, w ->
                    if (i > 0) append(" ")
                    withStyle(
                        SpanStyle(
                            color = if (w.matched) good else bad,
                            textDecoration = if (w.matched) null else TextDecoration.Underline,
                        ),
                    ) { append(w.word) }
                }
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Вы сказали: «${result.heard.ifBlank { "…" }}»",
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MicCircle(listening: Boolean, level: Float, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pulse = if (listening) {
        val t = rememberInfiniteTransition(label = "mic")
        t.animateFloat(
            initialValue = 1f, targetValue = 1.18f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
        ).value
    } else 1f
    // rms ≈ -2..10 дБ → 0..1
    val loud by animateFloatAsState(if (listening) ((level + 2f) / 12f).coerceIn(0f, 1f) else 0f, label = "level")
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        if (listening) Box(
            Modifier
                .size(64.dp)
                .scale(pulse + loud * 0.25f)
                .clip(CircleShape)
                .background(colors.error.copy(alpha = 0.22f)),
        )
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (listening) colors.error else colors.primary)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (listening) "Закончить" else "Сказать",
                tint = if (listening) colors.onError else colors.onPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
