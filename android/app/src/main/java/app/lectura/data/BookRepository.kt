package app.lectura.data

import android.content.Context
import android.net.Uri
import app.lectura.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

data class BookInfo(
    val id: String,
    val title: String,
    val author: String,
    val level: String,
    val chapters: Int,
    val file: File,
    val tags: List<String> = emptyList(),
    val kind: String = Book.KIND_ADAPTED,
    val workId: String = "",
) {
    val isOriginal: Boolean get() = kind == Book.KIND_ORIGINAL
}

/**
 * Книги — это JSON-файлы в /sdcard/Android/data/app.lectura/files/books/.
 * Туда их кладёт `prepare_book.py --push` (adb) или кнопка «Импорт».
 */
class BookRepository(private val context: Context, private val prefs: Prefs) {
    private val json = Json { ignoreUnknownKeys = true }
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "books").apply { mkdirs() }

    private val _books = MutableStateFlow<List<BookInfo>>(emptyList())
    val books: StateFlow<List<BookInfo>> = _books.asStateFlow()

    private val cache = HashMap<String, Pair<Long, Book>>()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        installBundled()
        _books.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f ->
                runCatching { parse(f) }.getOrNull()?.let {
                    BookInfo(it.id, it.title, it.author, it.level, it.chapters.size, f, it.tags, it.kind, it.workId)
                }
            }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun load(id: String): Book = withContext(Dispatchers.IO) {
        val info = _books.value.firstOrNull { it.id == id } ?: run {
            refresh()
            _books.value.first { it.id == id }
        }
        parse(info.file)
    }

    /** Возвращает название импортированной книги. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        val book = json.decodeFromString<Book>(text)
        File(dir, "${book.id}.json").writeText(text)
        refresh()
        book.title
    }

    suspend fun delete(info: BookInfo) = withContext(Dispatchers.IO) {
        info.file.delete()
        prefs.markBundledDeleted(info.file.name)
        refresh()
    }

    @Synchronized
    private fun parse(f: File): Book {
        cache[f.path]?.let { (mtime, book) -> if (mtime == f.lastModified()) return book }
        val book = json.decodeFromString<Book>(f.readText())
        cache[f.path] = f.lastModified() to book
        return book
    }

    /**
     * Копирует встроенные книги. Если в новой версии приложения встроенная книга изменилась
     * (например, добавились фразы и вопросы), копия на телефоне обновляется — прогресс хранится отдельно.
     */
    private fun installBundled() {
        val deleted = prefs.deletedBundled()
        context.assets.list("books").orEmpty().forEach { name ->
            if (name in deleted) return@forEach
            val bytes = context.assets.open("books/$name").use { it.readBytes() }
            val hash = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val target = File(dir, name)
            if (!target.exists() || prefs.bundledHash(name) != hash) {
                target.writeBytes(bytes)
                prefs.setBundledHash(name, hash)
            }
        }
    }
}
