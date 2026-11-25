package app.lectura.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.lectura.model.InboxFile
import app.lectura.model.PersonalResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * Связь с pipeline/server.py на Mac в домашней сети («Мои фразы» без кабеля).
 *
 * Пока открыта вкладка «Говорить» ([acquire]/[release]), ищем Mac через Bonjour (`_lectura._tcp`) —
 * или по адресу, заданному вручную, — и держим WebSocket: сервер присылает статус очереди и «готово»
 * по каждой записи. Ждущие записи уходят на Mac через POST /sync, готовые переводы с озвучкой
 * возвращаются в files/personal/ и дальше идут обычным путём [PersonalRepository.sync].
 */
class MacLink(
    private val context: Context,
    private val prefs: Prefs,
    private val db: AppDatabase,
    private val personal: PersonalRepository,
    private val scope: CoroutineScope,
) {
    enum class Status { SEARCHING, OFFLINE, NEED_CODE, IDLE, BUSY }

    data class State(
        val status: Status = Status.OFFLINE,
        /** Записей в работе на Mac (включая текущую). */
        val queue: Int = 0,
        /** Что Mac делает сейчас: «перевожу», «озвучиваю», «готовлю голос». */
        val phase: String = "",
        val current: String = "",
        val host: String = "",
        /** Последняя ошибка перевода на Mac (запись повторится при следующей синхронизации). */
        val error: String = "",
        /** Код задан, но Mac его не принял. */
        val wrongCode: Boolean = false,
        /** Mac умеет озвучивать (Qwen3-TTS загружен, сервер без --no-audio). */
        val macAudio: Boolean = false,
        /** Сервер поддерживает «Живой разговор» (старый server.py — нет). */
        val chat: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _arrived = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** Сколько фраз только что добавилось в повторение. */
    val arrived: SharedFlow<Int> = _arrived.asSharedFlow()

    private val _chatEvents = MutableSharedFlow<JsonObject>(extraBufferCapacity = 512)
    /** События «Живого разговора» с Mac (type = "chat.delta" | "chat.sentence" | "chat.audio" | "chat.audio_error"). */
    val chatEvents: SharedFlow<JsonObject> = _chatEvents.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    /** Реплика разговора ждёт ответа модели — дольше обычного. */
    private val chatHttp = http.newBuilder().readTimeout(150, TimeUnit.SECONDS).build()
    private val wsClient = http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS) // Mac пропал (уснул, вы ушли из дома) — заметим за ~10–20 с
        .build()

    private var users = 0
    private var job: Job? = null
    private var stopJob: Job? = null
    private var kick = CompletableDeferred<Unit>()
    private val syncMutex = Mutex()

    /** Экран, которому нужен статус, стал видимым (вызывать с главного потока). */
    fun acquire() {
        users++
        stopJob?.cancel()
        stopJob = null
        if (job?.isActive != true) job = scope.launch { loop() }
    }

    /** Экран скрыт. Соединение закрывается не сразу — переход между экранами «Говорить» его не рвёт. */
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        stopJob?.cancel()
        stopJob = scope.launch(Dispatchers.Main) {
            delay(5_000)
            if (users == 0) {
                job?.cancel()
                job = null
                _state.value = State()
            }
        }
    }

    /** Код или адрес изменились — переподключиться сразу. */
    fun reconnect() {
        kick.complete(Unit)
    }

    private suspend fun loop() {
        _state.value = State(Status.SEARCHING)
        while (currentCoroutineContext().isActive) {
            kick = CompletableDeferred()
            val host = findHost()
            val outcome = if (host == null) Outcome.NOT_FOUND else connect(host)
            _state.update {
                when (outcome) {
                    Outcome.CODE -> State(Status.NEED_CODE, host = host.orEmpty(), wrongCode = prefs.macCode.isNotBlank())
                    else -> State(Status.OFFLINE)
                }
            }
            // Ждём либо паузу перед новой попыткой, либо новый код/адрес из диалога.
            withTimeoutOrNull(if (outcome == Outcome.CODE) 60_000 else 5_000) { kick.await() }
        }
    }

    private enum class Outcome { NOT_FOUND, CODE, CLOSED }

    /** Адрес вручную → Bonjour → где Mac был в прошлый раз. */
    private suspend fun findHost(): String? {
        parseAddress(prefs.macAddress)?.let { return it }
        return discover() ?: parseAddress(prefs.macLastHost)
    }

    private suspend fun discover(): String? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val found = CompletableDeferred<String>()
        val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                    override fun onServiceUpdated(si: NsdServiceInfo) {
                        val ip = si.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return
                        found.complete("$ip:${si.port}")
                    }
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                synchronized(callbacks) { callbacks += cb }
                runCatching { nsd.registerServiceInfoCallback(info, context.mainExecutor, cb) }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        if (runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery) }.isFailure) return null
        return try {
            withTimeoutOrNull(4_000) { found.await() }
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
            synchronized(callbacks) { callbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } } }
        }
    }

    /** Держит WebSocket, пока он жив; синхронизирует записи при подключении, изменениях и «готово». */
    private suspend fun connect(host: String): Outcome {
        val code = prefs.macCode
        if (code.isBlank()) return Outcome.CODE
        val closed = CompletableDeferred<Outcome>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                prefs.macLastHost = host
                _state.value = State(Status.IDLE, host = host)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val type = msg["type"]?.jsonPrimitive?.content.orEmpty()
                if (type.startsWith("chat.")) {
                    _chatEvents.tryEmit(msg)
                    return
                }
                when (type) {
                    "hello" -> _state.update {
                        it.copy(
                            macAudio = msg["audio"]?.jsonPrimitive?.content == "true",
                            chat = msg["chat"]?.jsonPrimitive?.content == "true",
                        )
                    }
                    "status" -> {
                        val queue = msg["queue"]?.jsonPrimitive?.intOrNull ?: 0
                        val busy = msg["busy"]?.jsonPrimitive?.content == "true"
                        _state.update {
                            it.copy(
                                status = if (busy || queue > 0) Status.BUSY else Status.IDLE,
                                queue = queue,
                                phase = msg["phase"]?.jsonPrimitive?.content.orEmpty(),
                                current = msg["current"]?.jsonPrimitive?.content.orEmpty(),
                            )
                        }
                    }
                    "done" -> {
                        _state.update { it.copy(error = "") }
                        scope.launch { syncNow(host) }
                    }
                    "error" -> _state.update { it.copy(error = msg["text"]?.jsonPrimitive?.content.orEmpty()) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Outcome.CLOSED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(if (response?.code == 401) Outcome.CODE else Outcome.CLOSED)
            }
        }
        val ws = wsClient.newWebSocket(
            Request.Builder().url("http://$host/ws").header(HEADER, code).build(),
            listener,
        )
        // Любое изменение ждущих записей (новая фраза, правка ответа) — сразу на Mac.
        val watcher = scope.launch {
            db.inbox().all()
                .map { list -> list.filter { it.pending }.map { it.id to it.ru } }
                .distinctUntilChanged()
                .collect { if (_state.value.status in CONNECTED) syncNow(host) }
        }
        val opened = scope.launch {
            // Первая синхронизация — как только соединение открылось.
            while (_state.value.status !in CONNECTED && !closed.isCompleted) delay(100)
            if (!closed.isCompleted) syncNow(host)
        }
        return try {
            select {
                closed.onAwait { it }
                kick.onAwait { Outcome.CLOSED }
            }
        } finally {
            watcher.cancel()
            opened.cancel()
            ws.cancel()
        }
    }

    /** Отправить ждущие записи, забрать готовые переводы и озвучку. */
    private suspend fun syncNow(host: String) = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = personal.pendingEntries()
                if (entries.isEmpty()) return@runCatching
                val body = json.encodeToString(InboxFile.serializer(), InboxFile(entries))
                    .toRequestBody("application/json".toMediaType())
                val reply = http.newCall(
                    Request.Builder().url("http://$host/sync").header(HEADER, prefs.macCode).post(body).build(),
                ).execute().use { r ->
                    if (!r.isSuccessful) error("sync: HTTP ${r.code}")
                    json.decodeFromString<SyncReply>(r.body.string())
                }
                if (reply.results.isEmpty()) return@runCatching
                reply.results.forEach { download(host, it) }
                personal.addResults(reply.results)
                val n = personal.sync()
                if (n > 0) _arrived.emit(n)
            }.onFailure { runCatching { Log.w("MacLink", "sync", it) } }
        }
    }

    /** Озвучка результата: во временный файл и переименование — недокачанный файл не подхватится. */
    private fun download(host: String, r: PersonalResult) {
        for (name in clipNames(r)) {
            http.newCall(Request.Builder().url("http://$host/audio/$name").header(HEADER, prefs.macCode).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use // без озвучки (server.py --no-audio) — системный голос
                    personal.audioDir.mkdirs()
                    val tmp = File(personal.audioDir, "$name.part")
                    tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                    val dst = File(personal.audioDir, "$name.m4a")
                    dst.delete()
                    tmp.renameTo(dst)
                }
        }
    }

    /** Ответ сервера: HTTP-код и тело. */
    data class Reply(val code: Int, val body: String)

    /** POST JSON на Mac (для «Живого разговора»). null — Mac не на связи или запрос не дошёл. */
    suspend fun postJson(path: String, body: String): Reply? = withContext(Dispatchers.IO) {
        val host = _state.value.host.takeIf { _state.value.status in CONNECTED } ?: return@withContext null
        runCatching {
            chatHttp.newCall(
                Request.Builder().url("http://$host$path").header(HEADER, prefs.macCode)
                    .post(body.toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { Reply(it.code, it.body.string()) }
        }.onFailure { runCatching { Log.w("MacLink", path, it) } }.getOrNull()
    }

    /** Скачать файл с Mac во временный и переименовать. false — не получилось. */
    suspend fun download(path: String, dst: File): Boolean = withContext(Dispatchers.IO) {
        val host = _state.value.host.takeIf { it.isNotBlank() } ?: return@withContext false
        runCatching {
            http.newCall(Request.Builder().url("http://$host$path").header(HEADER, prefs.macCode).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    dst.parentFile?.mkdirs()
                    val tmp = File(dst.path + ".part")
                    tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                    dst.delete()
                    tmp.renameTo(dst)
                }
        }.getOrDefault(false)
    }

    @Serializable
    private data class SyncReply(val results: List<PersonalResult> = emptyList())

    companion object {
        const val SERVICE = "_lectura._tcp"
        const val PORT = 8765
        const val HEADER = "X-Lectura-Code"
        private val CONNECTED = setOf(Status.IDLE, Status.BUSY)

        /** «192.168.1.5:8765», «192.168.1.5» (порт по умолчанию), «macbook.local»; иначе null. */
        fun parseAddress(s: String): String? {
            val t = s.trim().removePrefix("http://").trimEnd('/')
            if (t.isEmpty() || t.any { it.isWhitespace() || it == '/' }) return null
            val host = t.substringBefore(':')
            val port = if (':' in t) t.substringAfter(':').toIntOrNull() ?: return null else PORT
            if (host.isEmpty() || port !in 1..65535) return null
            return "$host:$port"
        }

        /** Файлы озвучки результата — как в pipeline/personal.py clips_for. */
        fun clipNames(r: PersonalResult): List<String> =
            r.phrases.map { it.id } + if (r.kind == "about" && r.question.isNotBlank()) listOf("q-${r.ref}") else emptyList()
    }
}
