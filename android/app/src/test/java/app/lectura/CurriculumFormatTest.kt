package app.lectura

import app.lectura.model.CURRICULUM_LEVELS
import app.lectura.model.GrammarLevel
import app.lectura.model.VocabList
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Проверяет учебную программу из assets/curriculum (словарь и грамматику уровней):
 * что файлы читаются моделью приложения и что данные согласованы.
 * Gradle запускает unit-тесты с рабочей папкой android/app.
 */
class CurriculumFormatTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File("src/main/assets/curriculum")
    private val vocabName = Regex("vocab_([A-Z][0-9])\\.json")
    private val grammarName = Regex("grammar_([A-Z][0-9])\\.json")

    private fun files(re: Regex): List<Pair<String, File>> {
        assertTrue("нет папки ${dir.absolutePath}", dir.isDirectory)
        return dir.listFiles().orEmpty().sortedBy { it.name }
            .mapNotNull { f -> re.matchEntire(f.name)?.let { it.groupValues[1] to f } }
    }

    @Test
    fun vocabDecodesAndLemmasAreUniqueAcrossLevels() {
        val errors = mutableListOf<String>()
        val files = files(vocabName)
        assertTrue("нет vocab_*.json", files.isNotEmpty())
        val levelOf = mutableMapOf<String, String>()
        for ((level, file) in files) {
            val v = try {
                json.decodeFromString(VocabList.serializer(), file.readText())
            } catch (e: Exception) {
                errors += "${file.name}: не читается: ${e.message}"; continue
            }
            if (level !in CURRICULUM_LEVELS) errors += "${file.name}: неизвестный уровень $level"
            if (v.level != level) errors += "${file.name}: level=${v.level}, ожидался $level"
            if (v.themes.map { it.id }.toSet().size != v.themes.size) errors += "${file.name}: повтор id темы"
            for (t in v.themes) {
                if (t.id.isBlank() || t.title.isBlank()) errors += "${file.name}: пустой id/title темы"
                for (w in t.words) {
                    if (w.l.isBlank() || w.ru.isBlank()) errors += "${file.name}/${t.id}: пустое слово $w"
                    if (w.l != w.l.lowercase()) errors += "${file.name}/${t.id}: не нижний регистр «${w.l}»"
                    if (w.g !in setOf("", "m", "f")) errors += "${file.name}/${t.id}: g=${w.g} у «${w.l}»"
                    val prev = levelOf.put(w.l, level)
                    if (prev != null) errors += "«${w.l}» есть и в $prev, и в $level (${t.id})"
                }
            }
        }
        if (errors.isNotEmpty()) fail(errors.take(50).joinToString("\n"))
    }

    @Test
    fun grammarDecodesAndIsConsistent() {
        val errors = mutableListOf<String>()
        val files = files(grammarName)
        assertTrue("нет grammar_*.json", files.isNotEmpty())
        val topicIds = mutableSetOf<String>()
        val related = mutableListOf<Pair<String, String>>()
        for ((level, file) in files) {
            val g = try {
                json.decodeFromString(GrammarLevel.serializer(), file.readText())
            } catch (e: Exception) {
                errors += "${file.name}: не читается: ${e.message}"; continue
            }
            if (level !in CURRICULUM_LEVELS) errors += "${file.name}: неизвестный уровень $level"
            if (g.level != level) errors += "${file.name}: level=${g.level}, ожидался $level"
            for (t in g.topics) {
                val where = "${file.name}/${t.id}"
                if (!topicIds.add(t.id)) errors += "$where: повтор id темы"
                if (t.title.isBlank() || t.summary.isBlank()) errors += "$where: пустой title/summary"
                if (t.sections.isEmpty() || t.sections.any { it.title.isBlank() || it.text.isBlank() })
                    errors += "$where: пустые разделы"
                for (s in t.sections) if (s.table.isNotEmpty() && s.table.map { it.size }.toSet().size != 1)
                    errors += "$where: таблица «${s.title}» с разной длиной строк"
                if (t.exercises.size < 15) errors += "$where: всего ${t.exercises.size} упражнений"
                t.related.forEach { related += t.id to it }
                for ((i, e) in t.exercises.withIndex()) {
                    val ex = "$where#$i (${e.type})"
                    if (e.answer.isBlank()) errors += "$ex: пустой answer"
                    when (e.type) {
                        "choice" -> {
                            if ("___" !in e.sentence) errors += "$ex: нет ___"
                            if (e.answer !in e.options) errors += "$ex: ответа «${e.answer}» нет в ${e.options}"
                        }
                        "input" -> if ("___" !in e.sentence) errors += "$ex: нет ___"
                        "order" -> if (e.answer.split(' ').size < 2) errors += "$ex: слишком короткое"
                        "transform" -> if (e.sentence.isBlank() || e.prompt.isBlank()) errors += "$ex: нет sentence/prompt"
                        else -> errors += "$ex: неизвестный тип"
                    }
                }
            }
        }
        // Ссылки на темы уровней, которых ещё нет в assets, не проверяем (уровни генерируются по очереди).
        val levels = files.map { it.first.lowercase() }.toSet()
        for ((from, to) in related)
            if (to.substringBefore('-') in levels && to !in topicIds) errors += "$from: related «$to» не найден"
        if (errors.isNotEmpty()) fail(errors.take(50).joinToString("\n"))
    }
}
