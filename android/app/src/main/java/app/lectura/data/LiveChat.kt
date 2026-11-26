package app.lectura.data

import android.content.Context
import android.util.Log
import app.lectura.model.ABOUT_QUESTIONS
import app.lectura.model.Character
import app.lectura.model.ChatBrief
import app.lectura.model.ChatPair
import app.lectura.model.ChatReply
import app.lectura.model.ChatTopic
import app.lectura.model.ChatVoice
import app.lectura.model.Phrase
import app.lectura.playback.ClipPlayer
import app.lectura.speech.SpeechTts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.SecureRandom

/** Реплика в разговоре. */
data class ChatMsg(
    val key: Int,
    val mine: Boolean,
    val text: String,
    /** Перевод реплики собеседника. */
    val ru: String = "",
    /** Для реплики ученика: как сказать правильно. */
    val fix: ChatPair? = null,
    /** Незнакомые слова из реплики собеседника. */
    val words: List<ChatPair> = emptyList(),
    /** Собеседник ещё «печатает». */
    val pending: Boolean = false,
    /** Не дошло до Mac — можно повторить. */
    val failed: Boolean = false,
    /** Сколько Mac озвучивал каждое предложение, мс (для сравнения режимов). */
    val audioMs: List<Int> = emptyList(),
    val whisper: Boolean = false,
    /** Первый токен / весь ответ модели, мс. */
    val firstMs: Int = 0,
    val totalMs: Int = 0,
)

data class ChatState(
    val id: String,
    val topic: ChatTopic,
    val level: String,
    val messages: List<ChatMsg> = emptyList(),
    /** Ждём ответа собеседника. */
    val busy: Boolean = false,
    val hints: List<ChatPair> = emptyList(),
    val chunks: List<ChatPair> = emptyList(),
    val sample: ChatPair? = null,
    val brief: ChatBrief? = null,
    /** «Перед разговором» is shown (opens at the start, 📋 reopens it). */
    val briefOpen: Boolean = false,
    val goalsDone: Set<String> = emptySet(),
    val ended: Boolean = false,
    val error: String = "",
)

/**
 * «Живой разговор» с персонажем на Mac (pipeline/chat.py через server.py).
 *
 * Реплика уходит POST-запросом и ждёт итог (ответ, перевод, исправление, подсказки); пока модель пишет,
 * по WebSocket ([MacLink.chatEvents]) приходят текст по мере набора и готовые предложения — их сразу
 * читает голос телефона или (с голосом Mac) Mac присылает клип на каждое предложение.
 * Разговор живёт здесь (не в экране), поэтому выход в другие вкладки его не обрывает.
 */
class LiveChat(
    private val context: Context,
    private val mac: MacLink,
    private val prefs: Prefs,
    private val db: AppDatabase,
    private val userData: UserData,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow<ChatState?>(null)
    val state: StateFlow<ChatState?> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var tts: SpeechTts? = null
    private var clips: ClipPlayer? = null
    private val dir = File(context.cacheDir, "chat")

    /** Последний ход сервера, который уже пришёл целиком (события старше игнорируем). */
    private var serverTurn = 0
    /** Ход, чьи предложения сейчас можно играть (-1 — ничего). */
    @Volatile private var playTurn = -1
    /** Голос текущего хода: выбирается при отправке. */
    private var turnVoice = ChatVoice.NONE
    private var turnWhisper = false
    /** Реплику целиком уже отдали голосу телефона (предложения по WebSocket не пришли вовремя). */
    private var wholeQueued = false
    /** Скачивание клипов идёт параллельно, а в очередь они встают строго по порядку. */
    private var lastDownload: Job? = null
    /** Предложения текущего хода по номеру — для голоса телефона и запасного чтения. */
    private val sentences = HashMap<Int, String>()
    private var nextKey = 0
    /** The opening line waits until the learner closes «Перед разговором». */
    private val held = MutableStateFlow(false)

    private data class Play(val turn: Int, val text: String, val file: File?)
    private var queue = Channel<Play>(Channel.UNLIMITED)
    /** Клипы Mac по ключу реплики — для «послушать ещё раз». */
    private val msgClips = HashMap<Int, MutableList<File>>()

    var voice: ChatVoice
        get() = ChatVoice.of(prefs.chatVoice)
        set(v) {
            prefs.chatVoice = v.key
            if (v == ChatVoice.NONE) stopAudio()
        }

    var whisper: Boolean
        get() = prefs.chatWhisper
        set(v) {
            prefs.chatWhisper = v
        }

    // ---------------------------------------------------------------- жизненный цикл

    fun start(topic: ChatTopic, level: String) {
        end()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        dir.deleteRecursively()
        dir.mkdirs()
        serverTurn = 0
        nextKey = 0
        msgClips.clear()
        tts = SpeechTts(context).also { it.setCharacters(listOf(Character(PARTNER, topic.gender))) }
        clips = ClipPlayer(context)
        queue = Channel(Channel.UNLIMITED)
        val id = newId()
        _state.value = ChatState(id, topic, level, busy = true, briefOpen = true)
        held.value = true
        s.launch { mac.chatEvents.collect { onEvent(it) } }
        s.launch { playLoop() }
        s.launch { send(null) }
    }

    /** Закончить разговор (Mac закроет процесс модели). */
    fun end() {
        val st = _state.value
        _state.value = null
        held.value = false
        stopAudio()
        scope?.cancel()
        scope = null
        tts?.shutdown()
        tts = null
        clips?.release()
        clips = null
        if (st != null) {
            CoroutineScope(Dispatchers.IO).launch { mac.postJson("/chat/${st.id}/end", "{}") }
        }
    }

    // ---------------------------------------------------------------- реплики

    /** Отправить реплику ученика. */
    fun say(text: String, spoken: Boolean) {
        val t = text.trim()
        val st = _state.value ?: return
        if (t.isEmpty() || st.busy) return
        val s = scope ?: return
        s.launch {
            if (spoken) runCatching { userData.recordSpeaking() }
            send(t)
        }
    }

    /** Повторить то, что не дошло: начало разговора или последнюю реплику ученика. */
    fun retry() {
        val st = _state.value ?: return
        if (st.busy) return
        val failed = st.messages.lastOrNull()?.takeIf { it.mine && it.failed }
        if (st.messages.isNotEmpty() && failed == null) return
        _state.update { it?.copy(messages = it.messages.filter { m -> m.key != failed?.key }) }
        scope?.launch { send(failed?.text) }
    }

    /** text == null — начало разговора (собеседник говорит первым). */
    private suspend fun send(text: String?) {
        val st = _state.value ?: return
        stopAudio()
        turnVoice = voice.let { if (it == ChatVoice.MAC && !mac.state.value.macAudio) ChatVoice.PHONE else it }
        turnWhisper = whisper
        sentences.clear()
        wholeQueued = false
        lastDownload = null
        val myKey = if (text != null) nextKey++ else -1
        val replyKey = nextKey++
        playTurn = serverTurn + 1
        _state.update {
            it?.copy(
                busy = true, error = "", hints = emptyList(), chunks = emptyList(), sample = null,
                messages = it.messages + listOfNotNull(
                    text?.let { t -> ChatMsg(myKey, mine = true, text = t) },
                    ChatMsg(replyKey, mine = false, text = "", pending = true),
                ),
            )
        }
        val audio = turnVoice == ChatVoice.MAC
        val path: String
        val body: String
        if (text == null) {
            pushProgress()
            path = "/chat/start"
            body = json.encodeToString(StartBody(st.id, st.level, st.topic, about(), audio = audio, whisper = turnWhisper))
        } else {
            path = "/chat/${st.id}/say"
            body = json.encodeToString(SayBody(text, audio, turnWhisper))
        }
        var reply = mac.postJson(path, body)
        if (reply?.code == 404 && text != null) {
            // Mac перезапускали — разговор начинаем заново с той же историей.
            pushProgress()
            serverTurn = 0
            playTurn = 1
            reply = mac.postJson(
                "/chat/start",
                json.encodeToString(
                    StartBody(st.id, st.level, st.topic, about(), history(), text, audio, turnWhisper),
                ),
            )
        }
        val r = reply?.takeIf { it.code == 200 }?.let { runCatching { json.decodeFromString<ChatReply>(it.body) }.getOrNull() }
        if (r == null) {
            val why = when {
                reply == null -> "Mac не на связи"
                reply.code == 502 -> "Mac не получил ответ модели"
                else -> "Mac ответил ошибкой ${reply.code}"
            }
            _state.update { s ->
                s?.copy(
                    busy = false, error = why,
                    messages = s.messages.filter { it.key != replyKey }.map { if (it.key == myKey) it.copy(failed = true) else it },
                )
            }
            return
        }
        serverTurn = r.turn
        _state.update { s ->
            s?.copy(
                busy = false,
                hints = r.hints,
                chunks = r.chunks,
                sample = r.sample,
                brief = r.brief ?: s.brief,
                goalsDone = s.goalsDone + r.goals_done,
                ended = r.end,
                messages = s.messages.map {
                    when (it.key) {
                        replyKey -> it.copy(
                            text = r.reply, ru = r.ru, words = r.words, pending = false,
                            firstMs = r.firstMs, totalMs = r.totalMs, whisper = turnWhisper && turnVoice == ChatVoice.MAC,
                        )
                        myKey -> it.copy(fix = r.fix)
                        else -> it
                    }
                },
            )
        }
        // Предложения не пришли по WebSocket (соединение моргнуло) или Mac не озвучит — читает телефон.
        if (turnVoice == ChatVoice.MAC && !r.audio) turnVoice = ChatVoice.PHONE
        if (turnVoice == ChatVoice.PHONE && sentences.isEmpty() && playTurn == r.turn) {
            wholeQueued = true
            enqueue(Play(r.turn, r.reply, null))
        }
    }

    /**
     * Progress snapshot to the Mac (pipeline/learner.py) before a conversation starts: the character then knows
     * which words to repeat, which frequent words are new and which are known. An older Mac (404) or an error
     * just means a conversation without it.
     */
    private suspend fun pushProgress() {
        runCatching {
            val snap = withContext(Dispatchers.IO) { ProgressSnapshot.build(db) }
            mac.postJson("/progress", json.encodeToString(snap))
        }.onFailure { runCatching { Log.w("LiveChat", "progress", it) } }
    }

    private fun currentReplyKey(): Int? = _state.value?.messages?.lastOrNull { !it.mine }?.key

    /** События текущего хода; ход, который ученик прервал (playTurn сброшен), молчит. */
    private fun onEvent(ev: JsonObject) {
        val st = _state.value ?: return
        if (ev["chat"]?.jsonPrimitive?.content != st.id) return
        val turn = ev["turn"]?.jsonPrimitive?.intOrNull ?: return
        val type = ev["type"]?.jsonPrimitive?.content
        if (type == "chat.delta") {
            if (turn <= serverTurn) return
        } else if (turn != playTurn) {
            return
        }
        when (type) {
            "chat.delta" -> {
                val text = ev["text"]?.jsonPrimitive?.content ?: return
                _state.update { s ->
                    s?.copy(messages = s.messages.map { if (!it.mine && it.pending) it.copy(text = text) else it })
                }
            }
            "chat.sentence" -> {
                val seq = ev["seq"]?.jsonPrimitive?.intOrNull ?: return
                val text = ev["text"]?.jsonPrimitive?.content ?: return
                sentences[seq] = text
                if (turnVoice == ChatVoice.PHONE && !wholeQueued) enqueue(Play(turn, text, null))
            }
            "chat.audio" -> {
                if (turnVoice != ChatVoice.MAC) return
                val name = ev["name"]?.jsonPrimitive?.content ?: return
                val seq = ev["seq"]?.jsonPrimitive?.intOrNull ?: 0
                val ms = ev["ms"]?.jsonPrimitive?.intOrNull ?: 0
                val key = currentReplyKey() ?: return
                _state.update { s ->
                    s?.copy(messages = s.messages.map { if (it.key == key) it.copy(audioMs = it.audioMs + ms) else it })
                }
                val file = File(dir, "$name.wav")
                val prev = lastDownload
                lastDownload = scope?.launch {
                    val ok = mac.download("/chat/audio/$name", file)
                    prev?.join()
                    if (ok) msgClips.getOrPut(key) { mutableListOf() }.add(file)
                    enqueue(Play(turn, sentences[seq] ?: "", file.takeIf { ok }))
                }
            }
            "chat.audio_error" -> {
                if (turnVoice != ChatVoice.MAC) return
                val seq = ev["seq"]?.jsonPrimitive?.intOrNull ?: return
                sentences[seq]?.let { enqueue(Play(turn, it, null)) }
            }
        }
    }

    // ---------------------------------------------------------------- звук

    private fun enqueue(p: Play) {
        if (voice == ChatVoice.NONE) return
        queue.trySend(p)
    }

    /** Show or close «Перед разговором». Closing it lets the opening line speak (it waits while you read). */
    fun showBrief(open: Boolean) {
        _state.update { it?.copy(briefOpen = open) }
        if (!open) held.value = false
    }

    private suspend fun playLoop() {
        for (p in queue) {
            held.first { !it }
            if (p.turn != playTurn) continue
            val file = p.file
            // One broken clip must not end the loop: then the rest of the conversation would be silent.
            try {
                if (file != null) {
                    clips?.play(file.path, p.text)
                } else if (p.text.isNotBlank()) {
                    tts?.speak(p.text, prefs.rate, PARTNER)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LiveChat", "play", e)
            }
        }
    }

    /** Замолчать (ученик начал говорить или отправил реплику). */
    fun stopAudio() {
        playTurn = -1
        while (queue.tryReceive().isSuccess) Unit
        clips?.stop()
        tts?.stop()
    }

    /** Послушать реплику ещё раз: клипы Mac, если есть, иначе голос телефона. */
    fun replay(msg: ChatMsg) {
        stopAudio()
        val turn = -2
        playTurn = turn
        val files = msgClips[msg.key].orEmpty().filter { it.exists() }
        if (files.isNotEmpty()) {
            files.forEach { queue.trySend(Play(turn, "", it)) }
        } else {
            queue.trySend(Play(turn, msg.text, null))
        }
    }

    /** Прочитать подсказку или исправление голосом телефона (сразу, без очереди). */
    fun speak(text: String) {
        stopAudio()
        playTurn = -3
        queue.trySend(Play(-3, text, null))
    }

    // ---------------------------------------------------------------- итог

    /** Исправленные фразы разговора → в повторение. Возвращает число новых карточек. */
    suspend fun saveFixes(): Int {
        val st = _state.value ?: return 0
        val list = st.messages.mapNotNull { it.fix }.distinctBy { it.es.lowercase() }
            .map { Phrase(id = "", es = it.es, ru = it.ru.ifBlank { it.es }) }
        if (list.isEmpty()) return 0
        return userData.addPhrases(list, label = "разговор: ${st.topic.title}")
    }

    // ---------------------------------------------------------------- данные для Mac

    /** Ответы «Обо мне» — собеседник знает, кто вы (последний ответ на каждый вопрос). */
    private suspend fun about(): List<AboutFact> = runCatching {
        val items = db.inbox().allOnce().filter { it.kind == InboxItem.ABOUT }
        items.groupBy { it.ref }.mapNotNull { (ref, list) ->
            val q = ABOUT_QUESTIONS.firstOrNull { it.id == ref } ?: return@mapNotNull null
            val a = list.maxBy { it.createdAt }.ru.trim()
            if (a.isEmpty()) null else AboutFact(q.ru, a)
        }
    }.onFailure { Log.w("LiveChat", "about", it) }.getOrDefault(emptyList())

    private fun history(): List<HistoryItem> {
        val msgs = _state.value?.messages.orEmpty().filter { !it.pending && !it.failed }
        val out = ArrayList<HistoryItem>()
        var user = "[START]"
        for (m in msgs) {
            if (m.mine) user = m.text else {
                out += HistoryItem(user, m.text)
                user = ""
            }
        }
        return out
    }

    @Serializable
    private data class AboutFact(val q: String, val a: String)

    @Serializable
    private data class HistoryItem(val user: String, val reply: String)

    @Serializable
    private data class StartBody(
        val chat: String,
        val level: String,
        val topic: ChatTopic,
        val about: List<AboutFact>,
        val history: List<HistoryItem> = emptyList(),
        val text: String = "",
        val audio: Boolean = false,
        val whisper: Boolean = false,
    )

    @Serializable
    private data class SayBody(val text: String, val audio: Boolean, val whisper: Boolean)

    companion object {
        private const val PARTNER = "partner"

        /** id разговора: 12 hex-символов (сервер проверяет ^[a-f0-9]{8,32}$). */
        fun newId(): String {
            val b = ByteArray(6)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
