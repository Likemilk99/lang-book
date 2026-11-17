package app.lectura.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import app.lectura.model.Character
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Свой системный TTS для упражнений на говорение: в отличие от Player умеет дождаться конца фразы
 * (`speak` возвращается, когда фраза прозвучала) и озвучивать персонажей разными голосами.
 */
class SpeechTts(context: Context) {
    private data class VoiceSpec(val voice: Voice?, val pitch: Float)

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()
    private val ids = AtomicInteger()

    /** Нет испанского (Испания) голоса — стоит предложить установить. */
    @Volatile
    var voiceMissing: Boolean = false
        private set

    private var pool: List<Voice> = emptyList()
    private var narrator: Voice? = null
    private var characters: List<Character> = emptyList()
    private val cast = HashMap<String, VoiceSpec>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    init {
        synchronized(live) { live += this }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            voiceMissing = true
            ready.complete(false)
            return
        }
        runCatching {
            val res = tts.setLanguage(SPAIN)
            voiceMissing = res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED
            pool = spanishVoices()
            narrator = pool.firstOrNull()
            narrator?.let { tts.voice = it }
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId, true)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId, false)
            })
            Log.i(TAG, "voices es-ES: ${pool.joinToString { it.name + "/" + (genderOf(it) ?: "?") }}")
        }.onFailure { Log.w(TAG, "init", it) }
        ready.complete(true)
    }

    /** Персонажи книги — для подбора голосов по полу. */
    fun setCharacters(list: List<Character>) {
        synchronized(cast) {
            characters = list
            cast.clear()
        }
    }

    /** Дождаться готовности движка. false — TTS недоступен. */
    suspend fun awaitReady(): Boolean = ready.await()

    /**
     * Произнести фразу и дождаться её конца. `speaker` — имя персонажа (Sentence.sp) или null для автора.
     * Возвращает true, если фраза прозвучала до конца; false — прервана (stop/отмена) или ошибка.
     * Отмена корутины останавливает речь.
     */
    suspend fun speak(text: String, rate: Float = 1f, speaker: String? = null): Boolean {
        if (text.isBlank()) return true
        if (!ready.await()) return false
        return suspendCancellableCoroutine { cont ->
            val id = "lt-${ids.incrementAndGet()}"
            pending[id] = cont
            cont.invokeOnCancellation {
                if (pending.remove(id) != null) runCatching { tts.stop() }
            }
            val spec = specFor(speaker)
            runCatching {
                (spec.voice ?: narrator)?.let { if (tts.voice?.name != it.name) tts.voice = it }
                tts.setPitch(spec.pitch)
                tts.setSpeechRate(rate)
            }.onFailure { Log.w(TAG, "voice setup", it) }
            val r = runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }.getOrDefault(TextToSpeech.ERROR)
            if (r != TextToSpeech.SUCCESS) finish(id, false)
        }
    }

    fun stop() {
        pending.keys.toList().forEach { finish(it, false) }
        runCatching { tts.stop() }
    }

    fun shutdown() {
        stop()
        synchronized(live) { live -= this }
        runCatching { tts.shutdown() }
    }

    private fun finish(id: String?, ok: Boolean) {
        val cont = id?.let { pending.remove(it) } ?: return
        if (cont.isActive) cont.resume(ok)
    }

    /** Установленные голоса es-ES (по одному на «базовое» имя), лучшие офлайновые — первыми. */
    private fun spanishVoices(): List<Voice> = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == "es" && it.locale.country == "ES" }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )
            .distinctBy { baseName(it) }
    }.getOrDefault(emptyList())

    private fun specFor(speaker: String?): VoiceSpec {
        val narratorSpec = VoiceSpec(narrator, 1f)
        val name = speaker?.trim()?.takeIf { it.isNotEmpty() } ?: return narratorSpec
        synchronized(cast) {
            cast[name]?.let { return it }
            if (cast.isEmpty()) buildCast()
            return cast.getOrPut(name) { castUnknown(name) }
        }
    }

    /**
     * Детерминированно раздаём голоса персонажам книги: по полу, отличные от голоса автора,
     * разным персонажам одного пола — разные голоса. Если подходящих голосов нет — меняем высоту тона.
     */
    private fun buildCast() {
        val others = pool.filter { it != narrator }.ifEmpty { pool }
        val used = HashMap<String, Int>() // сколько раз уже выдан голос
        for (c in characters) {
            val g = c.gender.lowercase().takeIf { it == "m" || it == "f" }
            val byGender = if (g == null) emptyList() else others.filter { genderOf(it) == g }
            val candidates = byGender.ifEmpty { others }
            if (candidates.isEmpty()) {
                cast[c.name] = VoiceSpec(narrator, pitchFor(g, voiceGender = null, repeat = 0))
                continue
            }
            // Голос, который ещё никому не выдан (или выдан реже всего); при равенстве — по порядку.
            val v = candidates.minBy { used[it.name] ?: 0 }
            val repeat = used[v.name] ?: 0
            used[v.name] = repeat + 1
            cast[c.name] = VoiceSpec(v, pitchFor(g, genderOf(v), repeat))
        }
    }

    private fun castUnknown(name: String): VoiceSpec {
        val others = pool.filter { it != narrator }
        if (others.isEmpty()) return VoiceSpec(narrator, if (Math.floorMod(name.hashCode(), 2) == 0) 0.9f else 1.1f)
        return VoiceSpec(others[Math.floorMod(name.hashCode(), others.size)], 1f)
    }

    /** Высота тона: подстроить под пол, если голос «не того» пола/неизвестен, и развести повторяющиеся голоса. */
    private fun pitchFor(gender: String?, voiceGender: String?, repeat: Int): Float {
        val base = when {
            gender == null || gender == voiceGender -> 1f
            gender == "m" -> 0.82f
            else -> 1.18f
        }
        val shift = when (repeat % 3) {
            0 -> 0f
            1 -> -0.08f
            else -> 0.08f
        }
        return (base + shift).coerceIn(0.6f, 1.5f)
    }

    companion object {
        private const val TAG = "SpeechTts"
        private val SPAIN: Locale = Locale.forLanguageTag("es-ES")
        private val live: MutableSet<SpeechTts> = Collections.newSetFromMap(WeakHashMap())

        /**
         * Пол голосов Google TTS для es-ES (имена вида «es-es-x-eed-local»): API пола не сообщает.
         * Предполагаемое соответствие (на устройстве не проверено); для чужих движков — эвристика по имени/features.
         * Если пол угадан неверно, персонаж всё равно получит отдельный голос, просто не того пола.
         */
        private val GOOGLE_GENDER = mapOf("eea" to "f", "eec" to "f", "eed" to "m", "eef" to "m")

        /** Замолчать всем экземплярам (например, перед записью с микрофона). */
        fun stopAll() {
            val all = synchronized(live) { live.toList() }
            all.forEach { it.stop() }
        }

        private fun baseName(v: Voice): String =
            v.name.lowercase().removeSuffix("-local").removeSuffix("-network")

        internal fun genderOf(v: Voice): String? {
            val n = v.name.lowercase()
            val feats = v.features.orEmpty().map { it.lowercase() }
            fun has(word: String) = word in n || feats.any { word in it }
            return when {
                has("female") -> "f"
                has("male") -> "m"
                else -> GOOGLE_GENDER[baseName(v).substringAfter("-x-", "").substringBefore('-')]
            }
        }
    }
}
