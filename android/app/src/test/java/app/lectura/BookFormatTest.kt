package app.lectura

import app.lectura.model.Book
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Проверяет книги из assets: что они читаются моделью приложения и что
 * необязательные поля (characters, sp, phrases, questions, replies) согласованы.
 * Gradle запускает unit-тесты с рабочей папкой android/app.
 */
class BookFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun bookFiles(): List<File> {
        val dir = File("src/main/assets/books")
        assertTrue("нет папки ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("в ${dir.absolutePath} нет книг", files.isNotEmpty())
        return files
    }

    @Test
    fun allAssetBooksDecodeAndAreConsistent() {
        val errors = mutableListOf<String>()
        for (file in bookFiles()) {
            val book = try {
                json.decodeFromString(Book.serializer(), file.readText())
            } catch (e: Exception) {
                errors += "${file.name}: не читается: ${e.message}"
                continue
            }
            check(file.name, book, errors)
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }

    private fun check(name: String, book: Book, errors: MutableList<String>) {
        fun err(msg: String) { errors += "$name: $msg" }

        if (book.id.isBlank()) err("пустой id")
        if (book.chapters.isEmpty()) err("нет глав")

        val names = book.characters.map { it.name }
        if (names.any { it.isBlank() }) err("персонаж с пустым именем")
        if (names.size != names.toSet().size) err("повторяющиеся персонажи: $names")
        book.characters.filter { it.gender !in setOf("", "m", "f") }
            .forEach { err("персонаж ${it.name}: неизвестный gender '${it.gender}'") }
        val nameSet = names.toSet()

        book.chapters.forEachIndexed { ci, ch ->
            val where = "глава ${ci + 1}"
            if (ch.sentences.isEmpty()) err("$where: нет предложений")
            ch.sentences.forEachIndexed { si, s ->
                if (s.tokens.isEmpty()) err("$where, предл. ${si + 1}: нет токенов")
                if (s.sp != null && s.sp !in nameSet) err("$where, предл. ${si + 1}: sp '${s.sp}' не персонаж")
            }
            ch.phrases.forEachIndexed { i, p ->
                if (p.es.isBlank() || p.ru.isBlank()) err("$where, фраза ${i + 1}: пустые es/ru")
            }
            ch.questions.forEachIndexed { i, q ->
                val w = "$where, вопрос ${i + 1}"
                if (q.q.isBlank()) err("$w: пустой вопрос")
                checkOptions(w, q.options, q.answer, ::err)
            }
            ch.replies.forEachIndexed { i, r ->
                val w = "$where, ответ ${i + 1}"
                if (r.prompt.isBlank()) err("$w: пустая реплика")
                checkOptions(w, r.options, r.answer, ::err)
            }
        }
    }

    private fun checkOptions(where: String, options: List<String>, answer: Int, err: (String) -> Unit) {
        if (options.size < 2) err("$where: меньше двух вариантов")
        if (options.any { it.isBlank() }) err("$where: пустой вариант")
        if (options.size != options.toSet().size) err("$where: повторяющиеся варианты")
        if (answer !in options.indices) err("$where: answer=$answer вне 0..${options.size - 1}")
    }

    @Test
    fun enrichedSampleBooksHaveContent() {
        // Книги в assets прошли шаг enrich в pipeline — проверяем, что поля реально заполнены.
        for (file in bookFiles()) {
            val book = json.decodeFromString(Book.serializer(), file.readText())
            assertTrue("${file.name}: нет персонажей", book.characters.isNotEmpty())
            assertTrue("${file.name}: нет ни одной реплики с sp",
                book.chapters.any { ch -> ch.sentences.any { it.sp != null } })
            book.chapters.forEachIndexed { ci, ch ->
                assertTrue("${file.name}, глава ${ci + 1}: нет phrases", ch.phrases.isNotEmpty())
                assertTrue("${file.name}, глава ${ci + 1}: нет questions", ch.questions.isNotEmpty())
                assertTrue("${file.name}, глава ${ci + 1}: нет replies", ch.replies.isNotEmpty())
            }
        }
    }
}
