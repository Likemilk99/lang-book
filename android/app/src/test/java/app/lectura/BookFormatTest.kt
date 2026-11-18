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
            ch.sentences.forEachIndexed { si, s ->
                s.tokens.filter { it.r != null }.forEach { t ->
                    if (t.r !in RARITY) err("$where, предл. ${si + 1}: r='${t.r}' у '${t.t}' (можно только $RARITY)")
                    if (t.l == null) err("$where, предл. ${si + 1}: r у не-слова '${t.t}'")
                }
            }
            val text = ch.sentences.joinToString(" ") { it.text }.lowercase()
            ch.glossary.forEachIndexed { i, g ->
                val w = "$where, словарик ${i + 1}"
                if (g.l.isBlank() || g.ru.isBlank()) err("$w: пустые l/ru")
                if (g.form.isNotBlank() && g.form.lowercase() !in text) err("$w: форма '${g.form}' не найдена в тексте главы")
            }
        }

        if (book.kind !in setOf(Book.KIND_ADAPTED, Book.KIND_ORIGINAL)) err("неизвестный kind '${book.kind}'")
        if (book.isOriginal) {
            if (book.license.isBlank()) err("оригинал без license")
            if (book.workId.isBlank()) err("оригинал без workId")
            book.chapters.forEachIndexed { ci, ch ->
                if (ch.glossary.isEmpty()) err("глава ${ci + 1}: у оригинала нет словарика (glossary)")
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
        // В оригиналах диалогов может не быть (или они архаичные), поэтому phrases и replies
        // там необязательны; вопросы на понимание — в каждой главе любой книги.
        for (file in bookFiles()) {
            val book = json.decodeFromString(Book.serializer(), file.readText())
            assertTrue("${file.name}: нет персонажей", book.characters.isNotEmpty())
            assertTrue("${file.name}: нет ни одной реплики с sp",
                book.chapters.any { ch -> ch.sentences.any { it.sp != null } })
            book.chapters.forEachIndexed { ci, ch ->
                assertTrue("${file.name}, глава ${ci + 1}: нет questions", ch.questions.isNotEmpty())
                if (!book.isOriginal) {
                    assertTrue("${file.name}, глава ${ci + 1}: нет phrases", ch.phrases.isNotEmpty())
                    assertTrue("${file.name}, глава ${ci + 1}: нет replies", ch.replies.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun versionsOfOneWorkDiffer() {
        // Книги с общим workId — это «лесенка» версий одного произведения (A2, B1, оригинал):
        // у каждой своя пара (kind, level), иначе в библиотеке не понять, какая где.
        val books = bookFiles().map { json.decodeFromString(Book.serializer(), it.readText()) }
        val errors = mutableListOf<String>()
        books.filter { it.workId.isNotBlank() }.groupBy { it.workId }.forEach { (work, list) ->
            val keys = list.map { it.kind to it.level }
            if (keys.size != keys.toSet().size) errors += "$work: повторяются (kind, level): $keys"
            if (list.map { it.id }.toSet().size != list.size) errors += "$work: повторяются id"
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }

    @Test
    fun ladderBecquerMonteAnimas() {
        val ladder = bookFiles().map { json.decodeFromString(Book.serializer(), it.readText()) }
            .filter { it.workId == "becquer-monte-animas" }
        if (ladder.isEmpty()) return
        val keys = ladder.map { it.kind to it.level }.toSet()
        assertTrue("нет оригинала: $keys", ladder.any { it.isOriginal })
        assertTrue("нет адаптации A2: $keys", Book.KIND_ADAPTED to "A2" in keys)
        assertTrue("нет адаптации B1: $keys", Book.KIND_ADAPTED to "B1" in keys)
    }

    private companion object {
        val RARITY = setOf("rare", "old")
    }

    @Test
    fun tagsComeFromTheCatalog() {
        val known = app.lectura.model.Tags.groups.flatMap { g -> g.tags.map { it.id } }.toSet()
        java.io.File("src/main/assets/books").listFiles { f -> f.extension == "json" }!!.forEach { f ->
            val book = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<app.lectura.model.Book>(f.readText())
            book.tags.forEach { t -> org.junit.Assert.assertTrue("${f.name}: unknown tag $t", t in known) }
        }
    }
}
