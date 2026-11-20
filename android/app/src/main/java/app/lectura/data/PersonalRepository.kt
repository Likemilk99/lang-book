package app.lectura.data

import android.content.Context
import android.util.Log
import app.lectura.model.InboxEntry
import app.lectura.model.InboxFile
import app.lectura.model.PersonalFile
import app.lectura.model.PersonalResult
import app.lectura.model.ABOUT_QUESTIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * «Мои фразы»: результаты с Mac лежат в files/personal/ — personal.json и audio/<имя>.m4a.
 * Доставка — по Wi-Fi от pipeline/server.py ([MacLink]) или по кабелю от pipeline/personal.py
 * (adb; для него приложение пишет inbox.json — записи, ждущие перевода).
 */
class PersonalRepository(
    context: Context,
    private val db: AppDatabase,
    private val userData: UserData,
    private val phrasebook: PhrasebookRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "personal").apply { mkdirs() }
    val audioDir = File(dir, "audio")
    private var loadedMtime = -1L

    private val _results = MutableStateFlow<List<PersonalResult>>(emptyList())
    /** Готовые переводы (все, что пришли с Mac). */
    val results: StateFlow<List<PersonalResult>> = _results.asStateFlow()

    /** Озвучка по имени без расширения, или null. */
    fun clip(name: String): String? = File(audioDir, "$name.m4a").takeIf { it.exists() }?.path

    /**
     * Свести всё вместе: выгрузить ждущие записи в inbox.json, прочитать personal.json (если изменился),
     * отметить готовые записи и добавить их фразы в повторение. Возвращает число новых фраз в повторении.
     */
    suspend fun sync(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val inbox = db.inbox().allOnce()
            exportInbox(inbox)
            val file = File(dir, "personal.json")
            if (file.exists() && file.lastModified() != loadedMtime) {
                runCatching { json.decodeFromString<PersonalFile>(file.readText()).items }
                    .onSuccess {
                        _results.value = it
                        loadedMtime = file.lastModified()
                    }
                    .onFailure { Log.w("Personal", "personal.json", it) }
            }
            val results = _results.value
            phrasebook.registerExtra(results.flatMap { it.phrases }) { clip(it) }
            val pending = inbox.filter { it.pending }
            val arrived = results.filter { r -> r.phrases.isNotEmpty() && pending.any { r.matches(it.id, it.ru) } }
            if (arrived.isEmpty()) return@withContext 0
            val added = userData.addPhrases(arrived.flatMap { it.phrases }, label = "мои фразы")
            userData.markInboxDone(arrived.map { it.inboxId })
            exportInbox(db.inbox().allOnce())
            added
        }
    }

    private fun exportInbox(items: List<InboxItem>) {
        runCatching { File(dir, "inbox.json").writeText(json.encodeToString(InboxFile.serializer(), InboxFile(entriesOf(items)))) }
            .onFailure { Log.w("Personal", "inbox.json", it) }
    }

    /** Записи, ждущие перевода, — в том виде, в каком их ждёт Mac. */
    suspend fun pendingEntries(): List<InboxEntry> = withContext(Dispatchers.IO) { entriesOf(db.inbox().allOnce()) }

    /**
     * Дописать пришедшие по Wi-Fi переводы в personal.json (старые не теряются, перевод той же записи
     * заменяется). Озвучка к этому моменту уже лежит в [audioDir]. Дальше всё делает [sync].
     */
    suspend fun addResults(new: List<PersonalResult>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(dir, "personal.json")
            val old = if (file.exists()) runCatching { json.decodeFromString<PersonalFile>(file.readText()).items }.getOrDefault(emptyList()) else emptyList()
            val tmp = File(dir, "personal.json.tmp")
            tmp.writeText(json.encodeToString(PersonalFile.serializer(), PersonalFile(merge(old, new))))
            if (!tmp.renameTo(file)) error("personal.json: не удалось записать")
            loadedMtime = -1L
        }
    }

    /** Результат для записи «Мои фразы», если перевод уже пришёл. */
    fun resultFor(item: InboxItem): PersonalResult? = _results.value.firstOrNull { it.matches(item.id, item.ru) }

    companion object {
        /** Команда на Mac: сервер «Мои фразы» для домашней сети. */
        const val MAC_COMMAND = "python3 pipeline/server.py"

        internal fun entriesOf(items: List<InboxItem>): List<InboxEntry> {
            val questions = ABOUT_QUESTIONS.associateBy { it.id }
            return items.filter { it.pending }.map { InboxEntry(it.id, it.kind, it.ref, questions[it.ref]?.es.orEmpty(), it.ru) }
        }

        /** Ключ как в pipeline/personal.py: id записи + её текст. Новый перевод той же записи заменяет старый. */
        internal fun merge(old: List<PersonalResult>, new: List<PersonalResult>): List<PersonalResult> {
            val byKey = LinkedHashMap<String, PersonalResult>()
            (old + new).forEach { byKey["${it.inboxId}:${it.ru}"] = it }
            return byKey.values.toList()
        }
    }
}
