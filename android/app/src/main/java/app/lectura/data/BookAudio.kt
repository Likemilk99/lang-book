package app.lectura.data

import android.content.Context
import android.util.Log
import app.lectura.model.Book
import app.lectura.model.Token
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Дикторская озвучка книг (её готовит `pipeline/audio.py`): по архиву на книгу в GitHub Release «audio».
 * Скачивается по кнопке один раз, дальше всё офлайн: `files/audio/<bookId>/<глава>_<предложение>.m4a`
 * + `manifest.json` с хэшем токенов каждого предложения. Если текст предложения с тех пор изменился,
 * хэш не совпадёт — такое предложение читается системным TTS.
 */
class BookAudio(context: Context) {

    @Serializable
    data class Manifest(val bookId: String, val version: String, val files: Map<String, String>)

    sealed interface Download {
        data class Running(val progress: Float) : Download
        data class Failed(val message: String) : Download
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "audio").apply { mkdirs() }

    /** bookId → версия скачанной озвучки. */
    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Map<String, String>> = _installed.asStateFlow()

    /** bookId → идущее/неудавшееся скачивание. */
    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads: StateFlow<Map<String, Download>> = _downloads.asStateFlow()

    private val manifests = HashMap<String, Manifest?>()
    private val jobs = HashMap<String, Job>()

    private fun dirOf(bookId: String) = File(root, bookId)

    private fun scanInstalled(): Map<String, String> =
        root.listFiles { f -> f.isDirectory && !f.name.endsWith(".tmp") }.orEmpty()
            .mapNotNull { d -> readManifest(d)?.let { d.name to it.version } }
            .toMap()

    private fun readManifest(dir: File): Manifest? = runCatching {
        json.decodeFromString<Manifest>(File(dir, "manifest.json").readText())
    }.getOrNull()

    @Synchronized
    private fun manifest(bookId: String): Manifest? =
        manifests.getOrPut(bookId) { readManifest(dirOf(bookId)) }

    /** Файл диктора для предложения или null (не скачано / текст изменился). */
    fun fileFor(bookId: String, chapter: Int, sentence: Int, tokens: List<Token>): File? {
        if (bookId !in _installed.value) return null
        val name = "${chapter}_$sentence"
        val m = manifest(bookId) ?: return null
        if (m.files[name] != tokenHash(tokens)) return null
        return File(dirOf(bookId), "$name.m4a").takeIf { it.isFile }
    }

    fun isCurrent(book: Book): Boolean {
        val v = _installed.value[book.id] ?: return false
        return book.audio == null || book.audio.version == v
    }

    /** Скачать (или обновить) озвучку книги в фоне; состояние — в [downloads]. */
    fun download(book: Book) {
        val pack = book.audio ?: return
        synchronized(jobs) {
            if (jobs[book.id]?.isActive == true) return
            _downloads.update { it + (book.id to Download.Running(0f)) }
            jobs[book.id] = scope.launch {
                runCatching { fetch(book.id, pack.url, pack.size) }
                    .onSuccess { _downloads.update { it - book.id } }
                    .onFailure { e ->
                        Log.w("BookAudio", "download ${book.id}", e)
                        _downloads.update { it + (book.id to Download.Failed(e.message ?: e.javaClass.simpleName)) }
                    }
            }
        }
    }

    /** Скачать по очереди озвучку всех книг, где её нет или она устарела. */
    fun downloadAll(books: List<Book>) {
        scope.launch {
            for (b in books) {
                if (b.audio == null || isCurrent(b)) continue
                download(b)
                synchronized(jobs) { jobs[b.id] }?.join()
            }
        }
    }

    fun delete(bookId: String) {
        _installed.update { it - bookId }
        synchronized(this) { manifests.remove(bookId) }
        scope.launch { dirOf(bookId).deleteRecursively() }
    }

    fun sizeOnDisk(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private suspend fun fetch(bookId: String, url: String, expected: Long) = withContext(Dispatchers.IO) {
        val part = File(root, "$bookId.zip.part")
        val tmp = File(root, "$bookId.tmp")
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: expected
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastReport > 256 * 1024) {
                                lastReport = done
                                val p = (done.toFloat() / total).coerceIn(0f, 0.99f)
                                _downloads.update { it + (bookId to Download.Running(p)) }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            tmp.deleteRecursively()
            tmp.mkdirs()
            ZipInputStream(part.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    // Только плоские имена из нашего архива: никаких путей вида ../
                    if (e.isDirectory || !NAME.matches(name)) continue
                    File(tmp, name).outputStream().use { zip.copyTo(it) }
                }
            }
            val m = readManifest(tmp) ?: throw IOException("в архиве нет manifest.json")
            if (m.bookId != bookId) throw IOException("архив от другой книги: ${m.bookId}")
            val dir = dirOf(bookId)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("не удалось сохранить озвучку")
            synchronized(this@BookAudio) { manifests.remove(bookId) }
            _installed.update { it + (bookId to m.version) }
        } finally {
            part.delete()
            tmp.deleteRecursively()
        }
    }

    companion object {
        private val NAME = Regex("""(\d+_\d+\.m4a|manifest\.json)""")

        /** Тот же хэш считает pipeline/audio.py (token_hash): sha1 токенов через \u0001, первые 10 hex. */
        fun tokenHash(tokens: List<Token>): String {
            val bytes = MessageDigest.getInstance("SHA-1")
                .digest(tokens.joinToString("\u0001") { it.t }.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }.take(10)
        }
    }
}
