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

/** Распознавание испанской речи (es-ES), по возможности на устройстве (офлайн). */
class SpeechInput(private val context: Context) {
    sealed interface Event {
        data object Ready : Event
        data class Partial(val text: String) : Event
        data class Final(val alternatives: List<String>) : Event
        data class Error(val message: String) : Event
    }

    /** Громкость голоса во время записи (≈ -2..10 дБ) — для анимации. Вызывается на главном потоке. */
    var onLevel: ((Float) -> Unit)? = null

    private val appContext: Context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var recognizerOnDevice = false
    /** Номер текущей сессии: колбэки от старых сессий игнорируются. */
    private var session = 0
    private var lastPartial = ""
    private var fellBackToCloud = false
    private var retries = 0

    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext) || SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    /** Вызывать на главном потоке. Требует разрешения RECORD_AUDIO. */
    fun start(onEvent: (Event) -> Unit) {
        cancel()
        fellBackToCloud = false
        retries = 0
        if (!isAvailable()) {
            onEvent(Event.Error("Распознавание речи на телефоне недоступно"))
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onEvent(Event.Error("Нет доступа к микрофону"))
            return
        }
        probeOnDeviceSupport()
        begin(onEvent)
    }

    /** Закончить запись: распознаватель выдаст результат по уже сказанному. */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    /** Прервать без результата. */
    fun cancel() {
        session++
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        session++
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun begin(onEvent: (Event) -> Unit) {
        val rec = obtain(wantOnDevice()) ?: run {
            onEvent(Event.Error("Не удалось запустить распознавание"))
            return
        }
        val id = ++session
        lastPartial = ""
        rec.setRecognitionListener(Listener(id, onEvent))
        try {
            rec.startListening(recognizerIntent(appContext))
        } catch (e: Exception) {
            Log.w(TAG, "startListening", e)
            onEvent(Event.Error("Не удалось включить микрофон"))
        }
    }

    private fun wantOnDevice(): Boolean {
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        val cloud = SpeechRecognizer.isRecognitionAvailable(appContext)
        return when {
            !onDevice -> false
            !cloud -> true
            else -> !preferCloud(appContext)
        }
    }

    private fun obtain(onDevice: Boolean): SpeechRecognizer? {
        recognizer?.let {
            if (recognizerOnDevice == onDevice) return it
            runCatching { it.destroy() }
            recognizer = null
        }
        val r = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            else SpeechRecognizer.createSpeechRecognizer(appContext)
        }.onFailure { Log.w(TAG, "create recognizer (onDevice=$onDevice)", it) }.getOrNull()
        recognizer = r
        recognizerOnDevice = onDevice
        return r
    }

    private inner class Listener(private val id: Int, private val onEvent: (Event) -> Unit) : RecognitionListener {
        private val live get() = id == session

        override fun onReadyForSpeech(params: Bundle?) {
            if (live) onEvent(Event.Ready)
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (live) onLevel?.invoke(rmsdB)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() } ?: return
            lastPartial = text
            onEvent(Event.Partial(text))
        }

        override fun onResults(results: Bundle?) {
            if (!live) return
            session++ // сессия закончена
            val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                .filter { it.isNotBlank() }
                .ifEmpty { listOfNotNull(lastPartial.takeIf { it.isNotBlank() }) }
            if (alts.isEmpty()) onEvent(Event.Error(errorMessage(SpeechRecognizer.ERROR_NO_MATCH)))
            else onEvent(Event.Final(alts))
        }

        override fun onError(error: Int) {
            if (!live) return
            Log.i(TAG, "recognizer error $error (onDevice=$recognizerOnDevice)")
            val languageProblem = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            when {
                // Модели es-ES на устройстве нет — переходим на обычный (облачный) распознаватель и запоминаем это.
                languageProblem && recognizerOnDevice && !fellBackToCloud &&
                    SpeechRecognizer.isRecognitionAvailable(appContext) -> {
                    fellBackToCloud = true
                    recognizer?.let { requestModelDownload(it) }
                    setPreferCloud(appContext, true)
                    begin(onEvent)
                }
                // Распознаватель занят/отключился — пересоздаём и пробуем ещё раз.
                (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) &&
                    retries < 1 -> {
                    retries++
                    recognizer?.let { runCatching { it.destroy() } }
                    recognizer = null
                    begin(onEvent)
                }
                // Что-то уже было распознано — засчитываем это как окончательный результат.
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                    lastPartial.isNotBlank() -> {
                    session++
                    onEvent(Event.Final(listOf(lastPartial)))
                }
                else -> {
                    session++
                    onEvent(Event.Error(errorMessage(error)))
                }
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Один раз за запуск приложения спрашиваем у распознавателя на устройстве, установлена ли модель es-ES.
     * Установлена — снова предпочитаем офлайн; можно скачать — запускаем загрузку и пока работаем через облако.
     */
    private fun probeOnDeviceSupport() {
        if (probed || !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) return
        probed = true
        val probe = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }.getOrNull() ?: return
        val intent = recognizerIntent(appContext)
        runCatching {
            probe.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(appContext), object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installed = support.installedOnDeviceLanguages.any { it.equals(LANG, ignoreCase = true) }
                    val pending = support.pendingOnDeviceLanguages.any { it.equals(LANG, ignoreCase = true) }
                    val supported = support.supportedOnDeviceLanguages.any { it.equals(LANG, ignoreCase = true) }
                    Log.i(TAG, "on-device es-ES: installed=$installed pending=$pending supported=$supported")
                    if (installed) {
                        setPreferCloud(appContext, false)
                    } else if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                        setPreferCloud(appContext, true)
                        if (supported && !pending) requestModelDownload(probe, intent)
                    }
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

    private fun requestModelDownload(rec: SpeechRecognizer, intent: Intent = recognizerIntent(appContext)) {
        if (downloadRequested) return
        downloadRequested = true
        // Не блокирует: система качает модель в фоне (может показать своё уведомление).
        runCatching { rec.triggerModelDownload(intent) }.onFailure { Log.w(TAG, "triggerModelDownload", it) }
    }

    companion object {
        private const val TAG = "SpeechInput"
        private const val LANG = "es-ES"
        private const val PREFS = "lectura_speech"
        private const val KEY_CLOUD = "preferCloud"

        private var probed = false
        private var downloadRequested = false
        private var cloudCache: Boolean? = null

        private fun preferCloud(ctx: Context): Boolean =
            cloudCache ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLOUD, false)
                .also { cloudCache = it }

        private fun setPreferCloud(ctx: Context, v: Boolean) {
            if (cloudCache == v) return
            cloudCache = v
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLOUD, v).apply()
        }

        private fun recognizerIntent(ctx: Context) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Ученик говорит медленно, с паузами — не обрываем запись слишком рано.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
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
        val lower = s.lowercase(SPANISH)
        val plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(MARKS, "")
        return plain.replace(NON_WORD, " ").split(' ')
            .filter { it.isNotEmpty() }
            .flatMap { expandNumber(it) }
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
        // Мелкая оговорка/ошибка распознавания в длинном слове не считается промахом.
        minOf(a.length, b.length) >= 6 && editDistanceAtMostOne(a, b) -> true
        else -> false
    }

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
        val n = w.toIntOrNull() ?: return listOf(w)
        if (n == 0 && w.length > 1) return emptyList() // «8:00» → «ocho»
        if (n > 9999) return listOf(w)
        return spanishNumber(n).split(' ')
    }

    private fun spanishNumber(n: Int): String = when {
        n < 30 -> SMALL[n]
        n < 100 -> TENS[n / 10] + if (n % 10 != 0) " y " + SMALL[n % 10] else ""
        n == 100 -> "cien"
        n < 1000 -> HUNDREDS[n / 100] + if (n % 100 != 0) " " + spanishNumber(n % 100) else ""
        else -> (if (n / 1000 == 1) "mil" else spanishNumber(n / 1000) + " mil") +
            if (n % 1000 != 0) " " + spanishNumber(n % 1000) else ""
    }

    private val SPANISH = Locale.forLanguageTag("es-ES")
    private val WHITESPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val UNO = setOf("un", "uno", "una")
    private val SMALL = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
        "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve",
        "veinte", "veintiuno", "veintidos", "veintitres", "veinticuatro", "veinticinco", "veintiseis",
        "veintisiete", "veintiocho", "veintinueve",
    )
    private val TENS = listOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val HUNDREDS = listOf(
        "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
        "seiscientos", "setecientos", "ochocientos", "novecientos",
    )
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
fun SpeakButton(expected: String, modifier: Modifier = Modifier, onResult: (MatchResult) -> Unit) {
    val context = LocalContext.current
    val input = remember { SpeechInput(context) }
    var ui by remember(expected) { mutableStateOf<SpeakUi>(SpeakUi.Idle) }
    var level by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val currentExpected by rememberUpdatedState(expected)
    val lifecycleOwner = LocalLifecycleOwner.current

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
        input.start { ev ->
            if (exp != currentExpected) return@start
            when (ev) {
                SpeechInput.Event.Ready -> (ui as? SpeakUi.Listening)?.let { ui = it.copy(ready = true) }
                is SpeechInput.Event.Partial -> ui = SpeakUi.Listening(ev.text, ready = true)
                is SpeechInput.Event.Final -> {
                    val r = SpeechMatch.compare(exp, ev.alternatives)
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
                    SpeakUi.Idle -> Text("Нажмите и скажите вслух", style = MaterialTheme.typography.bodyLarge)
                    is SpeakUi.Listening -> {
                        Text(
                            if (state.ready) "Говорите… (нажмите, чтобы закончить)" else "Включаю микрофон…",
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
